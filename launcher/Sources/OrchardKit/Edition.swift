import Foundation

public struct Edition: Codable, Sendable, Equatable {

    public var minecraft: String
    public var loader: String
    public var fabricAPI: String
    public var modJar: URL?

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

    public static func current(bundle: Bundle = .main) throws -> Edition {
        if let url = bundle.url(forResource: "edition", withExtension: "json") {
            var edition = try JSONDecoder().decode(Edition.self, from: Data(contentsOf: url))
            edition.modJar = bundle.url(forResource: "orchard", withExtension: "jar")
            return edition
        }
        #if DEBUG
        let repository = URL(filePath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
        #else
        let repository = URL(filePath: FileManager.default.currentDirectoryPath).deletingLastPathComponent()
        #endif
        return try fromRepository(repository)
    }

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
