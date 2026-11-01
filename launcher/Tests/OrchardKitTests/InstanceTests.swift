import Compression
import Foundation
import Testing
@testable import OrchardKit

/// Builds the small binary files the readers take, so tests need no fixtures.
private enum Fixture {

    static func deflate(_ data: Data) -> Data {
        var out = Data(count: data.count + 1024)
        let written = out.withUnsafeMutableBytes { destination in
            data.withUnsafeBytes { source in
                compression_encode_buffer(destination.bindMemory(to: UInt8.self).baseAddress!, destination.count,
                                          source.bindMemory(to: UInt8.self).baseAddress!, data.count, nil, COMPRESSION_ZLIB)
            }
        }
        return out.prefix(written)
    }

    static func gzip(_ data: Data) -> Data {
        var out = Data([0x1F, 0x8B, 8, 0x08, 0, 0, 0, 0, 0, 3])
        out += Data("level.dat".utf8) + Data([0])
        out += deflate(data)
        out += Data([0, 0, 0, 0]) + le32(data.count)
        return out
    }

    /// A zip with each file deflated.
    static func zip(_ files: [String: Data]) -> Data {
        var body = Data()
        var directory = Data()
        for (name, contents) in files.sorted(by: { $0.key < $1.key }) {
            let packed = deflate(contents)
            let offset = body.count
            let sizes: [Data] = [le32(0), le32(packed.count), le32(contents.count), le16(name.utf8.count), le16(0)]
            let local: [Data] = [le32(0x0403_4B50), le16(20), le16(0), le16(8), le32(0)] + sizes
            body += join(local + [Data(name.utf8), packed])
            let central: [Data] = [le32(0x0201_4B50), le16(20), le16(20), le16(0), le16(8), le32(0)] + sizes
            let rest: [Data] = [le16(0), le16(0), le16(0), le32(0), le32(offset), Data(name.utf8)]
            directory += join(central + rest)
        }
        let end: [Data] = [le32(0x0605_4B50), le16(0), le16(0), le16(files.count), le16(files.count),
                           le32(directory.count), le32(body.count), le16(0)]
        return body + directory + join(end)
    }

    static func join(_ parts: [Data]) -> Data {
        parts.reduce(into: Data()) { $0.append($1) }
    }

    enum Tag {
        case byte(Int8), int(Int32), long(Int64), string(String), compound([(String, Tag)]), list([Tag])
    }

    static func nbt(_ root: [(String, Tag)]) -> Data {
        Data([10]) + string("") + payload(.compound(root))
    }

    private static func type(_ tag: Tag) -> UInt8 {
        switch tag {
        case .byte: 1
        case .int: 3
        case .long: 4
        case .string: 8
        case .list: 9
        case .compound: 10
        }
    }

    private static func payload(_ tag: Tag) -> Data {
        switch tag {
        case .byte(let v): return Data([UInt8(bitPattern: v)])
        case .int(let v): return be(UInt64(UInt32(bitPattern: v)), 4)
        case .long(let v): return be(UInt64(bitPattern: v), 8)
        case .string(let v): return string(v)
        case .list(let values):
            return Data([values.first.map(type) ?? 0]) + be(UInt64(values.count), 4) + values.map(payload).reduce(Data(), +)
        case .compound(let entries):
            return entries.map { Data([type($0.1)]) + string($0.0) + payload($0.1) }.reduce(Data(), +) + Data([0])
        }
    }

    private static func string(_ text: String) -> Data {
        be(UInt64(text.utf8.count), 2) + Data(text.utf8)
    }

    private static func be(_ value: UInt64, _ count: Int) -> Data {
        Data((0..<count).reversed().map { UInt8(truncatingIfNeeded: value >> (8 * UInt64($0))) })
    }

    private static func le16(_ value: Int) -> Data { Data([UInt8(value & 0xFF), UInt8(value >> 8 & 0xFF)]) }
    private static func le32(_ value: Int) -> Data { le16(value & 0xFFFF) + le16(value >> 16 & 0xFFFF) }

