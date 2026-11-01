import Foundation

public struct Platform: Sendable {
    public var os = "osx"
    public var arch = "arm64"
    public var features: [String: Bool] = [:]

    public init(features: [String: Bool] = [:]) {
        self.features = features
    }
}

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
