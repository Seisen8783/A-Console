// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "NoRealConsole",
    platforms: [.macOS(.v14)],
    targets: [
        .executableTarget(name: "NoRealConsole", resources: [.copy("Resources/device_bridge.py")]),
        .testTarget(name: "NoRealConsoleTests", dependencies: ["NoRealConsole"])
    ]
)
