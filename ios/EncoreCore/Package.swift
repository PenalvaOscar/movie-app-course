// swift-tools-version:6.0
import PackageDescription

let package = Package(
    name: "EncoreCore",
    platforms: [.macOS(.v14), .iOS(.v17)],
    products: [
        .library(name: "EncoreCore", targets: ["EncoreCore"]),
    ],
    targets: [
        .target(
            name: "EncoreCore",
            linkerSettings: [.linkedLibrary("sqlite3")]
        ),
        .testTarget(
            name: "EncoreCoreTests",
            dependencies: ["EncoreCore"],
            resources: [.copy("Fixtures")]
        ),
    ],
    swiftLanguageModes: [.v6]
)
