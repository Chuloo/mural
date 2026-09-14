// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "MuralCore",
    platforms: [.macOS(.v14), .iOS(.v17)],
    products: [.library(name: "MuralCore", targets: ["MuralCore"])],
    targets: [
        .target(name: "MuralCore", path: "Core", resources: [
            .process("Resources/ogden-850-zh-cn.json"),
            .process("Resources/Ogden-NOTICE.txt"),
            // Preserve accent folders: both contain the same 850 filenames.
            .copy("Resources/OgdenAudio")
        ]),
        .testTarget(name: "MuralCoreTests", dependencies: ["MuralCore"], path: "Tests")
    ]
)
