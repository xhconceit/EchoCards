# EchoCards macOS

macOS 原生 SwiftUI 客户端，目标为 macOS 26+，使用 Liquid Glass，并在不支持时降级为系统 Material。

## 本地命令

```bash
cd mac
swift build
swift test
swift run EchoCardsMac
```

生成并验证本地签名的 `.app`：

```bash
./scripts/package-app.sh
./scripts/smoke-test.sh
```

正式发布时将 `codesign --sign -` 替换为开发者证书签名，并执行 Apple 公证流程。

也可以在 Xcode 中打开 `Package.swift` 运行 `EchoCardsMac` scheme。

当前阶段只包含工程骨架、导航、空状态和视觉设计层；数据、TTS 和跟读识别将在后续阶段接入。
