# Android 第一版开发方案

状态：实施方案；数据、卡片管理、JSON 导入与学习流程开发中  
范围：Android 原生 App 第一版

## 1. 当前起点与目标

`android/` 已接入 Compose 页面、Room 数据库、JSON 导入、三种学习模式与跟读匹配。`dome.html` 是视觉与交互基准；Android 仍需完成真机验证、迁移测试与完整交互验收。

第一版交付本地卡组与卡片管理、JSON 文件导入、三种学习模式、普通话朗读与跟读、本地持久化。产品行为以[第一版需求](product/requirements.md)为准；此文档规定实现方式和模块边界，执行状态与验收用例由[开发节点与测试](development/README.md)维护。

## 2. 模块与数据流

保持单 `app` Gradle 模块，按职责分包；业务复杂度增长后再评估拆分模块。

```text
app/src/main/java/com/orange/echocards/
├── ui/          Compose 页面、导航、ViewModel、UI 状态
├── domain/      领域模型、用例、Learning Engine、跟读匹配、接口
├── data/        Room Entity、DAO、数据库、Repository 实现
├── importdata/  JSON 读取、解析、校验、规范化、重复判断
├── speech/      Android TTS、离线／系统／云端识别适配器、音频焦点适配
└── di/          依赖装配
```

调用方向为 `Compose → ViewModel → 领域用例 → Repository／语音接口`。UI 通过 `StateFlow` 接收状态，通过 ViewModel 发出命令。Composable 不直接访问 Room DAO、`TextToSpeech` 或 `SpeechRecognizer`。领域代码不依赖 Compose、Room Entity 或 Android 系统类型。

依赖版本以 `android/gradle/libs.versions.toml` 和 Gradle Wrapper 为准。实现时补入 Navigation Compose、ViewModel、协程、Room 和所需测试依赖，并锁定版本；朗读直接使用 Android SDK 的 `TextToSpeech`，跟读识别使用随包的 Vosk 离线模型（见 [ADR-002](decisions/002-offline-vosk-recognition.md)）。

## 3. 本地数据实现

依据[数据模型](reference/data-model.md)建立 `decks`、`cards`、`deck_progress`、`card_attempts` 和 `user_settings`。Room 导出 schema JSON；已发布版本之后的结构变化使用显式 Migration 和迁移测试。

- `DeckRepository` 提供卡组列表、搜索、创建、编辑、删除和详情读取。
- `CardRepository` 提供卡片增删改、排序和按卡组顺序读取。修改正文或跟读文本时增加 `revision`。
- `ProgressRepository` 保存每个卡组的当前卡片与上次模式。
- `AttemptRepository` 保存浏览与跟读完成记录。
- 设置 Repository 保存默认模式、朗读速度与自动翻页延迟。

删除卡组、更新排序、写入学习记录与位置、批量导入各自通过事务完成。数据写入失败时，页面保留当前状态并提供重试，不能呈现已成功的假象。

## 4. JSON 导入方案

文件格式见[JSON 导入格式](reference/import-format.md)。每个文件对应一个卡组；不合并或覆盖现有卡组。

### 4.1 处理流程

1. 首页通过 Activity Result API 启动 Android 系统文件选择器，读取用户选择的 `content://` URI；不申请广泛存储权限。
2. 在 IO 协程读取 UTF-8 文件，限制为 2 MiB；解析为独立的导入 DTO，不直接构造 Room Entity。
3. 校验顶层对象、必填字符串、可选字段类型、`cards` 非空且不超过 1000 张。错误标明字段或卡片序号；任一错误都终止整次导入。
4. 对原始 JSON 中有效内容做规范化并计算 SHA-256 指纹，再查询现有导入指纹和现有卡组当前内容。
5. 显示文件名、可编辑卡组名称、卡片数量及重复提示。用户确认后再次检查重复并提交，避免预览期间数据变化。
6. 在单个 Room 事务内生成新 UUID，写入卡组、按数组顺序写入所有卡片、写入指向第一张卡片的初始学习位置。任何写入失败都回滚整个导入。

### 4.2 重复判断

规范化内容按固定顺序包含：原 JSON 的卡组 `title`、`description`，以及每张卡片的 `title`、`content`、`speechText`、`memoryTip`。字符串去除首尾空格；缺失或 `null` 的可选字段与空字符串等价。卡片顺序参与判断；文件名、JSON 字段顺序、外部 ID 和未知字段不参与判断。指纹必须使用无歧义的字段编码生成，不能简单拼接字符串。

- **内容完全相同**：提示已有卡组，主操作为“查看已有卡组”；不新增数据。修改预览中的卡组名称不能绕过判断。
- **仅名称相同、内容不同**：提示将新增同名卡组，允许导入。
- **原导入卡组后来被编辑**：保留其原始导入指纹，仍能识别相同原始文件。
- **原卡组已删除**：其指纹随卡组删除，允许重新导入。

`decks.import_fingerprint` 为可空字段并建立唯一索引。手动创建的卡组指纹为 `NULL`；导入前同时比较现有卡组当前内容，以发现与手动创建卡组完全相同的数据。唯一索引负责兜底处理并发重复提交。发生唯一约束冲突时重新查询并打开已有卡组，不显示为普通保存失败。

## 5. 学习与语音实现

`LearningEngine` 按[现有设计](architecture/learning-engine.md)实现为纯 Kotlin 状态机，公开 `StateFlow<LearningEngineState>` 和 `initialize/start/pause/resume/replay/next/previous/flip/setMode/dispose` 命令。`SpeechPlayer`、识别器和数据 Repository 通过接口注入，便于在 JVM 测试状态转换。

Android 适配器把 TTS 和识别回调转换为带 `operationId` 的领域事件。暂停、切卡、重读、切模式、进入后台或退出时，先使旧 ID 失效，再停止对应语音资源；迟到事件必须被忽略。进入后台立即停止收音，回到前台保持暂停，等待用户主动继续。

- **手动学习**：左右循环切卡、点击翻面；按句子或短语分段朗读，暂停后从当前分段开头继续。
- **自动播放**：等待真实 TTS 完成事件，翻到快速记忆点，按设置停留，然后切到下一张；不使用固定朗读时长。
- **自动跟读**：朗读完成后才启动识别；内部匹配完成后先停止识别、事务保存完成记录与位置，再翻面并前进。用完成锁保证同一操作最多推进一次。

语音权限只在进入自动跟读时请求。权限拒绝、服务不可用或朗读失败时显示原因，提供重试与切换手动学习。语音回调和完整识别文字不写入持久化数据或日志。

## 6. 执行入口

开发任务、节点状态与对应测试用例见[开发节点与测试](development/README.md)。
