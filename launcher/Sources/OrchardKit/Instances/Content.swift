import Foundation

public enum ContentKind: String, CaseIterable, Codable, Identifiable, Sendable {
    case mod, resourcePack, shader

    public var id: Self { self }

    public var folder: String {
        switch self {
        case .mod: "mods"
        case .resourcePack: "resourcepacks"
        case .shader: "shaderpacks"
        }
    }

    public var projectType: String {
        switch self {
        case .mod: "mod"
        case .resourcePack: "resourcepack"
        case .shader: "shader"
        }
    }

    public var loaders: [String] {
        switch self {
        case .mod: ["fabric"]
        case .resourcePack: ["minecraft"]
        case .shader: ["iris"]
        }
    }

    public var title: String {
        switch self {
        case .mod: "Mods"
        case .resourcePack: "Resource Packs"
        case .shader: "Shaders"
        }
    }

    public init?(projectType: String) {
        guard let kind = Self.allCases.first(where: { $0.projectType == projectType }) else { return nil }
        self = kind
    }
}

public struct ContentItem: Identifiable, Hashable, Sendable {
    public var id: String { file.lastPathComponent }
    public let kind: ContentKind
    public let file: URL
    public var enabled: Bool
    public var managed: Bool
    public var name: String
    public var version: String?
    public var authors: [String]
    public var summary: String?
    public var icon: Data?

    public var fileName: String {
        let name = file.lastPathComponent
        return name.hasSuffix(InstanceContent.disabledSuffix) ? String(name.dropLast(InstanceContent.disabledSuffix.count)) : name
    }
}

public struct InstanceContent: Sendable {

    static let disabledSuffix = ".disabled"

    public let directory: URL

    public init(directory: URL) {
        self.directory = directory
    }

    public func folder(_ kind: ContentKind) -> URL {
        directory.appending(path: kind.folder, directoryHint: .isDirectory)
    }

    static let needMojangRenderer: Set<String> = ["sodium", "iris"]

