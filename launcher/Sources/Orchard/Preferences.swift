import Foundation
import OrchardKit

/// Settings keys and their defaults. Views bind with `@AppStorage`; the launcher reads them here
/// when the game starts.
enum Preferences {

    static let memoryKey = "memory"
    static let afterLaunchKey = "afterLaunch"
    static let javaArgumentsKey = "javaArguments"
    static let sceneKey = "scene"
    static let playerName = "playerName"
    /// The signed-in Microsoft account's profile, as JSON; its tokens are in the Keychain.
    static let microsoftAccount = "microsoftAccount"
    static let openingKey = "opening"

    enum AfterLaunch: String, CaseIterable, Identifiable {
        case keepOpen, hide, quit

        var id: Self { self }

        var label: String {
            switch self {
            case .keepOpen: "Keep Orchard open"
            case .hide: "Hide Orchard until Minecraft quits"
            case .quit: "Quit Orchard"
            }
        }
    }

    /// Mojang's own default, enough for the game at normal render distances.
    static let defaultMemory = 4

    /// Leaves the Mac a quarter of its memory, and never offers less than the default.
    static var maximumMemory: Int {
        let gigabytes = Int(ProcessInfo.processInfo.physicalMemory / 1_073_741_824)
        return max(defaultMemory, min(32, gigabytes * 3 / 4))
    }

    static var memory: Int {
        let saved = UserDefaults.standard.integer(forKey: memoryKey)
        return saved == 0 ? defaultMemory : min(saved, maximumMemory)
    }

    static var afterLaunch: AfterLaunch {
        UserDefaults.standard.string(forKey: afterLaunchKey).flatMap(AfterLaunch.init) ?? .keepOpen
    }

    static var scene: SceneTime {
        UserDefaults.standard.string(forKey: sceneKey).flatMap(SceneTime.init) ?? .timeOfDay
    }

    static var opening: Opening {
        UserDefaults.standard.string(forKey: openingKey).flatMap(Opening.init) ?? .iconFirst
    }

    static var javaArguments: [String] {
        (UserDefaults.standard.string(forKey: javaArgumentsKey) ?? "")
            .split(whereSeparator: \.isWhitespace).map(String.init)
    }

    /// The Mac account's short name when Minecraft would accept it, so the first play needs no setup.
    static var defaultName: String {
        let name = NSUserName()
        return OfflineAccount.isValid(name) ? name : "Player"
    }
}

extension InstanceRenderer {

    /// The name the instance's menu gives the choice.
    var label: String {
        switch self {
        case .metal: "Metal"
        case .mojang: "Mojang’s"
        }
    }
}

extension SceneTime {

    /// The name Settings gives the choice.
    var label: String {
        switch self {
        case .timeOfDay: "Auto"
        case .day: "Day"
        case .sunset: "Sunset"
        case .night: "Night"
        }
    }
}
