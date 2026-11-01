import Foundation
import Testing
@testable import OrchardKit

@Suite struct RulesTests {

    private func rules(_ json: String) throws -> [Rule] {
        try JSONDecoder().decode([Rule].self, from: Data(json.utf8))
    }

    @Test func noRulesAllow() {
        #expect([Rule]().allows(Platform()))
    }

    @Test func osRulesPickTheMac() throws {
        #expect(try rules(#"[{"action":"allow","os":{"name":"osx"}}]"#).allows(Platform()))
        #expect(try !rules(#"[{"action":"allow","os":{"name":"windows"}}]"#).allows(Platform()))
        #expect(try !rules(#"[{"action":"allow","os":{"arch":"x86"}}]"#).allows(Platform()))
    }

    @Test func lastMatchingRuleWins() throws {
        let list = try rules(#"[{"action":"allow"},{"action":"disallow","os":{"name":"osx"}}]"#)
        #expect(!list.allows(Platform()))
    }

    @Test func featuresDefaultOff() throws {
        let demo = try rules(#"[{"action":"allow","features":{"is_demo_user":true}}]"#)
        #expect(!demo.allows(Platform()))
        #expect(demo.allows(Platform(features: ["is_demo_user": true])))
    }
}

@Suite struct ArgumentTests {

    @Test func decodesPlainAndConditional() throws {
        let json = #"["-cp",{"rules":[{"action":"allow","os":{"name":"osx"}}],"value":["-XstartOnFirstThread"]},{"rules":[{"action":"allow","os":{"name":"windows"}}],"value":"-Xwin"}]"#
        let arguments = try JSONDecoder().decode([Argument].self, from: Data(json.utf8))
        #expect(arguments.flatMap { $0.values(on: Platform()) } == ["-cp", "-XstartOnFirstThread"])
    }

    @Test func substitutesAndDropsEmptyFlags() {
        let out = LaunchCommand.substitute(
            ["--username", "${auth_player_name}", "--xuid", "${auth_xuid}", "-Dpath=${path}", "--demo"],
            ["auth_player_name": "Steve", "path": "/tmp/x"])
        #expect(out == ["--username", "Steve", "-Dpath=/tmp/x", "--demo"])
    }

    @Test func quickPlayOpensOnlyWhatWasAskedFor() throws {
        // As 26.1.2's version JSON has them.
        let json = #"""
        [{"rules":[{"action":"allow","features":{"is_quick_play_singleplayer":true}}],"value":["--quickPlaySingleplayer","${quickPlaySingleplayer}"]},
         {"rules":[{"action":"allow","features":{"is_quick_play_multiplayer":true}}],"value":["--quickPlayMultiplayer","${quickPlayMultiplayer}"]}]
        """#
        let arguments = try JSONDecoder().decode([Argument].self, from: Data(json.utf8))
        func command(_ quickPlay: QuickPlay?) -> [String] {
            var platform = Platform()
            var values: [String: String] = [:]
            LaunchCommand.apply(quickPlay, to: &platform, values: &values)
            return LaunchCommand.substitute(arguments.flatMap { $0.values(on: platform) }, values)
        }
        #expect(command(nil) == [])
        #expect(command(.world("New World")) == ["--quickPlaySingleplayer", "New World"])
        #expect(command(.server("play.example.net:25566")) == ["--quickPlayMultiplayer", "play.example.net:25566"])
    }

    @Test func memoryNeverStartsAboveItsCeiling() {
        #expect(LaunchCommand.memoryArguments(4) == ["-Xms2G", "-Xmx4G"])
        #expect(LaunchCommand.memoryArguments(1) == ["-Xms1G", "-Xmx1G"])
    }
}

@Suite struct AccountTests {

    @Test func offlineUUIDMatchesJava() {
        // UUID.nameUUIDFromBytes("OfflinePlayer:Notch".getBytes(UTF_8)) in Java.
        #expect(OfflineAccount(name: "Notch").uuid == "b50ad385829d3141a2167e7d7539ba7f")
    }

    @Test func validatesNames() {
        #expect(OfflineAccount.isValid("Steve_123"))
        #expect(!OfflineAccount.isValid("ab"))
        #expect(!OfflineAccount.isValid("seventeen_letters"))
        #expect(!OfflineAccount.isValid("spaced name"))
        #expect(!OfflineAccount.isValid("Stéve"))
    }
}

@Suite struct EditionTests {

    @Test func mavenPaths() {
        #expect(Maven.path(of: "org.ow2.asm:asm:9.10.1") == "org/ow2/asm/asm/9.10.1/asm-9.10.1.jar")
        #expect(Maven.path(of: "org.lwjgl:lwjgl:3.4.1:natives-macos-arm64")
            == "org/lwjgl/lwjgl/3.4.1/lwjgl-3.4.1-natives-macos-arm64.jar")
        #expect(Maven.path(of: "nonsense") == nil)
    }

    @Test func readsGradleProperties() {
        let properties = Edition.parseProperties("""
            # Fabric toolchain
            minecraftVersion=26.1.2
            loaderVersion = 0.20.1
            fabricApiVersion=0.155.3+26.1.2
            """)
        #expect(properties["minecraftVersion"] == "26.1.2")
        #expect(properties["loaderVersion"] == "0.20.1")
        #expect(properties["fabricApiVersion"] == "0.155.3+26.1.2")
    }

    @Test func repositoryEditionIsComplete() throws {
        let edition = try Edition.current()
        #expect(edition.minecraft.contains("."))
        #expect(!edition.loader.isEmpty)
    }
}

@Suite struct InstallerTests {

    @Test func managedModsAreReplacedAndPlayersModsKept() throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "orchard-test-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let paths = Paths(root: root)
        let installer = Installer(paths: paths, edition: Edition(minecraft: "x", loader: "y", fabricAPI: "z", modJar: nil))
        let source = root.appending(path: "src")
        try FileManager.default.createDirectory(at: source, withIntermediateDirectories: true)
        for name in ["old.jar", "new.jar"] {
            try Data(name.utf8).write(to: source.appending(path: name))
        }
        let mods = paths.instance(Instance.defaultID).appending(path: "mods")
        try installer.installMods(["old-api.jar": source.appending(path: "old.jar")], into: mods)
        try FileManager.default.createDirectory(at: mods, withIntermediateDirectories: true)
        try Data("theirs".utf8).write(to: mods.appending(path: "sodium.jar"))
        try installer.installMods(["new-api.jar": source.appending(path: "new.jar")], into: mods)
        let names = try FileManager.default.contentsOfDirectory(atPath: mods.path).sorted()
        #expect(names == [".orchard-managed", "new-api.jar", "sodium.jar"])
    }

    @Test func theOldRendererJarGoesNowItIsInOrchards() throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "orchard-test-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let installer = Installer(paths: Paths(root: root), edition: Edition(minecraft: "x", loader: "y", fabricAPI: "z", modJar: nil))
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let source = root.appending(path: "orchard.jar")
        try Data("orchard".utf8).write(to: source)
        let mods = root.appending(path: "mods")
        try installer.installMods(["orchard.jar": source, "orchard-renderer.jar": source], into: mods)
        try installer.installMods(["orchard.jar": source], into: mods)
        let names = try FileManager.default.contentsOfDirectory(atPath: mods.path).sorted()
        #expect(names == [".orchard-managed", "orchard.jar"])
    }
}

@Suite struct SceneTimeTests {

    @Test func followingTheClockTellsTheGameNothing() {
        #expect(SceneTime.timeOfDay.hour == nil)
        #expect(SceneTime.timeOfDay.jvmArguments.isEmpty)
    }

    /// The hours `OrchardScene.java` draws for the same names.
    @Test func fixedTimesMatchTheGame() {
        #expect(SceneTime.day.hour == 12)
        #expect(SceneTime.sunset.hour == 18.4)
        #expect(SceneTime.night.hour == 23)
        #expect(SceneTime.sunset.jvmArguments == ["-Dorchard.scene=sunset"])
        #expect(SceneTime(rawValue: "night") == .night)
    }

    @Test func theGameIsToldTheTimeAndWhetherItsCloudsMayMove() {
        var options = LaunchOptions(account: OfflineAccount(name: "Notch"), memory: 4, launcherVersion: "test")
        #expect(options.orchardArguments.isEmpty)
        options.scene = .sunset
        options.reduceMotion = true
        #expect(options.orchardArguments == ["-Dorchard.scene=sunset", "-Dorchard.reduceMotion=true"])
    }

    @Test func theRendererInOrchardsJarIsToldWhenToStayOff() {
        var options = LaunchOptions(account: OfflineAccount(name: "Notch"), memory: 4, launcherVersion: "test")
        #expect(!options.orchardArguments.contains("-Dorchard.renderer=mojang"))
        options.metal = false
        #expect(options.orchardArguments == ["-Dorchard.renderer=mojang"])
    }
}

@Suite struct VersionMoveTests {

    private func folder() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appending(path: "orchard-test-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    @Test func ownModsLeaveOutWhatTheLauncherPutThere() throws {
        let root = try folder()
        defer { try? FileManager.default.removeItem(at: root) }
        let installer = Installer(paths: Paths(root: root), edition: Edition(minecraft: "x", loader: "y", fabricAPI: "z", modJar: nil))
        let source = root.appending(path: "orchard.jar")
        try Data("orchard".utf8).write(to: source)
        let mods = root.appending(path: "mods")
        try installer.installMods(["orchard.jar": source], into: mods)
        for name in ["lithium.jar", "authme.jar.disabled", "notes.txt"] {
            try Data(name.utf8).write(to: mods.appending(path: name))
        }
        #expect(VersionMove.ownMods(in: mods).map(\.lastPathComponent) == ["authme.jar.disabled", "lithium.jar"])
    }

    @Test func worldsAreCopiedOnce() throws {
        let root = try folder()
        defer { try? FileManager.default.removeItem(at: root) }
        let world = root.appending(path: "saves/New World")
        try FileManager.default.createDirectory(at: world, withIntermediateDirectories: true)
        try Data("first".utf8).write(to: world.appending(path: "level.dat"))
        try VersionMove.backUpWorlds(in: root, from: "26.1.2")
        try Data("second".utf8).write(to: world.appending(path: "level.dat"))
        try VersionMove.backUpWorlds(in: root, from: "26.1.2")
        let copy = root.appending(path: "saves-26.1.2/New World/level.dat")
        #expect(try Data(contentsOf: copy) == Data("first".utf8))
        #expect(!FileManager.default.fileExists(atPath: root.appending(path: "saves-26.1.2.partial").path))
    }

    @Test func noWorldsNoCopy() throws {
        let root = try folder()
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root.appending(path: "saves"), withIntermediateDirectories: true)
        try VersionMove.backUpWorlds(in: root, from: "26.1.2")
        #expect(!FileManager.default.fileExists(atPath: root.appending(path: "saves-26.1.2").path))
    }
}

@Suite struct InstanceRecordTests {

    @Test func aFileFromBeforeRenderersStillReads() throws {
        let json = #"{"created":"2026-10-30T11:02:34Z","icon":"grass","id":"test","name":"Test","playTime":517.4}"#
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let instance = try decoder.decode(Instance.self, from: Data(json.utf8))
        #expect(instance.name == "Test" && instance.minecraft == nil && instance.renderer == .metal)
        #expect(!instance.speedUps && !instance.macFixes)
    }

    @Test func anInstanceThatHadTheSetStillGetsTheMacFixes() throws {
        let json = #"{"created":"2026-10-30T11:02:34Z","icon":"grass","id":"test","name":"Test","playTime":5,"speedUps":true}"#
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let instance = try decoder.decode(Instance.self, from: Data(json.utf8))
        #expect(instance.speedUps && !instance.macFixes)
    }

    @Test func theSpeedUpsAreThePerformanceSet() {
        #expect(SpeedUps.projects == ["gvQqBUqZ", "uXXizFIs", "NNAgCjsB", "5ZwdcRci"])
        #expect(!SpeedUps.projects.contains { VersionMove.needsMojangRenderer.contains($0) })
    }

    @Test func theMacFixesAreMacOSInputFixes() {
        #expect(SpeedUps.macFixes == ["x9BrsVME"])
        #expect(!SpeedUps.macFixes.contains { VersionMove.needsMojangRenderer.contains($0) || SpeedUps.projects.contains($0) })
        #expect(SpeedUps(installer: ContentInstaller(modrinth: Modrinth(), minecraft: "26.3")).set == SpeedUps.projects)
    }
}
