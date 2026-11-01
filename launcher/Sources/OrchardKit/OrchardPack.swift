import Foundation

/// The Orchard Pack: resource packs the game joins into one that is always on (`OrchardPack` in
/// the mod). They are downloaded from Modrinth into the instance's `orchard-pack` folder at Play,
/// as a modpack would have them: Orchard never carries their files, as most are All Rights
/// Reserved. `order.txt` lists them for the mod, lowest first, a line each: project ID, file name
/// and title, split by tabs. Once there, a pack isn't downloaded again.
public struct OrchardPack: Sendable {

    public static let folder = "orchard-pack"

    /// Lowest first: where two packs have the same file, the later one wins. Fast Better Grass
    /// asks to be on top.
    public static let projects = [
        "CZrLuVQo", // Low Shield, Oculie
        "1jZGHJ05", // Low Fire, Oculie
        "O7z3QKAG", // Icons, WeNAN Studios
        "UGEVQ6t9", // Fancy Crops, Bee
        "6udpuGCH", // Even Better Enchants, Mythitorium
        "hFoOkEdk", // Fancy Beds, Agente_511
        "dspVZXKP", // Fast Better Grass
    ]

    public var installer: ContentInstaller

    public init(installer: ContentInstaller) {
        self.installer = installer
    }

    /// Downloads whichever packs the instance's folder lacks and writes `order.txt`. The first
    /// time, the player's own copies of the packs are turned off, as the Orchard Pack has them.
    /// Returns the files added.
    public func ensure(in directory: URL, progress: @Sendable (DownloadProgress) -> Void = { _ in }) async throws -> [String] {
        let folder = directory.appending(path: Self.folder, directoryHint: .isDirectory)
        let order = folder.appending(path: "order.txt")
        let first = !FileManager.default.fileExists(atPath: order.path)
        var have: [String: (file: String, title: String)] = [:]
        for line in (try? String(contentsOf: order, encoding: .utf8))?.split(whereSeparator: \.isNewline) ?? [] {
            let cells = line.split(separator: "\t", omittingEmptySubsequences: false).map(String.init)
            if cells.count >= 3, FileManager.default.fileExists(atPath: folder.appending(path: cells[1]).path) {
                have[cells[0]] = (cells[1], cells[2])
            }
        }

        var downloads: [Download] = []
        for project in Self.projects where have[project] == nil {
            let version = try await installer.newest(project, kind: .resourcePack)
            guard let file = version.primaryFile, let url = URL(string: file.url) else { continue }
            let title = try await installer.modrinth.project(project).title
                .replacingOccurrences(of: "\t", with: " ").replacingOccurrences(of: "\n", with: " ")
            downloads.append(Download(url: url, destination: folder.appending(path: file.filename), sha1: file.hashes["sha1"], size: file.size))
            have[project] = (file.filename, title)
        }
        guard first || !downloads.isEmpty else { return [] }

        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try await installer.downloader.run(Downloader.missing(downloads, verify: true), progress: progress)
        let lines = Self.projects.compactMap { project in have[project].map { "\(project)\t\($0.file)\t\($0.title)" } }
        try (lines.joined(separator: "\n") + "\n").write(to: order, atomically: true, encoding: .utf8)
        if first {
            try await turnOffCopies(in: InstanceContent(directory: directory))
        }
        return downloads.map(\.destination.lastPathComponent)
    }

    /// Turns off the player's resource packs that are one of the Orchard Pack's, matched by SHA-1.
    private func turnOffCopies(in content: InstanceContent) async throws {
        let on = content.items(.resourcePack).filter { $0.enabled && !Self.isDirectory($0.file) }
        var byHash: [String: ContentItem] = [:]
        for item in on {
            byHash[try Downloader.sha1(of: item.file)] = item
        }
        guard !byHash.isEmpty else { return }
        for (hash, version) in try await installer.modrinth.versions(sha1: Array(byHash.keys))
        where Self.projects.contains(version.projectID) {
            if let item = byHash[hash] {
                try content.setEnabled(item, false)
            }
        }
    }

    private static func isDirectory(_ url: URL) -> Bool {
        (try? url.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true
    }
}
