import CryptoKit
import Foundation

/// A name to play under without a Microsoft account: singleplayer, and servers in offline mode.
///
/// The UUID is the one Minecraft servers derive for offline players, Java's
/// `UUID.nameUUIDFromBytes("OfflinePlayer:" + name)`, so a player keeps their inventory on an
/// offline server between launchers.
public struct OfflineAccount: Codable, Sendable, Equatable {

    public var name: String

    public init(name: String) {
        self.name = name
    }

    /// Minecraft's rule for player names: 3 to 16 letters, digits and underscores.
    public static func isValid(_ name: String) -> Bool {
        (3...16).contains(name.count) && name.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "_") }
    }

    /// Version-3 UUID of `OfflinePlayer:<name>`, without dashes, as the game expects.
    public var uuid: String {
        var bytes = Array(Insecure.MD5.hash(data: Data("OfflinePlayer:\(name)".utf8)))
        bytes[6] = (bytes[6] & 0x0F) | 0x30
        bytes[8] = (bytes[8] & 0x3F) | 0x80
        return bytes.map { String(format: "%02x", $0) }.joined()
    }
}
