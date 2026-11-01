import Foundation

/// Takes an instance from one Minecraft to another. A mod is built for one Minecraft, so each is
/// swapped for its build for the new one, along with whatever that needs. The old jars and a copy
/// of the worlds stay beside them, because a world opened in a newer Minecraft can't go back.
public struct VersionMove: Sendable {

    /// Modrinth projects that can't run beside the Metal renderer: Sodium, whose terrain renderer
    /// it replaces, and what is built on Sodium (Iris, Sodium Extra, Reese's Sodium Options).
    static let needsMojangRenderer: Set<String> = ["AANobbMI", ContentInstaller.iris, "PtjYWJkn", "Bh37bMuy"]

    public struct Report: Equatable, Sendable {
        /// The files now in the mods folder.
        public var updated: [String] = []
        /// Mods left out because the Metal renderer does their job.
        public var replaced: [String] = []
        /// Mods turned off, unknown to Modrinth, or with no build for the new Minecraft.
        public var leftBehind: [String] = []
    }

    public var installer: ContentInstaller
    /// The Minecraft the instance last ran.
    public var from: String
    /// Whether the instance runs Mojang's renderer, and so can keep Sodium and what needs it.
    public var keepSodium: Bool

    public init(installer: ContentInstaller, from: String, keepSodium: Bool) {
        self.installer = installer
        self.from = from
        self.keepSodium = keepSodium
    }

    public func run(in directory: URL, progress: @Sendable (DownloadProgress) -> Void = { _ in }) async throws -> Report {
        let content = InstanceContent(directory: directory)
        let aside = directory.appending(path: "mods-\(from)", directoryHint: .isDirectory)
        // A move that stopped partway has put the old mods aside already, and what is in the mods
        // folder now is what it downloaded, so that stays where it is.
        let resuming = FileManager.default.fileExists(atPath: aside.path)
        let own = resuming ? [] : Self.ownMods(in: content.folder(.mod))
        // Modrinth is asked first, so failing here leaves the instance as it was.
        let (steps, report) = try await plan(Self.ownMods(in: aside) + own)
        try Self.moveAside(own, to: aside)
        try Self.backUpWorlds(in: directory, from: from)
        try await installer.install(steps, into: content, progress: progress)
        return report
    }

    private func plan(_ files: [URL]) async throws -> (steps: [ContentInstaller.Step], report: Report) {
        var report = Report()
        var hashes: [String: URL] = [:]
        for file in files {
            if file.pathExtension == "jar" {
                hashes[try Downloader.sha1(of: file)] = file
            } else {
                report.leftBehind.append(file.lastPathComponent)
            }
        }
        let known = try await installer.modrinth.versions(sha1: Array(hashes.keys))
        var steps: [ContentInstaller.Step] = []
        var projects: Set<String> = [ContentInstaller.fabricAPI]
        for (hash, file) in hashes.sorted(by: { $0.value.lastPathComponent < $1.value.lastPathComponent }) {
            guard let project = known[hash]?.projectID else {
                report.leftBehind.append(file.lastPathComponent)
                continue
            }
            guard !projects.contains(project) else { continue }
            do {
                let plan = try await installer.plan(project, kind: .mod, installed: projects)
                if !keepSodium, plan.contains(where: { Self.needsMojangRenderer.contains($0.version.projectID) }) {
                    report.replaced.append(file.lastPathComponent)
                } else {
                    steps += plan
                    projects.formUnion(plan.map(\.version.projectID))
                }
            } catch LauncherError.noCompatibleVersion {
                report.leftBehind.append(file.lastPathComponent)
            }
        }
        report.updated = steps.compactMap { $0.version.primaryFile?.filename }
        return (steps, report)
    }

    /// The jars in a mods folder, on or off, that the launcher didn't put there.
    static func ownMods(in folder: URL) -> [URL] {
        let managed = Installer.managedMods(in: folder)
        let names = (try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? []
        return names
            .filter { ($0.hasSuffix(".jar") || $0.hasSuffix(".jar" + InstanceContent.disabledSuffix)) && !managed.contains($0) }
            .sorted()
            .map { folder.appending(path: $0) }
    }

    static func moveAside(_ files: [URL], to folder: URL) throws {
        guard !files.isEmpty else { return }
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        for file in files {
            try FileManager.default.moveItem(at: file, to: folder.appending(path: file.lastPathComponent))
        }
    }

    /// Copies `saves` beside itself, once, finishing under its real name only when it's complete.
    static func backUpWorlds(in directory: URL, from: String) throws {
        let manager = FileManager.default
        let saves = directory.appending(path: "saves", directoryHint: .isDirectory)
        let backup = directory.appending(path: "saves-\(from)", directoryHint: .isDirectory)
        let worlds = (try? manager.contentsOfDirectory(atPath: saves.path)) ?? []
        guard worlds.contains(where: { !$0.hasPrefix(".") }), !manager.fileExists(atPath: backup.path) else { return }
        let partial = directory.appending(path: "saves-\(from).partial", directoryHint: .isDirectory)
        try? manager.removeItem(at: partial)
        try manager.copyItem(at: saves, to: partial)
        try manager.moveItem(at: partial, to: backup)
    }
}
