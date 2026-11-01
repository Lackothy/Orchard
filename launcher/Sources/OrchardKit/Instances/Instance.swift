import Foundation

/// A game directory of its own: its own mods, packs, worlds and options, on the shared game,
/// libraries and assets. Every instance runs Orchard; what else is in it is the player's.
public struct Instance: Codable, Identifiable, Hashable, Sendable {

    /// The first instance, which every install has and which cannot be deleted.
    public static let defaultID = "default"

    /// Its folder's name under `instances/`.
    public var id: String
    public var name: String
    public var icon: InstanceIcon
    public var created: Date
    public var lastPlayed: Date?
    /// Seconds played, in total.
    public var playTime: TimeInterval
    /// The Minecraft it last ran. Nil from before Orchard kept track, when every instance ran
    /// `Edition.firstMinecraft`.
    public var minecraft: String?
    public var renderer: InstanceRenderer
    /// Whether the performance set has been put in, so it goes in once (`SpeedUps`).
    public var speedUps: Bool
    /// Whether the Mac fixes have been put in (`Recommended.macFixes`), once, the same way. Their
    /// own record, so instances that had the performance set before them still get them.
    public var macFixes: Bool

    public init(id: String, name: String, icon: InstanceIcon, created: Date = .now,
                lastPlayed: Date? = nil, playTime: TimeInterval = 0, minecraft: String? = nil,
                renderer: InstanceRenderer = .metal, speedUps: Bool = false, macFixes: Bool = false) {
        self.id = id
        self.name = name
        self.icon = icon
        self.created = created
        self.lastPlayed = lastPlayed
        self.playTime = playTime
        self.minecraft = minecraft
        self.renderer = renderer
        self.speedUps = speedUps
        self.macFixes = macFixes
    }

    /// Written by hand so a file from before `renderer`, `speedUps` or `macFixes` still reads, rather than losing its name.
    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        name = try values.decode(String.self, forKey: .name)
        icon = try values.decode(InstanceIcon.self, forKey: .icon)
        created = try values.decode(Date.self, forKey: .created)
        lastPlayed = try values.decodeIfPresent(Date.self, forKey: .lastPlayed)
        playTime = try values.decode(TimeInterval.self, forKey: .playTime)
        minecraft = try values.decodeIfPresent(String.self, forKey: .minecraft)
        renderer = try values.decodeIfPresent(InstanceRenderer.self, forKey: .renderer) ?? .metal
        speedUps = try values.decodeIfPresent(Bool.self, forKey: .speedUps) ?? false
        macFixes = try values.decodeIfPresent(Bool.self, forKey: .macFixes) ?? false
    }

    public var isDefault: Bool { id == Self.defaultID }
}

/// What draws the game in an instance: Orchard's Metal renderer, or Mojang's own for mods that
/// call OpenGL directly and for Sodium and Iris, which can't run beside Metal.
public enum InstanceRenderer: String, Codable, CaseIterable, Identifiable, Sendable {
    case metal, mojang

    public var id: Self { self }
}

/// The small pixel pictures an instance can wear in the sidebar.
public enum InstanceIcon: String, Codable, CaseIterable, Sendable {
    case tree, sun, pearl, grass, diamond, heart, moon, sword
}

/// Instances on disk: one folder each under `instances/`, described by `orchard-instance.json`
/// inside it. A folder without one (the original `default`, or one copied in by hand) still counts.
public struct InstanceStore: Sendable {

    public static let metadataFile = "orchard-instance.json"

    public let paths: Paths

    public init(paths: Paths) {
        self.paths = paths
    }

    public func directory(of instance: Instance) -> URL {
        paths.instance(instance.id)
    }

    /// Every instance, the default first, then oldest to newest. Makes the default if it is missing.
    public func load() -> [Instance] {
        let files = FileManager.default
        try? files.createDirectory(at: paths.instance(Instance.defaultID), withIntermediateDirectories: true)
        let folders = (try? files.contentsOfDirectory(at: paths.instances, includingPropertiesForKeys: [.isDirectoryKey, .creationDateKey]))
            ?? []
        var instances: [Instance] = []
        for folder in folders where (try? folder.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true {
            let id = folder.lastPathComponent
            guard !id.hasPrefix(".") else { continue }
            if let data = try? Data(contentsOf: folder.appending(path: Self.metadataFile)),
               var instance = try? Self.decoder.decode(Instance.self, from: data) {
                instance.id = id
                instances.append(instance)
            } else {
                let created = (try? folder.resourceValues(forKeys: [.creationDateKey]).creationDate) ?? .now
                instances.append(id == Instance.defaultID
                    ? Instance(id: id, name: "Orchard", icon: .tree, created: created)
                    : Instance(id: id, name: id, icon: .grass, created: created))
            }
        }
        return instances.sorted { a, b in
            if a.isDefault != b.isDefault { return a.isDefault }
            return a.created < b.created
        }
    }

    public func save(_ instance: Instance) throws {
        let folder = directory(of: instance)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try Self.encoder.encode(instance).write(to: folder.appending(path: Self.metadataFile), options: .atomic)
    }

    /// A new instance, empty or a copy of `source`'s folder (worlds, options, mods and all).
    public func create(name: String, icon: InstanceIcon, minecraft: String? = nil, copying source: Instance? = nil) throws -> Instance {
        let taken = Set(((try? FileManager.default.contentsOfDirectory(atPath: paths.instances.path)) ?? [])
            .map { $0.lowercased() })
        var instance = Instance(id: Self.folderName(for: name, taken: taken), name: name, icon: icon, minecraft: minecraft)
        if let source {
            instance.minecraft = source.minecraft
            instance.renderer = source.renderer
            instance.speedUps = source.speedUps
            instance.macFixes = source.macFixes
            try FileManager.default.copyItem(at: directory(of: source), to: directory(of: instance))
        }
        try save(instance)
        return instance
    }

    /// Moves the instance's folder to the Trash, so a mistake can be undone from the Finder.
    public func delete(_ instance: Instance) throws {
        guard !instance.isDefault else { return }
        try FileManager.default.trashItem(at: directory(of: instance), resultingItemURL: nil)
    }

    /// A readable folder name from the instance's name: lower case, hyphens for anything else,
    /// numbered when taken.
    static func folderName(for name: String, taken: Set<String>) -> String {
        var slug = ""
        for scalar in name.lowercased().unicodeScalars {
            if scalar.isASCII, CharacterSet.alphanumerics.contains(scalar) {
                slug.unicodeScalars.append(scalar)
            } else if !slug.isEmpty, !slug.hasSuffix("-") {
                slug += "-"
            }
        }
        while slug.hasSuffix("-") { slug.removeLast() }
        if slug.isEmpty || slug == Instance.defaultID { slug = "instance" }
        var candidate = slug
        var number = 2
        while taken.contains(candidate) {
            candidate = "\(slug)-\(number)"
            number += 1
        }
        return candidate
    }

    private static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return encoder
    }()

    private static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }()
}
