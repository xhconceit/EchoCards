# 知声卡 macOS 版

macOS 客户端使用 SwiftUI 开发。`project.yml` 是 Xcode 工程配置的唯一来源，
通过 XcodeGen 生成本地 `EchoCards.xcodeproj`。

## 环境

- macOS 26+
- Xcode
- XcodeGen 2.46.0+

```bash
brew install xcodegen
```

## 生成工程

```bash
cd mac
xcodegen generate
```

生成的 `EchoCards.xcodeproj` 不提交 Git；修改 `project.yml` 后需要重新生成。

## 测试

```bash
xcodebuild test \
  -project EchoCards.xcodeproj \
  -scheme EchoCards \
  -destination 'platform=macOS' \
  -derivedDataPath .build/xcode \
  CODE_SIGNING_ALLOWED=NO
```

## 生成 Release Archive

```bash
xcodebuild archive \
  -project EchoCards.xcodeproj \
  -scheme EchoCards \
  -configuration Release \
  -destination 'generic/platform=macOS' \
  -archivePath dist/EchoCards.xcarchive \
  -derivedDataPath .build/xcode \
  CODE_SIGNING_ALLOWED=NO
```

未签名的 App 位于：

```text
dist/EchoCards.xcarchive/Products/Applications/EchoCards.app
```

面向其他用户发布时，需要移除 `CODE_SIGNING_ALLOWED=NO`，使用 Developer ID
Application 证书签名，并完成 Apple 公证。

## App 图标

macOS 与 Android 共用 `tools/icon/IconGenerator.java` 中的品牌绘制参数：

```bash
cd ..
java -Djava.awt.headless=true tools/icon/IconGenerator.java \
  --mac \
  mac/Resources/Assets.xcassets/AppIcon.appiconset
```
