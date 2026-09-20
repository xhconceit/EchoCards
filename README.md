# 知声卡 EchoCards

一个通过知识卡片、语音朗读和用户跟读进行学习的 Android App。

## 当前状态

项目已切换为 Android 原生开发。目前已建立 Kotlin + Jetpack Compose 工程骨架，App 入口仍为占位文本；卡片管理、本地存储和语音学习尚待实现。

[dome.html](dome.html) 是独立的交互设计原型，初始显示演示卡组；卡组编辑、卡片编辑和 JSON 导入只影响当前页面，朗读、自动播放和跟读是流程模拟，不代表 Android 功能已经实现。需求、架构和接口设计见 [文档入口](docs/README.md)。

## 第一版目标

- 卡组搜索、管理，以及卡片新增、编辑、删除、排序
- 从外部 UTF-8 JSON 文件导入卡组和卡片
- 左右循环浏览知识卡，点击翻面查看快速记忆点
- 手动学习、自动跟读和自动播放三种模式
- 自动跟读：朗读 → 用户跟读 → 完成判断 → 记忆点 → 下一张
- 自动播放：真实朗读 → 记忆点 → 下一张
- 按模式提供播放、暂停、继续、重读和速度控制
- 本地保存内容、学习位置和学习结果
- 普通话支持，语音不可用时保留手动学习

账号与跨设备同步、更多语言、标签、掌握度判断和复习计划不在第一版范围内。

## 技术方案

第一版仅支持 Android，决策见 [ADR-001](docs/decisions/001-use-native-android.md)。

- Kotlin + Jetpack Compose + Material 3
- 单 Activity + Navigation Compose
- ViewModel + StateFlow + Kotlin Coroutines
- Room（SQLite）本地存储
- Android TextToSpeech 负责朗读；跟读识别用 Vosk 端侧离线模型（见 [ADR-002](docs/decisions/002-offline-vosk-recognition.md)）

上述为目标架构；当前工程已接入 Compose、Room、Navigation 与语音适配器。依赖版本以 [Version Catalog](android/gradle/libs.versions.toml) 为准。`assets/model-cn/` 是随包分发的离线识别模型（约 62 MB）。

## 本地开发

使用 Android Studio 打开 `android/` 目录并同步 Gradle。工程配置为 `minSdk = 24`、`compileSdk = 37`、`targetSdk = 36`，Gradle Daemon 使用 JDK 21（见 `android/gradle/gradle-daemon-jvm.properties`）。安装对应 Android SDK，并通过 Android Studio 配置本机 SDK 路径。

在项目根目录执行：

```bash
cd android

# 构建 Debug APK
./gradlew :app:assembleDebug

# 单元测试与静态检查
./gradlew :app:testDebugUnitTest :app:lintDebug

# 连接 Android 设备或启动模拟器后安装
./gradlew :app:installDebug

# 设备上的集成测试
./gradlew :app:connectedDebugAndroidTest
```

也可以在 Android Studio 选择 `app` 和目标设备后运行。JVM 单元测试覆盖解析、匹配与学习引擎规则；设备测试覆盖数据库事务与学习页语音流程，需要在目标真机执行。

完整页面开发前，先验证固定卡片的朗读、跟读识别和完成判断流程。语音服务、语言包和离线能力依设备而异，必须覆盖目标 Android 真机。
