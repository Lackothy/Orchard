import Foundation

/// What a Minecraft crash report says went wrong, read from the file the game writes into
/// `crash-reports` as it dies.
public struct CrashReport: Sendable, Equatable {

    public let file: URL
    /// What the game was doing: "Initializing game", "Rendering screen", "Ticking entity".
    public let description: String
    /// The innermost exception, without its package: `NullPointerException: Cannot read …`.
    public let cause: String
    /// The mod Fabric names when one of its entrypoints threw.
    public let mod: String?

    public init?(text: String, file: URL) {
        let lines = text.components(separatedBy: .newlines)
        guard let at = lines.firstIndex(where: { $0.hasPrefix("Description: ") }) else { return nil }
        description = String(lines[at].dropFirst("Description: ".count))
        // The trace runs from the first line after the description to the next blank one.
        let trace = lines[(at + 1)...].drop { $0.trimmingCharacters(in: .whitespaces).isEmpty }
            .prefix { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        guard let thrown = trace.first else { return nil }
        let innermost = trace.last { $0.hasPrefix("Caused by: ") }.map { String($0.dropFirst("Caused by: ".count)) } ?? thrown
        cause = Self.withoutPackage(innermost)
        mod = thrown.firstMatch(of: #/provided by '([^']+)'/#).map { String($0.1) }
        self.file = file
    }

    public init?(file: URL) {
        guard let text = try? String(contentsOf: file, encoding: .utf8) else { return nil }
        self.init(text: text, file: file)
    }

    /// The newest report in a game folder written at `since` or after it.
    public static func newest(in gameDirectory: URL, since: Date) -> CrashReport? {
        let folder = gameDirectory.appending(path: "crash-reports")
        let files = (try? FileManager.default.contentsOfDirectory(
            at: folder, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
        let dated = files.filter { $0.pathExtension == "txt" }.compactMap { file -> (URL, Date)? in
            guard let date = try? file.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate,
                  date >= since else { return nil }
            return (file, date)
        }
        return dated.max { $0.1 < $1.1 }.flatMap { CrashReport(file: $0.0) }
    }

    /// `java.lang.NullPointerException: …` reads as `NullPointerException: …`.
    private static func withoutPackage(_ line: String) -> String {
        let head = line.prefix { $0 != ":" }
        guard let dot = head.lastIndex(of: "."), !head.contains(" ") else { return line }
        return String(line[line.index(after: dot)...])
    }
}
