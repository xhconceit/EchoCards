# ADR-003：增加 macOS Desktop 客户端

状态：已确认  
日期：2026-09-21  
决策人：项目团队

## 1. 背景

项目在 Android 第一版之外增加 macOS 桌面端，用于在 Mac 上管理卡片、学习内容和学习进度。ADR-001 中“仅开发 Android 原生 App”仍适用于 Android 第一版；本决策是新增桌面客户端后的补充，不改变现有 Android 工程的实现方式。

macOS 与 Android 在系统服务、窗口模型、文件路径和窗口交互上存在差异。macOS 首期不提供朗读和跟读识别，避免把移动端语音交互强行移植到桌面端。

## 2. 决策

采用 macOS 原生 SwiftUI 客户端方案：

- 保留 `android/` 作为 Android 客户端，不立即迁移现有代码。
- 新增 `mac/` 作为原生 macOS SwiftUI 客户端。
- macOS 使用 Swift、SwiftUI、Observation/适当的状态管理和 SQLite 相关实现；界面优先采用 Apple Liquid Glass 设计语言。
- 学习时以单张卡片悬浮窗显示，窗口保持置顶；使用键盘上/下方向键切换卡片。
- Android 与 macOS 各自实现 UI、ViewModel、数据层和平台能力适配器。
- 两端通过统一的数据模型、JSON 导入格式和行为规范保持兼容；暂不引入 Kotlin Multiplatform 共享代码层。
- macOS 首期优先支持 Apple Silicon；Intel 支持在构建和依赖验证后再承诺。

推荐的目标结构：

```text
android/              # 现有 Android App
mac/                  # macOS 原生 SwiftUI App
docs/
```

## 3. 跨端一致性边界

由于 macOS 使用 SwiftUI，首期不创建跨语言共享模块。两端需要保持一致的内容包括：

- 卡片、牌组、学习记录等数据模型
- JSON 导入格式、数据字段和迁移规则
- Learning Engine 的行为、评分规则和复习算法
- 跟读匹配的输入输出约定
- 错误、空状态和边界行为

各端独立实现：

- Android `Context`、Room Entity/DAO 和 Android 生命周期类型
- macOS SwiftUI、AppKit、AVFoundation 和应用生命周期类型
- `TextToSpeech`、麦克风、权限和平台音频会话实现
- 具体平台的文件路径、数据库连接和迁移执行代码

## 4. 客户端与平台适配

macOS 客户端采用 SwiftUI，使用 Liquid Glass 构建窗口、导航、工具栏、卡片容器和主要交互控件。视图通过 ViewModel/Observation 暴露的状态访问领域逻辑，不直接调用数据库或语音服务。

Liquid Glass 的使用约束：

- 优先使用 Apple 提供的 SwiftUI 原生 Material、容器和控件能力，不自行绘制一套仿制效果。
- 玻璃效果只用于窗口层级、导航、工具栏和需要强调层次的卡片容器；正文区域保持清晰、稳定的对比度。
- 文字、图标、焦点状态和键盘操作必须满足可读性与无障碍要求，不能为了透明效果牺牲信息辨识度。
- 对不支持 Liquid Glass 的 macOS 版本提供不透明或半透明 Material 降级样式，保持布局、功能和交互一致。

平台能力通过项目定义的接口隔离：

| 能力 | Android | macOS Desktop |
|---|---|---|
| UI | Jetpack Compose | SwiftUI + Liquid Glass |
| 数据存储 | Room/SQLite | 独立 SQLite 实现；共享 schema 与迁移规则 |
| 文字转语音 | Android `TextToSpeech` | 首期不提供 |
| 跟读识别 | Android Vosk 适配器 | 首期不提供 |
| 权限与生命周期 | Android API | macOS 权限、窗口和应用生命周期 |

所有语音事件继续携带 `operationId`。暂停、切换卡片、退出学习和窗口进入非活动状态后，旧操作回调必须被忽略；进入后台或失去录音权限时停止收音。

## 5. 选择该方案的原因

- SwiftUI 能提供符合 macOS 使用习惯的窗口、菜单、快捷键和系统集成。
- Liquid Glass 能提供统一的层次、材质和系统视觉语言，同时保留 SwiftUI 的原生可访问性与交互能力。
- 保留 Android 原生语音、生命周期和 Room 实现，不增加 Android 迁移风险。
- macOS 端优先提供适合桌面的悬浮卡片、置顶窗口和键盘操作。
- 两端通过明确的数据格式和行为文档保持兼容，降低跨语言耦合。
- macOS 可以先交付基础卡片和数据能力，再接入语音。

## 6. 不采用的方案

### Kotlin Multiplatform + Compose Multiplatform

首期不采用。虽然可以共享 Kotlin 领域代码，但会引入 KMP 构建链、Swift/Kotlin 边界和额外的跨语言调试成本；当前 macOS 端更需要原生 SwiftUI 体验。

### 直接复制 Android 工程

不采用。Android 和 macOS 的实现语言、系统 API 和 UI 生命周期不同，直接复制会产生难以维护的伪共享代码。

## 7. 分阶段实施

1. 创建 `mac/` Xcode 工程，使用 SwiftUI + Liquid Glass 实现应用启动、窗口、牌组列表和卡片浏览。
2. 在 Swift 中实现 macOS 领域模型、Learning Engine 和对应单元测试，并以 Android 行为作为对照。
3. 为 macOS 接入本地存储，并验证数据导入、导出和迁移。
4. 实现悬浮卡片学习窗口、置顶行为和上/下方向键切卡。
5. 增加 macOS 打包、签名、公证和 Apple Silicon 构建流程。

## 8. 验收与限制

- Android 现有构建、单元测试和真机语音验收不能因桌面端改造而回退。
- macOS 端必须在真实 Mac 环境验证窗口、文件访问、置顶窗口和键盘切卡；首期不验收 TTS 或麦克风能力。
- 首期不承诺 iOS、Windows 或 Linux 客户端。
- macOS 使用 Xcode 和 Swift Package Manager 管理依赖；Android 依赖版本继续以 Version Catalog 与 Gradle 配置为准。
- macOS 的 Liquid Glass 视觉效果必须在目标 macOS 版本和真实设备上验证，并保留兼容版本的降级样式。
