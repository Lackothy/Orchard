import Foundation

public struct VersionMove: Sendable {

    static let needsMojangRenderer: Set<String> = ["AANobbMI", ContentInstaller.iris, "PtjYWJkn", "Bh37bMuy"]

    public struct Report: Equatable, Sendable {
        public var updated: [String] = []
        public var replaced: [String] = []
        public var leftBehind: [String] = []
    }

    public var installer: ContentInstaller
    public var from: String
    public var keepSodium: Bool

    public init(installer: ContentInstaller, from: String, keepSodium: Bool) {
        self.installer = installer
        self.from = from
        self.keepSodium = keepSodium
    }

    public func run(in directory: URL, progress: @Sendable (DownloadProgress) -> Void = { _ in }) async throws -> Report {
        let content = InstanceContent(directory: directory)
        let aside = directory.appending(path: "mods-\(from)", directoryHint: .isDirectory)
        let resuming = FileManager.default.fileExists(atPath: aside.path)
        let own = resuming ? [] : Self.ownMods(in: content.folder(.mod))
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
