// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "EchoCardsMac",
    platforms: [.macOS(.v26)],
    products: [
        .executable(name: "EchoCardsMac", targets: ["EchoCardsMacApp"])
    ],
    targets: [
        .executableTarget(
            name: "EchoCardsMacApp",
            path: "Sources/EchoCardsMacApp",
            linkerSettings: [.linkedLibrary("sqlite3")]
        ),
        .testTarget(
            name: "EchoCardsMacAppTests",
            dependencies: ["EchoCardsMacApp"],
            path: "Tests/EchoCardsMacAppTests"
        )
    ]
)
