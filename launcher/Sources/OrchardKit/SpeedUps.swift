import Foundation

public struct SpeedUps: Sendable {

    public static let projects = Recommended.performance.picks.map(\.id)
    public static let macFixes = Recommended.macFixes.picks.map(\.id)

    public var installer: ContentInstaller
    public var set: [String]

    public init(installer: ContentInstaller, set: [String] = SpeedUps.projects) {
        self.installer = installer
        self.set = set
    }

    public func add(to content: InstanceContent, progress: @Sendable (DownloadProgress) -> Void = { _ in }) async throws -> [String] {
        let present = try await projects(in: content.folder(.mod))
        var installed = present.union([ContentInstaller.fabricAPI])
        var steps: [ContentInstaller.Step] = []
        for project in set where !installed.contains(project) {
            let plan = try await installer.plan(project, kind: .mod, installed: installed)
            steps += plan
            installed.formUnion(plan.map(\.version.projectID))
        }
        guard !steps.isEmpty else { return [] }
        try await installer.install(steps, into: content, progress: progress)
        return steps.compactMap { $0.version.primaryFile?.filename }
    }

    private func projects(in folder: URL) async throws -> Set<String> {
        var hashes: [String] = []
        for file in VersionMove.ownMods(in: folder) {
            hashes.append(try Downloader.sha1(of: file))
        }
        guard !hashes.isEmpty else { return [] }
        return Set(try await installer.modrinth.versions(sha1: hashes).values.map(\.projectID))
    }
}
