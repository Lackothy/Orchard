import Foundation
import Synchronization

/// The running game. Output arrives a line at a time; `exited` fires once, with the exit status.
public final class GameProcess: Sendable {

    private let process: Process

    private init(process: Process) {
        self.process = process
    }

    public var isRunning: Bool { process.isRunning }
    public var processIdentifier: Int32 { process.processIdentifier }

    public static func start(java: URL, arguments: [String], directory: URL,
                             output: @escaping @Sendable (String) -> Void,
                             exited: @escaping @Sendable (Int32) -> Void) throws -> GameProcess {
        let process = Process()
        process.executableURL = java
        process.arguments = arguments
        process.currentDirectoryURL = directory
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = pipe
        let lines = LineBuffer(output)
        pipe.fileHandleForReading.readabilityHandler = { handle in
            let data = handle.availableData
            if data.isEmpty {
                handle.readabilityHandler = nil
                lines.flush()
            } else {
                lines.append(data)
            }
        }
        process.terminationHandler = { finished in
            exited(finished.terminationStatus)
        }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try process.run()
        return GameProcess(process: process)
    }

    /// Asks the game to quit, as closing its window would.
    public func terminate() {
        process.terminate()
    }
}

/// Splits a byte stream into lines, holding back a partial line until its end arrives.
private final class LineBuffer: Sendable {
    private let pending = Mutex(Data())
    private let emit: @Sendable (String) -> Void

    init(_ emit: @escaping @Sendable (String) -> Void) {
        self.emit = emit
    }

    func append(_ data: Data) {
        let complete: [Data] = pending.withLock { buffer in
            buffer.append(data)
            var lines: [Data] = []
            while let newline = buffer.firstIndex(of: 0x0A) {
                lines.append(buffer[buffer.startIndex..<newline])
                buffer.removeSubrange(buffer.startIndex...newline)
            }
            return lines
        }
        for line in complete {
            emit(String(decoding: line, as: UTF8.self))
        }
    }

    func flush() {
        let rest = pending.withLock { buffer in
            defer { buffer.removeAll() }
            return buffer
        }
        if !rest.isEmpty {
            emit(String(decoding: rest, as: UTF8.self))
        }
    }
}
