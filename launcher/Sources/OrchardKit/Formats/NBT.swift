import Foundation

/// Minecraft's Named Binary Tag format, read only: `level.dat` (gzipped) for a world's name and
/// when it was last played, `servers.dat` (plain) for the server list.
public indirect enum NBT: Sendable, Equatable {
    case byte(Int8)
    case short(Int16)
    case int(Int32)
    case long(Int64)
    case float(Float)
    case double(Double)
    case bytes([UInt8])
    case string(String)
    case list([NBT])
    case compound([String: NBT])
    case ints([Int32])
    case longs([Int64])

    public subscript(key: String) -> NBT? {
        if case .compound(let values) = self { values[key] } else { nil }
    }

    public var string: String? {
        if case .string(let value) = self { value } else { nil }
    }

    public var list: [NBT]? {
        if case .list(let values) = self { values } else { nil }
    }

    /// Any whole-number tag, widened.
    public var integer: Int64? {
        switch self {
        case .byte(let v): Int64(v)
        case .short(let v): Int64(v)
        case .int(let v): Int64(v)
        case .long(let v): v
        default: nil
        }
    }

    /// The root tag of an uncompressed NBT file.
    public static func read(_ data: Data) throws -> NBT {
        var reader = Reader(bytes: [UInt8](data))
        guard try reader.u8() == 10 else { throw FormatError.corrupt("NBT root is not a compound") }
        _ = try reader.text()
        return try reader.payload(10, depth: 0)
    }

    public static func readGzipped(_ data: Data) throws -> NBT {
        try read(Inflate.gunzip(data))
    }

    private struct Reader {
        let bytes: [UInt8]
        var offset = 0

        mutating func take(_ count: Int) throws -> ArraySlice<UInt8> {
            guard count >= 0, offset + count <= bytes.count else { throw FormatError.corrupt("NBT ends early") }
            defer { offset += count }
            return bytes[offset..<offset + count]
        }

        mutating func u8() throws -> UInt8 {
            try take(1).first!
        }

        mutating func big(_ count: Int) throws -> UInt64 {
            try take(count).reduce(0) { $0 << 8 | UInt64($1) }
        }

        mutating func length() throws -> Int {
            let value = Int32(bitPattern: UInt32(try big(4)))
            guard value >= 0 else { throw FormatError.corrupt("NBT length") }
            return Int(value)
        }

        mutating func text() throws -> String {
            // Java's modified UTF-8; ordinary text reads the same as UTF-8.
            String(decoding: try take(Int(try big(2))), as: UTF8.self)
        }

        mutating func payload(_ type: UInt8, depth: Int) throws -> NBT {
            guard depth < 64 else { throw FormatError.corrupt("NBT nested too deep") }
            switch type {
            case 1: return .byte(Int8(bitPattern: try u8()))
            case 2: return .short(Int16(bitPattern: UInt16(try big(2))))
            case 3: return .int(Int32(bitPattern: UInt32(try big(4))))
            case 4: return .long(Int64(bitPattern: try big(8)))
            case 5: return .float(Float(bitPattern: UInt32(try big(4))))
            case 6: return .double(Double(bitPattern: try big(8)))
            case 7: return .bytes(Array(try take(try length())))
            case 8: return .string(try text())
            case 9:
                let element = try u8()
                let count = try length()
                var values: [NBT] = []
                for _ in 0..<count {
                    values.append(try payload(element, depth: depth + 1))
                }
                return .list(values)
            case 10:
                var values: [String: NBT] = [:]
                while true {
                    let tag = try u8()
                    if tag == 0 { return .compound(values) }
                    let name = try text()
                    values[name] = try payload(tag, depth: depth + 1)
                }
            case 11:
                return .ints(try (0..<length()).map { _ in Int32(bitPattern: UInt32(try big(4))) })
            case 12:
                return .longs(try (0..<length()).map { _ in Int64(bitPattern: try big(8)) })
            default:
                throw FormatError.corrupt("NBT tag \(type)")
            }
        }
    }
}
