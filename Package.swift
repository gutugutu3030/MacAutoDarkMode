// swift-tools-version: 6.2
// The swift-tools-version declares the minimum version of Swift required to build this package.

import PackageDescription

let package = Package(
    name: "autoDarkMode",
    platforms: [
        .macOS(.v13),
    ],
    products: [
        .executable(name: "autoDarkMode", targets: ["autoDarkMode"]),
    ],
    targets: [
        .target(
            name: "ALSBridge",
            path: "Sources/ALSBridge",
            publicHeadersPath: "include",
            cSettings: [
                .unsafeFlags(["-fobjc-arc"]),
            ],
            linkerSettings: [
                .linkedFramework("CoreFoundation"),
                .linkedFramework("IOKit"),
                .unsafeFlags(["-F", "/System/Library/PrivateFrameworks", "-framework", "BezelServices"]),
            ]
        ),
        .binaryTarget(
            name: "AutoDarkModeKMP",
            path: "kmp/build/XCFrameworks/release/AutoDarkModeKMP.xcframework"
        ),
        .executableTarget(
            name: "autoDarkMode",
            dependencies: ["ALSBridge", "AutoDarkModeKMP"]
        ),
        .testTarget(
            name: "autoDarkModeTests",
            dependencies: ["autoDarkMode"]
        ),
    ]
)
