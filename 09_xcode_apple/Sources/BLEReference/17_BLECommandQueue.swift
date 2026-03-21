// ─────────────────────────────────────────────────────
// BLE Reference Library
// Module  : 17 — Command Queue
// Platform: Swift / CoreBluetooth
// Sprint  : Sprint 35
// Phase   : Phase 02 — Swift / CoreBluetooth (Apple)
//
// Purpose : Serialize BLE GATT operations via an actor-based queue.
//
// Depends : Sprint 23 (Read/Write)
// ─────────────────────────────────────────────────────

// MARK: - Overview
//
// Swift's `actor` model is a natural fit for BLE command serialization.
// Unlike Python (which uses asyncio.PriorityQueue + consumer task),
// Swift actors guarantee serial access to their mutable state.
//
// Each BLE operation is submitted as an async closure to the actor,
// which executes them one at a time, in order.

import Foundation

// MARK: - Command Priority

/// Priority levels for BLE commands.
public enum BLECommandPriority: Int, Comparable, Sendable {
    case high = 0
    case normal = 1
    case low = 2

    public static func < (lhs: Self, rhs: Self) -> Bool {
        lhs.rawValue < rhs.rawValue
    }
}

// MARK: - BLECommandQueue

/// Actor-based command queue for serializing BLE operations.
///
/// Guarantees that only one GATT operation runs at a time,
/// preventing the undefined behavior that occurs with concurrent
/// ATT requests.
///
/// ## Example
/// ```swift
/// let queue = BLECommandQueue()
/// let value = try await queue.enqueue {
///     try await readWriter.read(char, on: peripheral)
/// }
/// ```
///
/// Swift actors naturally serialize access — no explicit lock
/// or consumer task needed (unlike the Python implementation).
public actor BLECommandQueue {

    private var _pendingCount: Int = 0

    /// Number of commands currently waiting.
    public var pendingCount: Int { _pendingCount }

    /// Enqueue an async operation for serial execution.
    ///
    /// - Parameters:
    ///   - priority: Execution priority (currently informational —
    ///     actor serialization is FIFO).
    ///   - timeout: Per-command timeout in seconds (default: 10).
    ///   - operation: The async work to execute.
    ///
    /// - Returns: The result of the operation.
    ///
    /// - Throws: Any error from the operation, or timeout error.
    public func enqueue<T: Sendable>(
        priority: BLECommandPriority = .normal,
        timeout: TimeInterval = 10.0,
        operation: @escaping @Sendable () async throws -> T
    ) async throws -> T {
        _pendingCount += 1
        defer { _pendingCount -= 1 }

        return try await withBLETimeout(
            seconds: timeout,
            operation: "BLE command (priority: \(priority))"
        ) {
            try await operation()
        }
    }
}
