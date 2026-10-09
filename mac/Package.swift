// swift-tools-version: 6.2
import PackageDescription

let package = Package(
  name: "EchoCardsMac",  // 包名
  platforms: [
    .macOS(.v26)  //最低支持版本
  ],
  products: [  // 生成产物
    .executable(  // 可启动的程序
      name: "EchoCardsMac",
      targets: ["EchoCardsMacApp"]
    )
  ],
  targets: [
    .executableTarget(
      name: "EchoCardsMacApp"
    ),
    .testTarget(
      name: "EchoCardsMacAppTests",
      dependencies: ["EchoCardsMacApp"]
    ),
  ]
)
