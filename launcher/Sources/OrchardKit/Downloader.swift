import CryptoKit
import Foundation

/// One file to fetch, with what it should hash to.
public struct Download: Sendable, Equatable {
    public var url: URL
    public var destination: URL
    public var sha1: String?
    public var size: Int64?
    public var executable = false

    public init(url: URL, destination: URL, sha1: String?, size: Int64?, executable: Bool = false) {
        self.url = url
        self.destination = destination
        self.sha1 = sha1
        self.size = size
        self.executable = executable
    }

    var name: String { destination.lastPathComponent }
}

public struct DownloadProgress: Sendable, Equatable {
    public var completedBytes: Int64 = 0
    public var totalBytes: Int64 = 0
    public var completedFiles = 0
    public var totalFiles = 0

    public var fraction: Double {
        totalBytes > 0 ? Double(completedBytes) / Double(totalBytes) : (totalFiles > 0 ? 1 : 0)
    }
}

/// Fetches files many at a time, checking each against its SHA-1 before moving it into place,
/// so a half-written or corrupted file never sits where the game will look for it.
public struct Downloader: Sendable {

    public var session: URLSession
    public var concurrency: Int

    public init(session: URLSession = .shared, concurrency: Int = 24) {
        self.session = session
        self.concurrency = concurrency
    }

    /// The downloads whose file is missing or the wrong size; with `verify`, the wrong hash too.
    public static func missing(_ downloads: [Download], verify: Bool) -> [Download] {
        var seen = Set<String>()
        return downloads.filter { download in
            guard seen.insert(download.destination.path).inserted else {
                return false
            }
            return !isInPlace(download, verify: verify)
        }
    }

    static func isInPlace(_ download: Download, verify: Bool) -> Bool {
        let path = download.destination.path
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: path) else {
            return false
        }
        if let size = download.size, (attributes[.size] as? NSNumber)?.int64Value != size {
            return false
        }
        if verify, let sha1 = download.sha1 {
            return (try? Self.sha1(of: download.destination)) == sha1
        }
        return true
    }

    public func run(_ downloads: [Download], progress: @Sendable (DownloadProgress) -> Void) async throws {
        var state = DownloadProgress(
            totalBytes: downloads.reduce(0) { $0 + ($1.size ?? 0) }, totalFiles: downloads.count)
        progress(state)
        try await withThrowingTaskGroup(of: Int64.self) { group in
            var queue = downloads.makeIterator()
            for _ in 0..<concurrency {
                guard let next = queue.next() else { break }
                group.addTask { try await fetch(next) }
            }
            while let bytes = try await group.next() {
                state.completedBytes += bytes
                state.completedFiles += 1
                progress(state)
                if let next = queue.next() {
                    group.addTask { try await fetch(next) }
                }
            }
        }
    }

    /// Downloads one file, trying twice more after a network, server or checksum error. Returns its size.
    private func fetch(_ download: Download) async throws -> Int64 {
        var attempt = 1
        while true {
            do {
                return try await fetchOnce(download)
            } catch where attempt < 3 && Self.isTransient(error) {
                try await Task.sleep(for: .milliseconds(400 * attempt))
                attempt += 1
            }
        }
    }

    static func isTransient(_ error: Error) -> Bool {
        switch error {
        case is URLError, LauncherError.badChecksum:
            true
        case LauncherError.download(_, let status):
            status >= 500
        default:
            false
        }
    }

    private func fetchOnce(_ download: Download) async throws -> Int64 {
        let (temporary, response) = try await session.download(from: download.url)
        defer { try? FileManager.default.removeItem(at: temporary) }
        if let http = response as? HTTPURLResponse, http.statusCode != 200 {
            throw LauncherError.download(download.name, status: http.statusCode)
        }
        if let sha1 = download.sha1, try Self.sha1(of: temporary) != sha1 {
            throw LauncherError.badChecksum(download.name)
        }
        let files = FileManager.default
        try files.createDirectory(at: download.destination.deletingLastPathComponent(),
                                  withIntermediateDirectories: true)
        if files.fileExists(atPath: download.destination.path) {
            try files.removeItem(at: download.destination)
        }
        try files.moveItem(at: temporary, to: download.destination)
        if download.executable {
            try files.setAttributes([.posixPermissions: 0o755], ofItemAtPath: download.destination.path)
        }
        let size = (try files.attributesOfItem(atPath: download.destination.path)[.size] as? NSNumber)?.int64Value
        return download.size ?? size ?? 0
    }

    public static func sha1(of url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = Insecure.SHA1()
        while let chunk = try handle.read(upToCount: 1 << 20), !chunk.isEmpty {
            hasher.update(data: chunk)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }
}
