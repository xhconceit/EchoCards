# 知声卡 EchoCards

一个通过知识卡片、语音朗读和用户跟读进行学习的 Android App。

## 当前状态

项目已切换为 Android 原生开发。目前已建立 Kotlin + Jetpack Compose 工程骨架，App 入口仍为占位文本；卡片管理、本地存储和语音学习尚待实现。

[dome.html](dome.html) 是独立的交互设计原型，使用模拟数据和动画，不代表 Android 功能已经实现。需求、架构和接口设计见 [文档入口](docs/README.md)。

## 第一版目标

- 卡组管理与卡片新增、编辑、删除、排序
- 上下滑动手动学习，支持播放和停止当前卡片朗读
- 自动流程：App 朗读 → 用户跟读 → 完成判断 → 自动翻页
- 暂停、继续、重读和跳过
- 本地保存内容、学习位置和学习结果
- 普通话支持，语音不可用时保留手动学习

账号与跨设备同步、更多语言和复习计划不在第一版范围内。

## 技术方案

第一版仅支持 Android，决策见 [ADR-001](docs/decisions/001-use-native-android.md)。

- Kotlin + Jetpack Compose + Material 3
- 单 Activity + Navigation Compose
- ViewModel + StateFlow + Kotlin Coroutines
- Room（SQLite）本地存储
- Android TextToSpeech 和 SpeechRecognizer

上述为目标架构；当前工程仅接入基础 Compose 和 AndroidX 依赖，其余随功能开发引入。依赖版本以 [Version Catalog](android/gradle/libs.versions.toml) 为准。

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

也可以在 Android Studio 选择 `app` 和目标设备后运行。当前测试仍是工程模板示例，尚未覆盖业务功能。

完整页面开发前，先验证固定卡片的朗读、跟读识别和完成判断流程。语音服务、语言包和离线能力依设备而异，必须覆盖目标 Android 真机。
