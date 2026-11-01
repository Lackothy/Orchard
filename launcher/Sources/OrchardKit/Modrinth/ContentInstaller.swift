import Foundation

/// Installs Modrinth projects into an instance: the newest version for this Minecraft, and
/// everything it needs that the instance doesn't have yet.
public struct ContentInstaller: Sendable {

    /// Always in every instance, put there by the launcher, so never installed or updated from here.
    public static let fabricAPI = "P7dR8mSH"
    public static let iris = "YL57xq9U"

    /// Requirements Modrinth doesn't record. Fresh Animations needs Entity Model Features and
    /// Entity Texture Features to work on Fabric.
    static let alsoNeeds: [String: [String]] = ["50dA9Sha": ["4I1XuqiY", "BVzZfTc1"]]

    /// One file to put in the instance.
    public struct Step: Hashable, Sendable {
        public var version: ModrinthVersion
        public var kind: ContentKind
    }

    public var modrinth: Modrinth
    public var downloader: Downloader
    public var minecraft: String

    public init(modrinth: Modrinth, downloader: Downloader = Downloader(concurrency: 6), minecraft: String) {
        self.modrinth = modrinth
        self.downloader = downloader
        self.minecraft = minecraft
    }

    /// The newest version of the project for this game, a release where there is one.
    public func newest(_ project: String, kind: ContentKind) async throws -> ModrinthVersion {
        let versions = try await modrinth.versions(of: project, kind: kind, minecraft: minecraft)
        guard let version = versions.first(where: { $0.versionType == "release" }) ?? versions.first else {
            throw LauncherError.noCompatibleVersion(minecraft)
        }
        return version
    }

    /// What installing `project` takes: its newest version, then each project it requires that
    /// isn't in `installed` (project IDs), recursively. Shader packs bring Iris.
    public func plan(_ project: String, kind: ContentKind, installed: Set<String>) async throws -> [Step] {
        var steps: [Step] = []
        var seen = installed.union([Self.fabricAPI])
        seen.remove(project)
        try await add(project, version: nil, kind: kind, into: &steps, seen: &seen)
        return steps
    }

    /// The projects a version needs, by ID, with the version Modrinth pins for each if it pins
    /// one: its required dependencies, what Modrinth doesn't record, and Iris for a shader pack.
    public func requirements(of version: ModrinthVersion, kind: ContentKind) async throws -> [(project: String, version: String?)] {
        var required: [(project: String, version: String?)] = []
        for dependency in version.dependencies where dependency.dependencyType == "required" {
            var project = dependency.projectID
            if project == nil, let id = dependency.versionID {
                project = try await modrinth.version(id).projectID
            }
            if let project {
                required.append((project, dependency.versionID))
            }
        }
        required += (Self.alsoNeeds[version.projectID] ?? []).map { ($0, nil) }
        if kind == .shader {
            required.append((Self.iris, nil))
        }
        return required
    }

    private func add(_ project: String, version pinned: String?, kind: ContentKind,
                     into steps: inout [Step], seen: inout Set<String>) async throws {
        guard seen.insert(project).inserted else { return }
        let version = if let pinned { try await modrinth.version(pinned) } else { try await newest(project, kind: kind) }
        steps.append(Step(version: version, kind: kind))
        for (required, pinned) in try await requirements(of: version, kind: kind) where !seen.contains(required) {
            let requiredKind = try await modrinth.project(required).kind ?? .mod
            try await add(required, version: pinned, kind: requiredKind, into: &steps, seen: &seen)
        }
    }

    /// Downloads each step's file into its folder, checked against its SHA-1. A step whose project
    /// is in `replacing` takes that file's place, keeping it turned off if it was. Returns the new files.
    @discardableResult
    public func install(_ steps: [Step], into content: InstanceContent, replacing: [String: URL] = [:],
                        progress: @Sendable (DownloadProgress) -> Void = { _ in }) async throws -> [URL] {
        var downloads: [Download] = []
        var placed: [(project: String, file: URL)] = []
        for step in steps {
            guard let file = step.version.primaryFile, let url = URL(string: file.url) else { continue }
            let destination = content.folder(step.kind).appending(path: file.filename)
            downloads.append(Download(url: url, destination: destination, sha1: file.hashes["sha1"], size: file.size))
            placed.append((step.version.projectID, destination))
        }
        try await downloader.run(Downloader.missing(downloads, verify: true), progress: progress)

        var results: [URL] = []
        for (project, file) in placed {
            var final = file
            if let old = replacing[project], old.standardizedFileURL != file.standardizedFileURL {
                if old.lastPathComponent.hasSuffix(InstanceContent.disabledSuffix) {
                    final = file.deletingLastPathComponent().appending(path: file.lastPathComponent + InstanceContent.disabledSuffix)
                    try? FileManager.default.removeItem(at: final)
                    try FileManager.default.moveItem(at: file, to: final)
                }
                try? FileManager.default.trashItem(at: old, resultingItemURL: nil)
            }
            results.append(final)
        }
        return results
    }
}
