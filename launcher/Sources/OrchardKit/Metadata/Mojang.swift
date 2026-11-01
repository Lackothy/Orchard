import Foundation

/// A file Mojang publishes with its checksum and size.
public struct RemoteFile: Codable, Sendable, Equatable {
    public var sha1: String
    public var size: Int64
    public var url: URL
}

/// `version_manifest_v2.json`: every Minecraft version and where its details are.
public struct VersionManifest: Codable, Sendable {
    public struct Entry: Codable, Sendable {
        public var id: String
        public var type: String
        public var url: URL
        public var sha1: String
    }

    public var versions: [Entry]

    public static let url = URL(string: "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")!
}

/// One argument: a plain string, or values that apply only when their rules allow.
public enum Argument: Codable, Sendable, Equatable {
    case plain(String)
    case conditional(rules: [Rule], values: [String])

    private enum CodingKeys: String, CodingKey {
        case rules, value
    }

    public init(from decoder: Decoder) throws {
        if let single = try? decoder.singleValueContainer().decode(String.self) {
            self = .plain(single)
            return
        }
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let rules = try container.decodeIfPresent([Rule].self, forKey: .rules) ?? []
        if let one = try? container.decode(String.self, forKey: .value) {
            self = .conditional(rules: rules, values: [one])
        } else {
            self = .conditional(rules: rules, values: try container.decode([String].self, forKey: .value))
        }
    }

    public func encode(to encoder: Encoder) throws {
        switch self {
        case .plain(let value):
            var container = encoder.singleValueContainer()
            try container.encode(value)
        case .conditional(let rules, let values):
            var container = encoder.container(keyedBy: CodingKeys.self)
            try container.encode(rules, forKey: .rules)
            try container.encode(values, forKey: .value)
        }
    }

    /// The strings this argument contributes on `platform`.
    public func values(on platform: Platform) -> [String] {
        switch self {
        case .plain(let value):
            [value]
        case .conditional(let rules, let values):
            rules.allows(platform) ? values : []
        }
    }
}

/// A library: a Maven coordinate, and for Mojang's, where to fetch it.
public struct Library: Codable, Sendable, Equatable {
    public struct Downloads: Codable, Sendable, Equatable {
        public struct Artifact: Codable, Sendable, Equatable {
            public var path: String
            public var sha1: String?
            public var size: Int64?
            public var url: URL
        }

        public var artifact: Artifact?
    }

    public var name: String
    public var downloads: Downloads?
    /// Fabric's libraries give a Maven repository instead of a full download.
    public var url: URL?
    public var sha1: String?
    public var size: Int64?
    public var rules: [Rule]?
}

/// `<version>.json`: everything needed to install and start one Minecraft version.
public struct VersionInfo: Codable, Sendable {
    public struct Arguments: Codable, Sendable {
        public var game: [Argument]
        public var jvm: [Argument]
        /// Mojang's default memory and garbage-collector settings, which players may change.
        public var defaultUserJVM: [Argument]?

        enum CodingKeys: String, CodingKey {
            case game, jvm, defaultUserJVM = "default-user-jvm"
        }
    }

    public struct AssetIndexRef: Codable, Sendable {
        public var id: String
        public var sha1: String
        public var size: Int64
        public var totalSize: Int64
        public var url: URL
    }

    public struct Downloads: Codable, Sendable {
        public var client: RemoteFile
    }

    public struct JavaVersion: Codable, Sendable {
        public var component: String
        public var majorVersion: Int
    }

    public struct Logging: Codable, Sendable {
        public struct Config: Codable, Sendable {
            public struct File: Codable, Sendable {
                public var id: String
                public var sha1: String
                public var size: Int64
                public var url: URL
            }

            public var argument: String
            public var file: File
        }

        public var client: Config?
    }

    public var id: String
    public var type: String
    public var mainClass: String
    public var arguments: Arguments
    public var assetIndex: AssetIndexRef
    public var downloads: Downloads
    public var libraries: [Library]
    public var javaVersion: JavaVersion
    public var logging: Logging?
}

/// `assets/indexes/<id>.json`: every sound, language and texture, by content hash.
public struct AssetIndex: Codable, Sendable {
    public struct Object: Codable, Sendable {
        public var hash: String
        public var size: Int64
    }

    public var objects: [String: Object]
}

/// Mojang's Java runtimes, by platform and component.
public struct JavaRuntimeIndex: Codable, Sendable {
    public struct Entry: Codable, Sendable {
        public struct Version: Codable, Sendable {
            public var name: String
        }

        public var manifest: RemoteFile
        public var version: Version
    }

    public var platforms: [String: [String: [Entry]]]

    public init(from decoder: Decoder) throws {
        platforms = try decoder.singleValueContainer().decode([String: [String: [Entry]]].self)
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(platforms)
    }

    public static let url = URL(string:
        "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json")!
}

/// The files of one Java runtime: directories, files and symbolic links.
public struct JavaRuntimeManifest: Codable, Sendable {
    public struct Entry: Codable, Sendable {
        public struct Downloads: Codable, Sendable {
            public var raw: RemoteFile
        }

        public var type: String
        public var executable: Bool?
        public var downloads: Downloads?
        public var target: String?
    }

    public var files: [String: Entry]
}
