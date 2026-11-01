import Foundation

/// A complete, ready-to-start install: what `LaunchCommand` needs.
public struct Installation: Sendable {
    public var paths: Paths
    /// The instance's folder, where the game runs.
    public var gameDirectory: URL
    public var version: VersionInfo
    public var fabric: FabricProfile
    public var java: URL
    /// Libraries, then the game itself.
    public var classpath: [URL]
    public var loggingConfig: URL?
}

public enum InstallStage: Sendable, Equatable {
    /// Fetching the lists of what to download; quick unless the network is slow.
    case checking
    case downloading(DownloadProgress)
    /// Unpacking the Java runtime's links and putting the mods in place.
    case finishing
}

/// Brings the install up to date: the game, its libraries and assets, Mojang's Java runtime and
/// Fabric Loader, shared by every instance, then Fabric API and the Orchard mod in the instance
/// being started. Only what is missing is downloaded.
///
/// Metadata is cached beside the files, so with no network the last install still starts.
public struct Installer: Sendable {

    public var paths: Paths
    public var edition: Edition
    public var downloader: Downloader
    public var platform: Platform

    static let assetHost = URL(string: "https://resources.download.minecraft.net/")!
    static let javaPlatform = "mac-os-arm64"

    public init(paths: Paths, edition: Edition, downloader: Downloader = Downloader(), platform: Platform = Platform()) {
        self.paths = paths
        self.edition = edition
        self.downloader = downloader
        self.platform = platform
    }

    /// With `verify`, files already in place are hashed too, to repair a damaged install. The Metal
    /// renderer is in Orchard's jar; `LaunchOptions.metal` says whether it draws.
    public func install(into gameDirectory: URL, verify: Bool = false,
                        progress: @Sendable (InstallStage) -> Void) async throws -> Installation {
        progress(.checking)
        guard let modJar = edition.modJar, FileManager.default.fileExists(atPath: modJar.path) else {
            throw LauncherError.modNotBuilt
        }
        let manifest: VersionManifest = try await metadata(
            VersionManifest.url, cache: paths.metadata.appending(path: "version_manifest_v2.json"))
        guard let entry = manifest.versions.first(where: { $0.id == edition.minecraft }) else {
            throw LauncherError.unknownVersion(edition.minecraft)
        }
        let version: VersionInfo = try await metadata(
            entry.url, cache: paths.version(entry.id).appending(path: "\(entry.id).json"), sha1: entry.sha1)
        let fabricID = "fabric-loader-\(edition.loader)-\(edition.minecraft)"
        let fabric: FabricProfile = try await metadata(
            FabricProfile.url(minecraft: edition.minecraft, loader: edition.loader),
            cache: paths.version(fabricID).appending(path: "\(fabricID).json"))
        let assets: AssetIndex = try await metadata(
            version.assetIndex.url,
            cache: paths.assets.appending(path: "indexes/\(version.assetIndex.id).json"),
            sha1: version.assetIndex.sha1)
        let component = version.javaVersion.component
        let runtimes: JavaRuntimeIndex = try await metadata(
            JavaRuntimeIndex.url, cache: paths.metadata.appending(path: "java-runtimes.json"))
        guard let runtime = runtimes.platforms[Self.javaPlatform]?[component]?.first else {
            throw LauncherError.noJavaRuntime(component)
        }
        let java: JavaRuntimeManifest = try await metadata(
            runtime.manifest.url, cache: paths.runtimes.appending(path: "\(component).json"),
            sha1: runtime.manifest.sha1)

        var downloads: [Download] = []
        var classpath: [URL] = []
        for library in fabric.libraries {
            guard let path = Maven.path(of: library.name), let repository = library.url else { continue }
            let url = repository.appending(path: path)
            var sha1 = library.sha1
            if sha1 == nil {
                sha1 = try await checksum(url, cache: paths.libraries.appending(path: path + ".sha1"))
            }
            let destination = paths.libraries.appending(path: path)
            downloads.append(Download(url: url, destination: destination, sha1: sha1, size: library.size))
            classpath.append(destination)
        }
        for library in version.libraries where (library.rules ?? []).allows(platform) {
            guard let artifact = library.downloads?.artifact else { continue }
            let destination = paths.libraries.appending(path: artifact.path)
            downloads.append(Download(url: artifact.url, destination: destination,
                                      sha1: artifact.sha1, size: artifact.size))
            classpath.append(destination)
        }
        let client = paths.version(version.id).appending(path: "\(version.id).jar")
        downloads.append(Download(url: version.downloads.client.url, destination: client,
                                  sha1: version.downloads.client.sha1, size: version.downloads.client.size))
        classpath.append(client)

        var loggingConfig: URL?
        if let logging = version.logging?.client {
            let file = paths.assets.appending(path: "log_configs/\(logging.file.id)")
            downloads.append(Download(url: logging.file.url, destination: file,
                                      sha1: logging.file.sha1, size: logging.file.size))
            loggingConfig = file
        }
        for object in assets.objects.values {
            let path = "\(object.hash.prefix(2))/\(object.hash)"
            downloads.append(Download(url: Self.assetHost.appending(path: path),
                                      destination: paths.assets.appending(path: "objects/\(path)"),
                                      sha1: object.hash, size: object.size))
        }
        let runtimeRoot = paths.runtime(component)
        var javaBinary: URL?
        for (path, entry) in java.files where entry.type == "file" {
            guard let raw = entry.downloads?.raw else { continue }
            let destination = runtimeRoot.appending(path: path)
            downloads.append(Download(url: raw.url, destination: destination, sha1: raw.sha1, size: raw.size,
                                      executable: entry.executable ?? false))
            if path.hasSuffix("/bin/java") {
                javaBinary = destination
            }
        }
        guard let javaBinary else {
            throw LauncherError.noJavaRuntime(component)
        }
        let fabricAPIURL = FabricMaven.fabricAPI(edition.fabricAPI)
        let fabricAPIName = "fabric-api-\(edition.fabricAPI).jar"
        downloads.append(Download(
            url: fabricAPIURL, destination: paths.libraries.appending(path: "orchard-mods/\(fabricAPIName)"),
            sha1: try await checksum(fabricAPIURL, cache: paths.metadata.appending(path: fabricAPIName + ".sha1")),
            size: nil))

        let missing = Downloader.missing(downloads, verify: verify)
        if !missing.isEmpty {
            try await downloader.run(missing) { progress(.downloading($0)) }
        }

        progress(.finishing)
        try linkRuntime(java, at: runtimeRoot)
        // An orchard-renderer.jar from before the renderer was part of Orchard's jar is in the
        // ledger, so it goes with the other old copies.
        let mods = [
            fabricAPIName: paths.libraries.appending(path: "orchard-mods/\(fabricAPIName)"),
            "orchard.jar": modJar,
        ]
        try installMods(mods, into: gameDirectory.appending(path: "mods", directoryHint: .isDirectory))
        try FileManager.default.createDirectory(at: paths.natives(fabric.id), withIntermediateDirectories: true)
        return Installation(paths: paths, gameDirectory: gameDirectory, version: version, fabric: fabric,
                            java: javaBinary, classpath: classpath, loggingConfig: loggingConfig)
    }