    static func temporaryFolder() throws -> URL {
        let folder = FileManager.default.temporaryDirectory.appending(path: "orchard-test-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }
}

@Suite struct FormatTests {

    @Test func readsNBT() throws {
        let data = Fixture.nbt([("Data", .compound([
            ("LevelName", .string("Apple Valley")),
            ("LastPlayed", .long(1_793_356_204_223)),
            ("GameType", .int(1)),
            ("servers", .list([.compound([("ip", .string("localhost"))])])),
        ]))])
        let root = try NBT.read(data)
        #expect(root["Data"]?["LevelName"]?.string == "Apple Valley")
        #expect(root["Data"]?["LastPlayed"]?.integer == 1_793_356_204_223)
        #expect(root["Data"]?["GameType"]?.integer == 1)
        #expect(root["Data"]?["servers"]?.list?.first?["ip"]?.string == "localhost")
    }

    @Test func rejectsTruncatedNBT() {
        let data = Fixture.nbt([("LevelName", .string("Apple Valley"))])
        #expect(throws: (any Error).self) { try NBT.read(data.prefix(data.count - 6)) }
    }

    @Test func gunzips() throws {
        let text = Data(String(repeating: "orchard ", count: 200).utf8)
        #expect(try Inflate.gunzip(Fixture.gzip(text)) == text)
    }

    @Test func readsZipEntries() throws {
        let zip = try ZipArchive(data: Fixture.zip(["a.txt": Data("apple".utf8), "dir/b.txt": Data("blossom".utf8)]))
        #expect(try zip.read("dir/b.txt") == Data("blossom".utf8))
        #expect(try zip.read("a.txt") == Data("apple".utf8))
        #expect(try zip.read("missing") == nil)
    }
}

@Suite struct InstanceContentTests {

    @Test func describesModsFromTheirJar() throws {
        let folder = try Fixture.temporaryFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        let content = InstanceContent(directory: folder)
        let mods = content.folder(.mod)
        try FileManager.default.createDirectory(at: mods, withIntermediateDirectories: true)
        let json = #"{"id":"sodium","name":"Sodium","version":"0.9.2","description":"Faster","authors":["jellysquid3",{"name":"IMS"}],"icon":{"32":"a.png","256":"b.png"}}"#
        try Fixture.zip(["fabric.mod.json": Data(json.utf8), "a.png": Data("small".utf8), "b.png": Data("big".utf8)])
            .write(to: mods.appending(path: "sodium-fabric.jar"))
        try Data("not a zip".utf8).write(to: mods.appending(path: "broken.jar.disabled"))
        try Data().write(to: mods.appending(path: "notes.txt"))

        let items = content.items(.mod)
        #expect(items.map(\.name) == ["broken", "Sodium"])
        let sodium = try #require(items.last)
        #expect(sodium.enabled && sodium.version == "0.9.2" && sodium.authors == ["jellysquid3", "IMS"])
        #expect(sodium.icon == Data("small".utf8))
        #expect(items.first?.enabled == false)
    }

    @Test func turnsModsOffByRenaming() throws {
        let folder = try Fixture.temporaryFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        let content = InstanceContent(directory: folder)
        try FileManager.default.createDirectory(at: content.folder(.mod), withIntermediateDirectories: true)
        try Data().write(to: content.folder(.mod).appending(path: "lithium.jar"))
        try content.setEnabled(try #require(content.items(.mod).first), false)
        #expect(try FileManager.default.contentsOfDirectory(atPath: content.folder(.mod).path) == ["lithium.jar.disabled"])
        try content.setEnabled(try #require(content.items(.mod).first), true)
        #expect(try FileManager.default.contentsOfDirectory(atPath: content.folder(.mod).path) == ["lithium.jar"])
    }

    @Test func sodiumOrIrisNeedMojangsRenderer() throws {
        let folder = try Fixture.temporaryFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        let content = InstanceContent(directory: folder)
        let mods = content.folder(.mod)
        try FileManager.default.createDirectory(at: mods, withIntermediateDirectories: true)
        try Fixture.zip(["fabric.mod.json": Data(#"{"id":"lithium"}"#.utf8)]).write(to: mods.appending(path: "lithium.jar"))
        try Fixture.zip(["fabric.mod.json": Data(#"{"id":"iris"}"#.utf8)]).write(to: mods.appending(path: "iris.jar.disabled"))
        #expect(!content.needsMojangRenderer)
        try Fixture.zip(["fabric.mod.json": Data(#"{"id":"sodium"}"#.utf8)]).write(to: mods.appending(path: "sodium.jar"))
        #expect(content.needsMojangRenderer)
    }

    @Test func editsResourcePacksInOptions() {
        let options = "version:4790\nresourcePacks:[\"vanilla\",\"fabric\"]\nfov:0.0\n"
        #expect(InstanceContent.resourcePacks(inOptions: options) == ["vanilla", "fabric"])
        let changed = InstanceContent.settingResourcePacks(["vanilla", "file/Fresh Animations.zip"], inOptions: options)
        #expect(changed == "version:4790\nresourcePacks:[\"vanilla\",\"file/Fresh Animations.zip\"]\nfov:0.0\n")
        #expect(InstanceContent.settingResourcePacks(["vanilla"], inOptions: "") == "resourcePacks:[\"vanilla\"]\n")
    }

    @Test func editsIrisProperties() {
        let text = "#This file stores configuration options for Iris\nenableShaders=false\nmaxShadowRenderDistance=32\n"
        let changed = InstanceContent.settingProperties(["enableShaders": "true", "shaderPack": "BSL.zip"], in: text)
        #expect(changed == "#This file stores configuration options for Iris\nenableShaders=true\nmaxShadowRenderDistance=32\nshaderPack=BSL.zip\n")
        #expect(InstanceContent.properties(changed)["shaderPack"] == "BSL.zip")
    }

    @Test func readsWorldsAndServers() throws {
        let folder = try Fixture.temporaryFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        let world = folder.appending(path: "saves/New World")
        try FileManager.default.createDirectory(at: world, withIntermediateDirectories: true)
        try Fixture.gzip(Fixture.nbt([("Data", .compound([
            ("LevelName", .string("Apple Valley")), ("LastPlayed", .long(1_700_000_000_000)), ("GameType", .int(1)),
            ("difficulty_settings", .compound([("hardcore", .byte(1))])),
        ]))])).write(to: world.appending(path: "level.dat"))
        try Fixture.nbt([("servers", .list([
            .compound([("name", .string("Test Server")), ("ip", .string("localhost"))]),
            .compound([("ip", .string("hidden.example")), ("hidden", .byte(1))]),
        ]))]).write(to: folder.appending(path: "servers.dat"))

        let content = InstanceContent(directory: folder)
        let worlds = content.worlds()
        #expect(worlds.map(\.name) == ["Apple Valley"])
        #expect(worlds.first?.folder == "New World" && worlds.first?.mode == .creative && worlds.first?.hardcore == true)
        #expect(worlds.first?.lastPlayed == Date(timeIntervalSince1970: 1_700_000_000))
        #expect(content.servers().map(\.name) == ["Test Server"])
    }

    @Test func instanceFolderNames() {
        #expect(InstanceStore.folderName(for: "Survival with Shaders", taken: []) == "survival-with-shaders")
        #expect(InstanceStore.folderName(for: "Speedrun!", taken: ["speedrun"]) == "speedrun-2")
        #expect(InstanceStore.folderName(for: "Default", taken: []) == "instance")
        #expect(InstanceStore.folderName(for: "☆☆", taken: ["instance"]) == "instance-2")
    }

    @Test func storeKeepsTheDefaultFirst() throws {
        let root = try Fixture.temporaryFolder()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = InstanceStore(paths: Paths(root: root))
        let made = try store.create(name: "Speedrun", icon: .pearl)
        let instances = store.load()
        #expect(instances.map(\.id) == ["default", "speedrun"])
        #expect(instances.first?.name == "Orchard" && instances.last?.icon == .pearl && made.id == "speedrun")
    }
}
