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
        // The vendored Carrier Wave CW audio decoder (pure C99). Kept
        // byte-identical to the firmware copy — see Sources/CWDecoderCore/PROVENANCE.md.
        .target(
            name: "CWDecoderCore",
            exclude: ["PROVENANCE.md", "LICENSE"],
            publicHeadersPath: "."
        ),
        .executableTarget(name: "MorseKitCheck", dependencies: ["MorseKit", "CWDecoderCore"])
    ]
)
