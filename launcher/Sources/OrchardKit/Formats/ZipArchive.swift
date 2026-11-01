import Foundation

/// Reads single files out of a zip: enough to look inside a mod's jar or a resource pack for its
/// name, version and icon without unpacking it. Stored and deflated entries; no zip64.
struct ZipArchive {

    private struct Entry {
        var method: Int
        var compressedSize: Int
        var size: Int
        var localHeader: Int
    }

    private let bytes: Data
    private var entries: [String: Entry] = [:]

    init(url: URL) throws {
        try self.init(data: Data(contentsOf: url, options: .alwaysMapped))
    }

    init(data: Data) throws {
        bytes = data
        // The end-of-central-directory record is in the last 64 KiB plus its own 22 bytes.
        let floor = max(0, data.count - 65_557)
        var end = data.count - 22
        while end >= floor, u32(end) != 0x0605_4B50 {
            end -= 1
        }
        guard end >= floor else { throw FormatError.corrupt("no zip directory") }
        let count = u16(end + 10)
        var offset = u32(end + 16)
        for _ in 0..<count {
            guard offset + 46 <= data.count, u32(offset) == 0x0201_4B50 else {
                throw FormatError.corrupt("zip directory")
            }
            let nameLength = u16(offset + 28)
            let nameEnd = offset + 46 + nameLength
            guard nameEnd <= data.count else { throw FormatError.corrupt("zip directory") }
            let name = String(decoding: data[data.startIndex + offset + 46 ..< data.startIndex + nameEnd], as: UTF8.self)
            entries[name] = Entry(method: u16(offset + 10), compressedSize: u32(offset + 20),
                                  size: u32(offset + 24), localHeader: u32(offset + 42))
            offset = nameEnd + u16(offset + 30) + u16(offset + 32)
        }
    }

    func contains(_ name: String) -> Bool {
        entries[name] != nil
    }

    /// The named file's contents, or nil when the archive has no such file.
    func read(_ name: String) throws -> Data? {
        guard let entry = entries[name] else { return nil }
        let header = entry.localHeader
        guard header + 30 <= bytes.count, u32(header) == 0x0403_4B50 else {
            throw FormatError.corrupt("zip entry \(name)")
        }
        let start = header + 30 + u16(header + 26) + u16(header + 28)
        guard start + entry.compressedSize <= bytes.count else { throw FormatError.corrupt("zip entry \(name)") }
        let stored = bytes[bytes.startIndex + start ..< bytes.startIndex + start + entry.compressedSize]
        switch entry.method {
        case 0: return Data(stored)
        case 8: return try Inflate.raw(Data(stored), size: entry.size)
        default: throw FormatError.corrupt("zip method \(entry.method)")
        }
    }

    private func u16(_ offset: Int) -> Int {
        let i = bytes.startIndex + offset
        return Int(bytes[i]) | Int(bytes[i + 1]) << 8
    }

    private func u32(_ offset: Int) -> Int {
        let i = bytes.startIndex + offset
        return Int(bytes[i]) | Int(bytes[i + 1]) << 8 | Int(bytes[i + 2]) << 16 | Int(bytes[i + 3]) << 24
    }
}
