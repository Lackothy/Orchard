import Foundation

/// One thing the game logged: its first line, read apart into time, thread, level and message,
/// and the lines after it that belong to it, such as a stack trace or the list of mods.
public struct LogEntry: Identifiable, Sendable, Equatable {

    public enum Level: Sendable, Equatable {
        case debug, info, warning, error
        /// Written by the launcher, not the game: starting, quitting, a session read from a file.
        case launcher
    }

    public let id: Int
    public let time: String?
    public let thread: String?
    public let level: Level
    public let message: String
    public internal(set) var detail: [String]

    /// The entry as the game printed it, for copying.
    public var text: String {
        var first = message
        if let time, let thread {
            first = "[\(time)] [\(thread)/\(level.name)]: \(message)"
        } else if let time {
            first = "[\(time)] \(message)"
        }
        return ([first] + detail).joined(separator: "\n")
    }
}

extension LogEntry.Level {
    var name: String {
        switch self {
        case .debug: "DEBUG"
        case .info: "INFO"
        case .warning: "WARN"
        case .error: "ERROR"
        case .launcher: "ORCHARD"
        }
    }
}

/// What the game has printed this session, as entries, newest last, with running counts of the
/// warnings and errors.
public struct GameLog: Sendable {

    public private(set) var entries: [LogEntry] = []
    public private(set) var warnings = 0
    public private(set) var errors = 0
    /// Goes up with every line, including ones added to the last entry, so a view can follow.
    public private(set) var lines = 0
    private var nextID = 0
    private let limit: Int

    public init(limit: Int = 20_000) {
        self.limit = limit
    }

    public var isEmpty: Bool { entries.isEmpty }

    /// A line the game printed: a new entry if it starts like one, otherwise more of the last.
    public mutating func append(_ raw: String) {
        let line = Self.withoutEscapes(raw)
        lines += 1
        if let match = line.wholeMatch(of: Self.header) {
            let level: LogEntry.Level = switch match.3 {
            case "WARN": .warning
            case "ERROR", "FATAL": .error
            case "DEBUG", "TRACE": .debug
            default: .info
            }
            add(LogEntry(id: nextID, time: String(match.1), thread: String(match.2), level: level,
                         message: String(match.4), detail: []))
        } else if Self.continues(line), let last = entries.indices.last, entries[last].level != .launcher {
            entries[last].detail.append(line)
        } else {
            let level: LogEntry.Level = line.hasPrefix("WARNING:") ? .warning : .info
            add(LogEntry(id: nextID, time: nil, thread: nil, level: level, message: line, detail: []))
        }
    }

    /// A line from the launcher itself.
    public mutating func note(_ text: String, at date: Date = .now) {
        lines += 1
        let clock = Calendar.current.dateComponents([.hour, .minute, .second], from: date)
        let time = String(format: "%02d:%02d:%02d", clock.hour ?? 0, clock.minute ?? 0, clock.second ?? 0)
        add(LogEntry(id: nextID, time: time, thread: nil, level: .launcher, message: text, detail: []))
    }

    public mutating func removeAll() {
        entries.removeAll()
        warnings = 0
        errors = 0
        lines += 1
    }

    private mutating func add(_ entry: LogEntry) {
        nextID += 1
        entries.append(entry)
        count(entry, by: 1)
        // Trimmed in batches, so a long session doesn't shift the whole array at every line.
        if entries.count > limit + limit / 10 {
            let gone = entries.count - limit
            for old in entries.prefix(gone) {
                count(old, by: -1)
            }
            entries.removeFirst(gone)
        }
    }

    private mutating func count(_ entry: LogEntry, by step: Int) {
        switch entry.level {
        case .warning: warnings += step
        case .error: errors += step
        default: break
        }
    }

    /// `[12:34:56] [Render thread/WARN]: message`, the pattern Minecraft's log configuration
    /// prints, with the `(logger)` some mods' configurations add.
    nonisolated(unsafe) private static let header =
        #/\[(\d{2}:\d{2}:\d{2}(?:\.\d+)?)\] \[(.+?)/(TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\](?: \([^)]*\))?:? ?(.*)/#

    /// Stack frames, "Caused by", the indented list of mods: lines that belong to the entry above.
    private static func continues(_ line: String) -> Bool {
        guard let first = line.first else { return true }
        if first == "\t" || first == " " { return true }
        if line.hasPrefix("Caused by:") || line.hasPrefix("Suppressed:") { return true }
        // An exception's own line, `java.lang.IllegalStateException: …`, comes before its frames.
        return line.firstMatch(of: #/^[a-z][\w$]*(\.[\w$]+)+(Exception|Error|Throwable)\b/#) != nil
    }

    private static func withoutEscapes(_ line: String) -> String {
        guard line.contains("\u{1B}") else { return line }
        return line.replacing(#/\x{1B}\[[0-9;]*[A-Za-z]/#, with: "")
    }
}
