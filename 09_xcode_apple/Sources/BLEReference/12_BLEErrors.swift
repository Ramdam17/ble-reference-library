// ─────────────────────────────────────────────────────
// BLE Reference Library
// Module  : 12 — Error Handling & Timeouts
// Platform: Swift / CoreBluetooth
// Sprint  : Sprint 30
// Phase   : Phase 02 — Swift / CoreBluetooth (Apple)
//
// Purpose : BLE error hierarchy and timeout utilities.
//
// Depends : Sprint 21 (Connection)
// ─────────────────────────────────────────────────────

import Foundation

// MARK: - BLE Error Hierarchy

/// Base BLE error type for all BLE operations.
public enum BLEError: LocalizedError, Sendable {
    case connection(BLEConnectionError)
    case scan(BLEScanError)
    case readWrite(BLEReadWriteError)
    case timeout(operation: String, seconds: TimeInterval)
    case serviceNotFound(uuid: String)

    public var errorDescription: String? {
        switch self {
        case .connection(let e): return e.localizedDescription
        case .scan(let e): return e.localizedDescription
        case .readWrite(let e): return e.localizedDescription
        case .timeout(let op, let s): return "\(op) timed out after \(s)s"
        case .serviceNotFound(let uuid): return "Service \(uuid) not found"
        }
    }
}

// MARK: - Timeout Utility

/// Execute an async operation with a timeout.
///
/// - Parameters:
///   - seconds: Maximum time to wait.
///   - operation: Description for error messages.
///   - work: The async work to execute.
///
/// - Returns: The result of `work`.
///
/// - Throws: `BLEError.timeout` if the work doesn't complete in time.
///
/// - Note: This is Swift's equivalent of Python's `asyncio.wait_for`.
///   CoreBluetooth doesn't have built-in timeouts, so this is essential
///   for preventing hung connections/reads.
public func withBLETimeout<T: Sendable>(
    seconds: TimeInterval,
    operation: String = "BLE operation",
    work: @escaping @Sendable () async throws -> T
) async throws -> T {
    try await withThrowingTaskGroup(of: T.self) { group in
        group.addTask {
            try await work()
        }

        group.addTask {
            try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            throw BLEError.timeout(operation: operation, seconds: seconds)
        }

        // Return whichever finishes first
        guard let result = try await group.next() else {
            throw BLEError.timeout(operation: operation, seconds: seconds)
        }

        // Cancel the remaining task
        group.cancelAll()
        return result
    }
}
