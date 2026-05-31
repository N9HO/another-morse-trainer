// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "MorseKit",
    platforms: [
        .iOS(.v16),
        .macOS(.v13)
    ],
    products: [
        .library(name: "MorseKit", targets: ["MorseKit"]),
        .executable(name: "MorseKitCheck", targets: ["MorseKitCheck"])
    ],
    targets: [
        .target(name: "MorseKit"),
        .executableTarget(name: "MorseKitCheck", dependencies: ["MorseKit"])
    ]
)