    // MARK: - Metadata

    /// A JSON document, from the cache when its hash is known and matches, else from the network,
    /// falling back to the cache when the network fails.
    func metadata<T: Decodable>(_ url: URL, cache: URL, sha1: String? = nil) async throws -> T {
        let files = FileManager.default
        if let sha1, files.fileExists(atPath: cache.path), (try? Downloader.sha1(of: cache)) == sha1 {
            return try JSONDecoder().decode(T.self, from: Data(contentsOf: cache))
        }
        do {
            let (data, response) = try await downloader.session.data(from: url)
            if let http = response as? HTTPURLResponse, http.statusCode != 200 {
                throw LauncherError.download(url.lastPathComponent, status: http.statusCode)
            }
            let decoded = try JSONDecoder().decode(T.self, from: data)
            try files.createDirectory(at: cache.deletingLastPathComponent(), withIntermediateDirectories: true)
            try data.write(to: cache, options: .atomic)
            return decoded
        } catch let error as URLError {
            guard files.fileExists(atPath: cache.path) else {
                throw error.code == .notConnectedToInternet ? LauncherError.offlineWithoutCache : error
            }
            return try JSONDecoder().decode(T.self, from: Data(contentsOf: cache))
        }
    }

    /// The `.sha1` file Maven publishes beside an artifact, cached once fetched.
    func checksum(_ artifact: URL, cache: URL) async throws -> String {
        if let cached = try? String(contentsOf: cache, encoding: .utf8) {
            return cached
        }
        let (data, response) = try await downloader.session.data(from: URL(string: artifact.absoluteString + ".sha1")!)
        if let http = response as? HTTPURLResponse, http.statusCode != 200 {
            throw LauncherError.download(artifact.lastPathComponent + ".sha1", status: http.statusCode)
        }
        let sha1 = String(decoding: data, as: UTF8.self).prefix(40).lowercased()
        try FileManager.default.createDirectory(at: cache.deletingLastPathComponent(), withIntermediateDirectories: true)
        try sha1.write(to: cache, atomically: true, encoding: .utf8)
        return sha1
    }

    // MARK: - Finishing

    /// Creates the runtime's directories and symbolic links; its files are already downloaded.
    func linkRuntime(_ manifest: JavaRuntimeManifest, at root: URL) throws {
        let files = FileManager.default
        for (path, entry) in manifest.files.sorted(by: { $0.key < $1.key }) {
            let url = root.appending(path: path)
            switch entry.type {
            case "directory":
                try files.createDirectory(at: url, withIntermediateDirectories: true)
            case "link":
                guard let target = entry.target else { continue }
                if (try? files.destinationOfSymbolicLink(atPath: url.path)) == target {
                    continue
                }
                try? files.removeItem(at: url)
                try files.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
                try files.createSymbolicLink(atPath: url.path, withDestinationPath: target)
            default:
                continue
            }
        }
    }

    /// Puts the managed mods in the mods folder, replacing older copies, and takes out any the
    /// launcher put there before but no longer installs. Mods the player added are left alone.
    func installMods(_ mods: [String: URL], into folder: URL) throws {
        let files = FileManager.default
        try files.createDirectory(at: folder, withIntermediateDirectories: true)
        for name in Self.managedMods(in: folder) where mods[name] == nil {
            try? files.removeItem(at: folder.appending(path: name))
        }
        for (name, source) in mods {
            let destination = folder.appending(path: name)
            if files.contentsEqual(atPath: source.path, andPath: destination.path) {
                continue
            }
            try? files.removeItem(at: destination)
            try files.copyItem(at: source, to: destination)
        }
        try mods.keys.sorted().joined(separator: "\n").write(to: Self.ledger(in: folder), atomically: true, encoding: .utf8)
    }

    /// The mods in a folder that the launcher put there.
    static func managedMods(in folder: URL) -> [String] {
        (try? String(contentsOf: ledger(in: folder), encoding: .utf8))?
            .split(whereSeparator: \.isNewline).map(String.init) ?? []
    }

    private static func ledger(in folder: URL) -> URL {
        folder.appending(path: ".orchard-managed")
    }
}
