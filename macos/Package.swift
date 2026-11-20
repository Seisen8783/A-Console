// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "UnRealConsole",
    platforms: [.macOS(.v14)],
    targets: [
        .executableTarget(
            name: "UnRealConsole",
            resources: [
                .copy("Resources/device_bridge.py"),
                .copy("Resources/VERSION"),
            ]
        ),
        .testTarget(name: "UnRealConsoleTests", dependencies: ["UnRealConsole"])
    ]
)
