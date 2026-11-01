import Foundation

/// What the launcher installs: the Minecraft, Fabric Loader and Fabric API versions the Orchard
/// mod was built against, and the mod itself, Metal renderer and all.
///
/// The versions come from the mod's `gradle.properties`, so they are only ever written down once.
/// Scripts/build-app.sh copies them into the app as `edition.json` beside the mod's jar; run
/// from the package instead, the launcher reads them from the repository.
public struct Edition: Codable, Sendable, Equatable {

    public var minecraft: String
    public var loader: String
    public var fabricAPI: String
    /// The Orchard mod's jar. Nil when it has not been built yet.
    public var modJar: URL?

    /// The Minecraft instances ran before Orchard recorded which one each last ran.
    public static let firstMinecraft = "26.1.2"

    public init(minecraft: String, loader: String, fabricAPI: String, modJar: URL?) {
        self.minecraft = minecraft
        self.loader = loader
        self.fabricAPI = fabricAPI
        self.modJar = modJar
    }

    enum CodingKeys: String, CodingKey {
        case minecraft, loader, fabricAPI = "fabricApi", modJar
    }

    /// The app bundle's edition, or the repository's when run with `swift run`.
    public static func current(bundle: Bundle = .main) throws -> Edition {
        if let url = bundle.url(forResource: "edition", withExtension: "json") {
            var edition = try JSONDecoder().decode(Edition.self, from: Data(contentsOf: url))
            edition.modJar = bundle.url(forResource: "orchard", withExtension: "jar")
            return edition
        }
        // launcher/Sources/OrchardKit/Edition.swift -> the repository root.
        let repository = URL(filePath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
        return try fromRepository(repository)
    }

    /// Reads `gradle.properties` and picks the newest jar in `jars/`.
    public static func fromRepository(_ repository: URL) throws -> Edition {
        let text = try String(contentsOf: repository.appending(path: "gradle.properties"), encoding: .utf8)
        let properties = parseProperties(text)
        func value(_ key: String) throws -> String {
            guard let value = properties[key], !value.isEmpty else {
                throw LauncherError.missingEdition(key)
            }
            return value
        }
        return Edition(
            minecraft: try value("minecraftVersion"),
            loader: try value("loaderVersion"),
            fabricAPI: try value("fabricApiVersion"),
            modJar: newestJar(in: repository.appending(path: "jars", directoryHint: .isDirectory)))
    }

    static func parseProperties(_ text: String) -> [String: String] {
        var out: [String: String] = [:]
        for line in text.split(whereSeparator: \.isNewline) {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            guard !trimmed.hasPrefix("#"), let equals = trimmed.firstIndex(of: "=") else {
                continue
            }
            let key = trimmed[..<equals].trimmingCharacters(in: .whitespaces)
            out[key] = trimmed[trimmed.index(after: equals)...].trimmingCharacters(in: .whitespaces)
        }
        return out
    }

    private static func newestJar(in folder: URL) -> URL? {
        let files = (try? FileManager.default.contentsOfDirectory(
            at: folder, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
        return files
            .filter { $0.pathExtension == "jar" }
            .max { modified($0) < modified($1) }
    }

    private static func modified(_ url: URL) -> Date {
        (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
    }
}
