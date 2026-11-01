// swift-tools-version: 6.2

import PackageDescription

let package = Package(
    name: "OrchardLauncher",
    platforms: [.macOS(.v26)],
    targets: [
        // Installs and starts the game; no UI, so it can be tested on its own.
        .target(name: "OrchardKit"),
        // The SwiftUI app. Scripts/build-app.sh wraps it into Orchard.app.
        .executableTarget(name: "Orchard", dependencies: ["OrchardKit"]),
        .testTarget(name: "OrchardKitTests", dependencies: ["OrchardKit"]),
    ]
)
