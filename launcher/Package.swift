// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "OrchardLauncher",
    platforms: [.macOS(.v26)],
    targets: [
        .target(name: "OrchardKit"),
        .executableTarget(name: "Orchard", dependencies: ["OrchardKit"]),
        .testTarget(name: "OrchardKitTests", dependencies: ["OrchardKit"]),
    ]
)
