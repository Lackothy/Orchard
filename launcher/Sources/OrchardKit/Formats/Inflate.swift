import Compression
import Foundation

enum FormatError: Error, Equatable {
    case corrupt(String)
}

/// Raw DEFLATE (RFC 1951), as zip entries and gzip members hold it.
enum Inflate {

    /// Decompresses `data` to exactly `size` bytes; anything else means the data is damaged.
    static func raw(_ data: Data, size: Int) throws -> Data {
        guard size > 0 else { return Data() }
        guard !data.isEmpty else { throw FormatError.corrupt("empty deflate stream") }
        var out = Data(count: size)
        let written = out.withUnsafeMutableBytes { destination in
            data.withUnsafeBytes { source in
                // Apple's ZLIB is raw DEFLATE, with no zlib header or checksum.
                compression_decode_buffer(
                    destination.bindMemory(to: UInt8.self).baseAddress!, size,
                    source.bindMemory(to: UInt8.self).baseAddress!, data.count, nil, COMPRESSION_ZLIB)
            }
        }
        guard written == size else { throw FormatError.corrupt("deflate stream ended early") }
        return out
    }

    /// A gzip file's contents (RFC 1952): the header skipped, the size read from the trailer.
    static func gunzip(_ data: Data) throws -> Data {
        let bytes = [UInt8](data)
        guard bytes.count >= 18, bytes[0] == 0x1F, bytes[1] == 0x8B, bytes[2] == 8 else {
            throw FormatError.corrupt("not gzip")
        }
        let flags = bytes[3]
        var offset = 10
        if flags & 0x04 != 0 {
            guard offset + 2 <= bytes.count else { throw FormatError.corrupt("gzip header") }
            offset += 2 + (Int(bytes[offset]) | Int(bytes[offset + 1]) << 8)
        }
        for flag: UInt8 in [0x08, 0x10] where flags & flag != 0 {
            while offset < bytes.count, bytes[offset] != 0 { offset += 1 }
            offset += 1
        }
        if flags & 0x02 != 0 {
            offset += 2
        }
        let trailer = bytes.count - 8
        guard offset < trailer else { throw FormatError.corrupt("gzip header") }
        let size = Int(bytes[trailer + 4]) | Int(bytes[trailer + 5]) << 8
            | Int(bytes[trailer + 6]) << 16 | Int(bytes[trailer + 7]) << 24
        return try raw(Data(bytes[offset..<trailer]), size: size)
    }
}