    public var needsMojangRenderer: Bool {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: folder(.mod).path)) ?? []
        return names.contains { name in
            guard name.hasSuffix(".jar"),
                  let meta = try? ZipArchive(url: folder(.mod).appending(path: name)).read("fabric.mod.json"),
                  let json = try? JSONSerialization.jsonObject(with: meta, options: .json5Allowed) as? [String: Any],
                  let id = json["id"] as? String
            else { return false }
            return Self.needMojangRenderer.contains(id)
        }
    }

    var optionsFile: URL { directory.appending(path: "options.txt") }
    var irisFile: URL { directory.appending(path: "config/iris.properties") }

    public var managedMods: Set<String> {
        let ledger = folder(.mod).appending(path: ".orchard-managed")
        return Set((try? String(contentsOf: ledger, encoding: .utf8))?
            .split(whereSeparator: \.isNewline).map(String.init) ?? [])
    }

    public func items(_ kind: ContentKind) -> [ContentItem] {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: folder(kind).path)) ?? []
        let items: [ContentItem] = switch kind {
        case .mod: mods(names)
        case .resourcePack: resourcePacks(names)
        case .shader: shaderPacks(names)
        }
        return items.sorted { a, b in
            if a.managed != b.managed { return a.managed }
            return a.name.localizedStandardCompare(b.name) == .orderedAscending
        }
    }

    private func mods(_ names: [String]) -> [ContentItem] {
        let managed = managedMods
        return names.compactMap { name in
            let enabled = name.hasSuffix(".jar")
            guard enabled || name.hasSuffix(".jar" + Self.disabledSuffix) else { return nil }
            let file = folder(.mod).appending(path: name)
            var item = ContentItem(kind: .mod, file: file, enabled: enabled, managed: managed.contains(name),
                                   name: Self.displayName(name), authors: [])
            if let jar = try? ZipArchive(url: file) {
                Self.describeMod(&item, from: jar)
            }
            return item
        }
    }

    private func resourcePacks(_ names: [String]) -> [ContentItem] {
        let active = Set(Self.resourcePacks(inOptions: (try? String(contentsOf: optionsFile, encoding: .utf8)) ?? "") ?? [])
        return names.compactMap { name in
            let file = folder(.resourcePack).appending(path: name)
            guard name.hasSuffix(".zip") || Self.isDirectory(file), !name.hasPrefix(".") else { return nil }
            var item = ContentItem(kind: .resourcePack, file: file, enabled: active.contains("file/" + name),
                                   managed: false, name: Self.displayName(name), authors: [])
            if let pack = try? ZipArchive(url: file) {
                if let meta = try? pack.read("pack.mcmeta"),
                   let json = try? JSONSerialization.jsonObject(with: meta, options: .json5Allowed) as? [String: Any],
                   let pack = json["pack"] as? [String: Any] {
                    item.summary = Self.plainText(pack["description"]).nilIfBlank
                }
                item.icon = try? pack.read("pack.png")
            }
            return item
        }
    }

    private func shaderPacks(_ names: [String]) -> [ContentItem] {
        let iris = Self.properties((try? String(contentsOf: irisFile, encoding: .utf8)) ?? "")
        let shadersOn = iris["enableShaders"] != "false"
        return names.compactMap { name in
            let file = folder(.shader).appending(path: name)
            guard name.hasSuffix(".zip") || Self.isDirectory(file), !name.hasPrefix(".") else { return nil }
            return ContentItem(kind: .shader, file: file, enabled: shadersOn && iris["shaderPack"] == name,
                               managed: false, name: Self.displayName(name), authors: [])
        }
    }

    public func setEnabled(_ item: ContentItem, _ on: Bool) throws {
        switch item.kind {
        case .mod:
            guard !item.managed, item.enabled != on else { return }
            let name = item.file.lastPathComponent
            let renamed = on ? String(name.dropLast(Self.disabledSuffix.count)) : name + Self.disabledSuffix
            try FileManager.default.moveItem(at: item.file, to: item.file.deletingLastPathComponent().appending(path: renamed))
        case .resourcePack:
            let text = (try? String(contentsOf: optionsFile, encoding: .utf8)) ?? ""
            var packs = Self.resourcePacks(inOptions: text) ?? ["vanilla"]
            let entry = "file/" + item.file.lastPathComponent
            packs.removeAll { $0 == entry }
            if on {
                packs.append(entry)
            }
            try Self.settingResourcePacks(packs, inOptions: text).write(to: optionsFile, atomically: true, encoding: .utf8)
        case .shader:
            try setIris(on ? ["shaderPack": item.file.lastPathComponent, "enableShaders": "true"]
                           : ["enableShaders": "false"])
        }
    }

    public func remove(_ item: ContentItem) throws {
        guard !item.managed else { return }
        if item.kind == .resourcePack, item.enabled {
            try? setEnabled(item, false)
        }
        try FileManager.default.trashItem(at: item.file, resultingItemURL: nil)
    }

    @discardableResult
    public func add(_ files: [URL], as kind: ContentKind) throws -> [URL] {
        let destination = folder(kind)
        try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)
        return try files.map { file in
            let target = destination.appending(path: file.lastPathComponent)
            if FileManager.default.fileExists(atPath: target.path) {
                try FileManager.default.removeItem(at: target)
            }
            try FileManager.default.copyItem(at: file, to: target)
            return target
        }
    }

    public func use(_ file: URL, as kind: ContentKind) throws {
        guard kind != .mod else { return }
        let item = ContentItem(kind: kind, file: file, enabled: false, managed: false,
                               name: file.lastPathComponent, authors: [])
        try setEnabled(item, true)
    }

    func setIris(_ changes: [String: String]) throws {
        try FileManager.default.createDirectory(at: irisFile.deletingLastPathComponent(), withIntermediateDirectories: true)
        let text = (try? String(contentsOf: irisFile, encoding: .utf8)) ?? ""
        try Self.settingProperties(changes, in: text).write(to: irisFile, atomically: true, encoding: .utf8)
    }

    public func worlds() -> [World] {
        let saves = directory.appending(path: "saves", directoryHint: .isDirectory)
        let names = (try? FileManager.default.contentsOfDirectory(atPath: saves.path)) ?? []
        return names.compactMap { name -> World? in
            let folder = saves.appending(path: name, directoryHint: .isDirectory)
            guard let data = try? Data(contentsOf: folder.appending(path: "level.dat")) else { return nil }
            let level = (try? NBT.readGzipped(data))?["Data"]
            let icon = folder.appending(path: "icon.png")
            let hardcore = level?["difficulty_settings"]?["hardcore"]?.integer ?? level?["hardcore"]?.integer
            return World(
                folder: name,
                name: level?["LevelName"]?.string?.nilIfBlank ?? name,
                mode: (level?["GameType"]?.integer).flatMap { GameMode(rawValue: Int($0)) },
                hardcore: hardcore == 1,
                lastPlayed: (level?["LastPlayed"]?.integer).map { Date(timeIntervalSince1970: Double($0) / 1000) },
                icon: FileManager.default.fileExists(atPath: icon.path) ? icon : nil)
        }
        .sorted { ($0.lastPlayed ?? .distantPast) > ($1.lastPlayed ?? .distantPast) }
    }

    public func servers() -> [Server] {
        guard let data = try? Data(contentsOf: directory.appending(path: "servers.dat")),
              let list = (try? NBT.read(data))?["servers"]?.list else { return [] }
        return list.compactMap { entry in
            guard let address = entry["ip"]?.string?.nilIfBlank, entry["hidden"]?.integer != 1 else { return nil }
            return Server(name: entry["name"]?.string?.nilIfBlank ?? address, address: address,
                          icon: entry["icon"]?.string.flatMap { Data(base64Encoded: $0) })
        }
    }

    public func screenshots() -> [URL] {
        let folder = directory.appending(path: "screenshots", directoryHint: .isDirectory)
        let files = (try? FileManager.default.contentsOfDirectory(
            at: folder, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
        func date(_ url: URL) -> Date {
            (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
        }
        return files.filter { $0.pathExtension.lowercased() == "png" }.sorted { date($0) > date($1) }
    }

    static func displayName(_ file: String) -> String {
        var name = file
        for suffix in [disabledSuffix, ".jar", ".zip"] where name.hasSuffix(suffix) {
            name = String(name.dropLast(suffix.count))
        }
        return name
    }

    static func describeMod(_ item: inout ContentItem, from jar: ZipArchive) {
        guard let data = try? jar.read("fabric.mod.json"),
              let json = try? JSONSerialization.jsonObject(with: data, options: .json5Allowed) as? [String: Any]
        else { return }
        if let name = (json["name"] as? String)?.nilIfBlank {
            item.name = name
        } else if let id = json["id"] as? String {
            item.name = id
        }
        item.version = json["version"] as? String
        item.summary = (json["description"] as? String)?.nilIfBlank
        item.authors = (json["authors"] as? [Any] ?? []).compactMap { author in
            (author as? String) ?? ((author as? [String: Any])?["name"] as? String)
        }
        var iconPath = json["icon"] as? String
        if let sizes = json["icon"] as? [String: String] {
            let ranked = sizes.compactMap { key, path in Int(key).map { ($0, path) } }.sorted { $0.0 < $1.0 }
            iconPath = (ranked.last(where: { $0.0 <= 128 }) ?? ranked.first)?.1
        }
        if let iconPath {
            item.icon = try? jar.read(iconPath.hasPrefix("/") ? String(iconPath.dropFirst()) : iconPath)
        }
    }

    static func plainText(_ component: Any?) -> String {
        switch component {
        case let text as String:
            text
        case let list as [Any]:
            list.map(plainText).joined()
        case let object as [String: Any]:
            plainText(object["text"]) + plainText(object["extra"])
        default:
            ""
        }
    }

    static func resourcePacks(inOptions text: String) -> [String]? {
        for line in text.split(whereSeparator: \.isNewline) where line.hasPrefix("resourcePacks:") {
            let value = line.dropFirst("resourcePacks:".count)
            return (try? JSONSerialization.jsonObject(with: Data(value.utf8))) as? [String]
        }
        return nil
    }

    static func settingResourcePacks(_ packs: [String], inOptions text: String) -> String {
        let encoded = String(decoding: (try? JSONSerialization.data(withJSONObject: packs, options: .withoutEscapingSlashes)) ?? Data("[]".utf8), as: UTF8.self)
        var lines = text.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        if let index = lines.firstIndex(where: { $0.hasPrefix("resourcePacks:") }) {
            lines[index] = "resourcePacks:" + encoded
        } else {
            if lines.last == "" { lines.removeLast() }
            lines.append("resourcePacks:" + encoded)
            lines.append("")
        }
        return lines.joined(separator: "\n")
    }

    static func properties(_ text: String) -> [String: String] {
        var values: [String: String] = [:]
        for line in text.split(whereSeparator: \.isNewline) where !line.hasPrefix("#") {
            guard let equals = line.firstIndex(of: "=") else { continue }
            values[line[..<equals].trimmingCharacters(in: .whitespaces)] =
                line[line.index(after: equals)...].trimmingCharacters(in: .whitespaces)
        }
        return values
    }

    static func settingProperties(_ changes: [String: String], in text: String) -> String {
        var remaining = changes
        var lines = text.split(whereSeparator: \.isNewline).map(String.init).map { line -> String in
            guard !line.hasPrefix("#"), let equals = line.firstIndex(of: "=") else { return line }
            let key = line[..<equals].trimmingCharacters(in: .whitespaces)
            guard let value = remaining.removeValue(forKey: key) else { return line }
            return "\(key)=\(value)"
        }
        lines += remaining.keys.sorted().map { "\($0)=\(remaining[$0]!)" }
        return lines.joined(separator: "\n") + "\n"
    }

    private static func isDirectory(_ url: URL) -> Bool {
        (try? url.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true
    }
}

public struct World: Identifiable, Hashable, Sendable {
    public var id: String { folder }
    public var folder: String
    public var name: String
    public var mode: GameMode?
    public var hardcore: Bool
    public var lastPlayed: Date?
    public var icon: URL?
}

public enum GameMode: Int, Sendable {
    case survival, creative, adventure, spectator

    public var label: String {
        switch self {
        case .survival: "Survival"
        case .creative: "Creative"
        case .adventure: "Adventure"
        case .spectator: "Spectator"
        }
    }
}

public struct Server: Identifiable, Hashable, Sendable {
    public var id: String { address }
    public var name: String
    public var address: String
    public var icon: Data?
}

extension String {
    var nilIfBlank: String? {
        trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : self
    }
}
