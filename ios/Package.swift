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
        // Complete concurrency checking, not `targeted`: the Swift sources here
        // turned out to be clean under it as they stand, so this locks that in
        // rather than describing an ambition. The app target is only on
        // `targeted` — see SWIFT_STRICT_CONCURRENCY in the Xcode project, and
        // the note in CLAUDE.md about what it does and does not catch.
        .target(
            name: "MorseKit",
            swiftSettings: [.enableUpcomingFeature("StrictConcurrency")]
        ),
        // The vendored Carrier Wave CW audio decoder (pure C99). Kept
        // byte-identical to the firmware copy — see Sources/CWDecoderCore/PROVENANCE.md.
        .target(
            name: "CWDecoderCore",
            exclude: ["PROVENANCE.md", "LICENSE"],
            publicHeadersPath: "."
        ),
        .executableTarget(
            name: "MorseKitCheck",
            dependencies: ["MorseKit", "CWDecoderCore"],
            swiftSettings: [.enableUpcomingFeature("StrictConcurrency")]
        )
    ]
)
