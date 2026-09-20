# 知声卡开发约定

第一版仅开发 Android 原生 App，技术决策见 [ADR-001](docs/decisions/001-use-native-android.md)。

- 使用 Kotlin、Jetpack Compose 和 Material 3；工程位于 `android/`。
- 按技术架构采用 ViewModel + StateFlow、协程、Room；朗读用 Android TextToSpeech，跟读识别用 Vosk 端侧离线模型（见 ADR-002）。
- 开发前阅读相关的需求、架构和接口文档，入口为 [docs/README.md](docs/README.md)。文档中的草稿接口不代表已经实现。
- 依赖版本以 `android/gradle/libs.versions.toml` 和 Gradle Wrapper 为准。
- Composable 不直接调用 Room DAO、TextToSpeech 或语音识别适配器；通过 ViewModel 和领域接口访问。
- 语音回调携带 `operationId`；暂停、翻页和退出后忽略旧操作回调，进入后台时停止收音。
- 核心语音流程必须在目标 Android 真机验证，不能只用模拟器验收。

## 本地检查

在 `android/` 目录使用 Gradle Wrapper：

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug
# 连接设备或启动模拟器后运行
./gradlew :app:connectedDebugAndroidTest
```
