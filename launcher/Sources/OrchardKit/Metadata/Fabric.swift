import Foundation

/// Fabric Loader's launcher profile: the libraries and main class that start Minecraft with mods.
public struct FabricProfile: Codable, Sendable {
    public struct Arguments: Codable, Sendable {
        public var game: [String]
        public var jvm: [String]
    }

    public var id: String
    public var inheritsFrom: String
    public var mainClass: String
    public var arguments: Arguments
    public var libraries: [Library]

    public static func url(minecraft: String, loader: String) -> URL {
        URL(string: "https://meta.fabricmc.net/v2/versions/loader/\(minecraft)/\(loader)/profile/json")!
    }
}

enum FabricMaven {
    static let repository = URL(string: "https://maven.fabricmc.net/")!

    static func fabricAPI(_ version: String) -> URL {
        repository.appending(path: Maven.path(of: "net.fabricmc.fabric-api:fabric-api:\(version)")!)
    }
}

/// Maven coordinates, `group:artifact:version[:classifier]`, as repository paths.
public enum Maven {
    public static func path(of coordinate: String) -> String? {
        let parts = coordinate.split(separator: ":").map(String.init)
        guard parts.count >= 3 else {
            return nil
        }
        let group = parts[0].replacingOccurrences(of: ".", with: "/")
        let artifact = parts[1]
        let version = parts[2]
        let classifier = parts.count > 3 ? "-" + parts[3] : ""
        return "\(group)/\(artifact)/\(version)/\(artifact)-\(version)\(classifier).jar"
    }
}
