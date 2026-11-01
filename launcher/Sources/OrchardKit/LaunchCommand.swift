import Foundation

public struct LaunchOptions: Sendable {
    public var player: Player
    public var memory: Int
    public var extraJVMArguments: [String]
    public var launcherVersion: String
    public var quickPlay: QuickPlay?
    public var scene: SceneTime
    public var reduceMotion: Bool
    public var metal: Bool

    var orchardArguments: [String] {
        scene.jvmArguments + (reduceMotion ? ["-Dorchard.reduceMotion=true"] : [])
            + (metal ? [] : ["-Dorchard.renderer=mojang"])
    }

    public init(player: Player, memory: Int, extraJVMArguments: [String] = [], launcherVersion: String,
                quickPlay: QuickPlay? = nil, scene: SceneTime = .timeOfDay, reduceMotion: Bool = false,
                metal: Bool = true) {
        self.player = player
        self.memory = memory
        self.extraJVMArguments = extraJVMArguments
        self.launcherVersion = launcherVersion
        self.quickPlay = quickPlay
        self.scene = scene
        self.reduceMotion = reduceMotion
        self.metal = metal
    }

    public init(account: OfflineAccount, memory: Int, extraJVMArguments: [String] = [], launcherVersion: String,
                quickPlay: QuickPlay? = nil, scene: SceneTime = .timeOfDay, reduceMotion: Bool = false,
                metal: Bool = true) {
        self.init(player: .offline(account), memory: memory, extraJVMArguments: extraJVMArguments,
                  launcherVersion: launcherVersion, quickPlay: quickPlay, scene: scene, reduceMotion: reduceMotion, metal: metal)
    }
}

public enum QuickPlay: Hashable, Sendable {
    case world(String)
    case server(String)
}

public enum LaunchCommand {

    static func apply(_ quickPlay: QuickPlay?, to platform: inout Platform, values: inout [String: String]) {
        switch quickPlay {
        case .world(let folder):
            platform.features["is_quick_play_singleplayer"] = true
            values["quickPlaySingleplayer"] = folder
        case .server(let address):
            platform.features["is_quick_play_multiplayer"] = true
            values["quickPlayMultiplayer"] = address
        case nil:
            break
        }
    }

    public static func arguments(for install: Installation, options: LaunchOptions,
                                 platform: Platform = Platform()) -> [String] {
        let version = install.version
        let fabric = install.fabric
        var platform = platform
        var values: [String: String] = [
            "auth_player_name": options.player.name,
            "version_name": fabric.id,
            "game_directory": install.gameDirectory.path,
            "assets_root": install.paths.assets.path,
            "assets_index_name": version.assetIndex.id,
            "auth_uuid": options.player.uuid,
            "auth_access_token": options.player.accessToken,
            "clientid": options.player.clientID ?? "",
            "version_type": version.type,
            "natives_directory": install.paths.natives(fabric.id).path,
            "launcher_name": "Orchard",
            "launcher_version": options.launcherVersion,
            "classpath": install.classpath.map(\.path).joined(separator: ":"),
            "path": install.loggingConfig?.path ?? "",
        ]
        apply(options.quickPlay, to: &platform, values: &values)

        var jvm = memoryArguments(options.memory)
        jvm += (version.arguments.defaultUserJVM ?? [])
            .flatMap { $0.values(on: platform) }
            .filter { !$0.hasPrefix("-Xmx") && !$0.hasPrefix("-Xms") }
        jvm.append("--enable-native-access=ALL-UNNAMED")
        jvm += options.orchardArguments
        jvm += options.extraJVMArguments
        jvm += version.arguments.jvm.flatMap { $0.values(on: platform) }
        jvm += fabric.arguments.jvm
        if let logging = version.logging?.client, install.loggingConfig != nil {
            jvm.append(logging.argument)
        }

        var game = version.arguments.game.flatMap { $0.values(on: platform) }
        game += fabric.arguments.game
        if !options.player.online {
            game.append("--offlineDeveloperMode")
        }

        return substitute(jvm, values) + [fabric.mainClass] + substitute(game, values)
    }

    static func memoryArguments(_ gigabytes: Int) -> [String] {
        let most = max(1, gigabytes)
        return ["-Xms\(min(2, most))G", "-Xmx\(most)G"]
    }

    static func substitute(_ arguments: [String], _ values: [String: String]) -> [String] {
        var out: [String] = []
        for argument in arguments {
            var result = argument
            while let start = result.range(of: "${"), let end = result.range(of: "}", range: start.upperBound..<result.endIndex) {
                let key = String(result[start.upperBound..<end.lowerBound])
                result.replaceSubrange(start.lowerBound..<end.upperBound, with: values[key] ?? "")
            }
            if result.isEmpty, argument.contains("${") {
                if let flag = out.last, flag.hasPrefix("--") {
                    out.removeLast()
                }
                continue
            }
            out.append(result)
        }
        return out
    }
}
