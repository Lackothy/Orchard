import Foundation

/// Where Orchard keeps the game: one folder in Application Support, shared by every instance.
///
/// Libraries, assets and Java runtimes are content the launcher downloads and can always fetch
/// again. Instance folders are the player's: worlds, options, screenshots, their own mods.
public struct Paths: Sendable {

    public let root: URL

    public init(root: URL) {
        self.root = root
    }

    /// `~/Library/Application Support/Orchard`.
    public static var standard: Paths {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return Paths(root: support.appending(path: "Orchard", directoryHint: .isDirectory))
    }

    public var libraries: URL { root.appending(path: "libraries", directoryHint: .isDirectory) }
    public var assets: URL { root.appending(path: "assets", directoryHint: .isDirectory) }
    public var versions: URL { root.appending(path: "versions", directoryHint: .isDirectory) }
    public var runtimes: URL { root.appending(path: "runtime", directoryHint: .isDirectory) }
    public var metadata: URL { root.appending(path: "metadata", directoryHint: .isDirectory) }

    /// One folder per instance, each a game directory: saves, options, resource packs, mods.
    public var instances: URL { root.appending(path: "instances", directoryHint: .isDirectory) }

    public func instance(_ id: String) -> URL {
        instances.appending(path: id, directoryHint: .isDirectory)
    }

    public func version(_ id: String) -> URL {
        versions.appending(path: id, directoryHint: .isDirectory)
    }

    public func runtime(_ component: String) -> URL {
        runtimes.appending(path: component, directoryHint: .isDirectory)
    }

    /// LWJGL unpacks its native libraries here; it starts empty.
    public func natives(_ versionID: String) -> URL {
        version(versionID).appending(path: "natives", directoryHint: .isDirectory)
    }
}
