import Foundation
import Observation

@Observable
final class TestLogger {
    private(set) var entries: [LogEntry] = []
    private var fileHandle: FileHandle?
    private let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss.SSS"
        return f
    }()

    struct LogEntry: Identifiable {
        let id = UUID()
        let timestamp: Date
        let level: Level
        let message: String

        enum Level: String {
            case info = "INFO"
            case success = "PASS"
            case error = "FAIL"
            case data = "DATA"
        }

        var formatted: String {
            let df = DateFormatter()
            df.dateFormat = "HH:mm:ss.SSS"
            return "[\(df.string(from: timestamp))] [\(level.rawValue)] \(message)"
        }
    }

    func start(prefix: String) {
        let timestamp = ISO8601DateFormatter().string(from: Date())
            .replacingOccurrences(of: ":", with: "")
            .replacingOccurrences(of: "-", with: "")
        let filename = "\(prefix)_\(timestamp).txt"

        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        let url = docs.appendingPathComponent(filename)
        FileManager.default.createFile(atPath: url.path, contents: nil)
        fileHandle = try? FileHandle(forWritingTo: url)

        log(.info, "Log started: \(filename)")
    }

    func log(_ level: LogEntry.Level, _ message: String) {
        let entry = LogEntry(timestamp: Date(), level: level, message: message)
        entries.append(entry)
        if let data = (entry.formatted + "\n").data(using: .utf8) {
            fileHandle?.write(data)
        }
    }

    func stop() {
        fileHandle?.closeFile()
        fileHandle = nil
    }
}
