import Foundation

public struct LaunchOptions: Sendable {
    public var player: Player
    /// The most memory the game may use, in gigabytes.
    public var memory: Int
    /// The player's own Java arguments, after Orchard's.
    public var extraJVMArguments: [String]
    public var launcherVersion: String
    /// Somewhere to go straight into instead of the title screen.
    public var quickPlay: QuickPlay?
    /// The time of day the orchard behind the game's menus shows.
    public var scene: SceneTime
    /// Whether the Mac asks for less motion, which stops the orchard's clouds.
    public var reduceMotion: Bool
    /// Whether the Metal renderer in Orchard's jar draws the game. Off, it is Mojang's renderer.
    public var metal: Bool

    /// What Orchard's mod is told, before the player's own arguments so one of theirs still wins.
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

/// Minecraft's Quick Play: the game opens a world or joins a server as soon as it has loaded.
public enum QuickPlay: Hashable, Sendable {
    /// A world, by its folder under `saves/`.
    case world(String)
    /// A server, by address.
    case server(String)
}

/// The `java` command line for an installation, built the way Mojang's launcher builds it:
/// JVM arguments, the logging config, the main class, then game arguments, each filtered by
/// its rules and with `${placeholders}` filled in.
public enum LaunchCommand {

    /// Turns on the feature that lets the version's own Quick Play arguments through, with
    /// their value.
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
            // Offline, there is no session, and the game only needs something non-empty here.
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
        // Mojang's garbage-collector choices, without its memory sizes, which are the player's.
        jvm += (version.arguments.defaultUserJVM ?? [])
            .flatMap { $0.values(on: platform) }
            .filter { !$0.hasPrefix("-Xmx") && !$0.hasPrefix("-Xms") }
        // Orchard's power readings and the Metal renderer call macOS through FFM, and MacOS Input
        // Fixes loads a native library: Java 25 warns about each and says a later Java will refuse.
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
        // Offline, Mojang's services have nothing for the player, but from 26.3 the game waits for
        // them before it loads: half a minute of black window when the network is slow. This is
        // Mojang's own switch for playing without them; it also greys out Friends and Realms.
        if !options.player.online {
            game.append("--offlineDeveloperMode")
        }

        return substitute(jvm, values) + [fabric.mainClass] + substitute(game, values)
    }

    static func memoryArguments(_ gigabytes: Int) -> [String] {
        let most = max(1, gigabytes)
        return ["-Xms\(min(2, most))G", "-Xmx\(most)G"]
    }

    /// Fills in `${name}`s. A flag whose value comes out empty, such as `--xuid` offline, is
    /// dropped with its value rather than passed blank.
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
