import CryptoKit
import Foundation

public struct OfflineAccount: Codable, Sendable, Equatable {

    public var name: String

    public init(name: String) {
        self.name = name
    }

    public static func isValid(_ name: String) -> Bool {
        (3...16).contains(name.count) && name.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "_") }
    }

    public var uuid: String {
        var bytes = Array(Insecure.MD5.hash(data: Data("OfflinePlayer:\(name)".utf8)))
        bytes[6] = (bytes[6] & 0x0F) | 0x30
        bytes[8] = (bytes[8] & 0x3F) | 0x80
        return bytes.map { String(format: "%02x", $0) }.joined()
    }
}
