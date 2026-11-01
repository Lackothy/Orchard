import Foundation

/// The machine a rule is checked against. Orchard only runs on Apple silicon Macs.
public struct Platform: Sendable {
    public var os = "osx"
    public var arch = "arm64"
    /// Launcher features such as `has_custom_resolution`; absent means off.
    public var features: [String: Bool] = [:]

    public init(features: [String: Bool] = [:]) {
        self.features = features
    }
}

/// One entry in a library's or argument's `rules`: allow or disallow, when the OS and features match.
public struct Rule: Codable, Sendable, Equatable {
    public enum Action: String, Codable, Sendable {
        case allow, disallow
    }

    public struct OS: Codable, Sendable, Equatable {
        public var name: String?
        public var arch: String?
    }

    public var action: Action
    public var os: OS?
    public var features: [String: Bool]?

    func matches(_ platform: Platform) -> Bool {
        if let name = os?.name, name != platform.os {
            return false
        }
        if let arch = os?.arch, arch != platform.arch {
            return false
        }
        for (feature, wanted) in features ?? [:] where (platform.features[feature] ?? false) != wanted {
            return false
        }
        return true
    }
}

extension Array where Element == Rule {
    /// Mojang's semantics: no rules allows; otherwise the last matching rule decides, and none
    /// matching disallows.
    public func allows(_ platform: Platform) -> Bool {
        if isEmpty {
            return true
        }
        var allowed = false
        for rule in self where rule.matches(platform) {
            allowed = rule.action == .allow
        }
        return allowed
    }
}
