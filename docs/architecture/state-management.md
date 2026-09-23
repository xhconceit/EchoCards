# 状态管理与分层架构

状态：已确认

## 1. 结论

Redux/MVI 与清洁架构（Clean Architecture）解决不同层面的问题，可以组合使用：

- 清洁架构负责整个应用的分层、依赖方向和平台能力隔离。
- Redux/MVI 负责单个复杂功能内部的状态变化和事件流。
- 普通页面使用 MVVM，不要求所有界面接入全局 Store。
- 学习与语音流程采用局部的单向数据流，以便表达状态机并测试异步回调。

知声卡不采用覆盖整个应用的全局 Redux Store，也不引入 VIPER。macOS 端暂不要求引入 TCA；如果自行维护 Reducer 的成本明显增加，再单独评估 TCA。

## 2. 两者的职责

### 2.1 清洁架构

清洁架构约束代码放在哪一层，以及依赖可以指向哪里：

```text
App → Features → Domain
       Data ──────↑
       Platform ──↑
```

- `App`：应用入口、导航和依赖组装。
- `Features`：SwiftUI View、ViewModel，以及复杂功能的 State、Action 和 Reducer。
- `Domain`：领域模型、业务规则、用例和 Repository/Service 协议；不依赖 SwiftUI、SQLite 或平台语音 API。
- `Data`：SQLite、JSON 导入和 Repository 实现。
- `Platform`：TTS、语音识别、文件、权限和窗口等系统能力实现。

协议定义在 `Domain`，具体实现位于外层，最终由 `App` 注入。例如 `Domain` 可以定义 `DeckRepository`，但不能知道它由 SQLite 实现。

### 2.2 Redux/MVI

Redux/MVI 约束一个功能运行时的状态如何变化：

```text
State → View → Action → Reducer → New State
  ↑                                  │
  └──────────────────────────────────┘
```

- `State`：当前功能可观察的业务状态。
- `Action`：用户操作、生命周期事件或异步结果。
- `Reducer`：根据当前状态和 Action 决定新状态及需要执行的 Effect。
- `Effect`：数据库、计时器、朗读和识别等异步工作。

Reducer 属于 `Features` 层，通过 `Domain` 协议调用能力，不直接执行 SQL，也不直接依赖具体的语音适配器。

## 3. 在知声卡中的使用范围

普通 CRUD 和设置页面使用 MVVM：

```text
SwiftUI View → ViewModel → Domain 协议 → Data/Platform 实现
```

学习与语音流程使用局部 Redux/MVI：

```text
StudyView
  ↓ StudyAction
StudyReducer / StudyStore
  ↓ Domain 协议
Learning Engine / Repository / SpeechPlayer / SpeechRecognizer
```

适合进入 `StudyState` 的内容包括：

- 当前卡片和索引
- 学习模式与学习阶段
- 正反面状态
- 当前 `operationId`
- 朗读、识别和匹配进度
- 可恢复的业务错误

悬停、焦点、临时动画等纯视觉状态保留在 SwiftUI View 中，不进入 Store。State 和 Action 不使用 `Color`、`Alert`、具体按钮名称等 SwiftUI 表现类型，避免业务状态依赖界面。

## 4. 与 TCA、VIPER 的关系

- **TCA** 是 Swift 生态中对单向数据流的完整框架实现，提供 Store、Reducer、Effect、依赖注入和测试工具。当前先采用局部 Redux/MVI 思想，不因架构选择而立即增加第三方框架。
- **VIPER** 将 View、Interactor、Presenter、Entity 和 Router 细分，适合部分大型 UIKit 工程，但对当前 SwiftUI 客户端会带来较多样板代码，因此不采用。
- **Redux/MVI** 在本项目中是状态管理方式，不是整个应用的分层方案，也不要求使用 React 或 JavaScript。

## 5. 依赖与测试约束

- View 只读取状态并发送意图或事件，不直接访问 SQLite、TTS 或识别器。
- Reducer 的状态转换应能在不启动 SwiftUI 和平台服务的情况下测试。
- 异步结果必须转换成 Action 后再改变 State。
- 语音相关 Action 必须携带 `operationId`；Reducer 忽略不属于当前操作的回调。
- Data 和 Platform 实现通过 Domain 协议替换为测试桩。
- 不建立包含所有页面状态的巨型 `AppStore`；每个复杂 Feature 拥有自己的 State 和 Store。

## 6. macOS 模块边界

目标模块可以逐步拆分为：

```text
EchoCardsDomain
EchoCardsData
EchoCardsSpeech
EchoCardsDesignSystem
EchoCardsFeatures
EchoCardsMacApp
```

模块化通过 Swift Package Manager 的 Target 落实。目录分层只是代码组织，只有独立 Target 才形成编译和依赖边界。当前 macOS 工程仍是单一业务 Target，拆分应渐进进行，不为简单页面提前增加不必要的协议和 Use Case。
