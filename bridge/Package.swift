// swift-tools-version:5.10
import PackageDescription

let package = Package(
    name: "PennyBridge",
    platforms: [.macOS(.v14)],
    products: [
        .executable(name: "penny-bridge", targets: ["PennyBridge"]),
    ],
    targets: [
        .executableTarget(
            name: "PennyBridge",
            path: "Sources/PennyBridge",
            linkerSettings: [.linkedLibrary("sqlite3")]
        ),
    ]
)
