// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "MuralCore",
    platforms: [.macOS(.v14), .iOS(.v17)],
    products: [.library(name: "MuralCore", targets: ["MuralCore"])],
    targets: [
        .target(name: "MuralCore", path: "Core"),
        .testTarget(name: "MuralCoreTests", dependencies: ["MuralCore"], path: "Tests", exclude: ["Fixtures"])
    ]
)
