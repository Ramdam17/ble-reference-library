// swift-tools-version: 5.9
// ─────────────────────────────────────────────────────
// BLE Reference Library — Phase 02: Swift / CoreBluetooth
//
// This Swift Package contains standalone, heavily commented
// modules for BLE operations using Apple's CoreBluetooth framework.
//
// Unit tests run via `swift test` (no BLE hardware required).
// Real-device integration tests run via the TestApp Xcode project.
// ─────────────────────────────────────────────────────

import PackageDescription

let package = Package(
    name: "BLEReference",
    platforms: [
        .iOS(.v15),
        .macOS(.v12),
    ],
    products: [
        .library(
            name: "BLEReference",
            targets: ["BLEReference"]
        ),
    ],
    targets: [
        .target(
            name: "BLEReference",
            path: "Sources/BLEReference"
        ),
        .testTarget(
            name: "BLEReferenceTests",
            dependencies: ["BLEReference"],
            path: "Tests/BLEReferenceTests"
        ),
    ]
)
