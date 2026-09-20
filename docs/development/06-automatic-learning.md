# 节点 6：自动播放与自动跟读

状态：已完成（2026-09-20 在目标真机 24122RKC7C / Android 16 跑通设备用例）  
返回：[开发总览](README.md)

交付：Learning Engine 状态机、真实语音事件驱动、跟读匹配和异常恢复。

开发清单：

- [x] 实现纯 Kotlin `LearningEngine` 状态机及其 `StateFlow`。
- [x] 接入自动播放的真实 TTS 完成事件、翻面和下一张流程。
- [x] 接入自动跟读的识别事件、匹配判断和事务保存。
- [x] 实现完成锁、`operationId` 失效与重复回调保护。
- [x] 实现暂停、重读、切模式、后台与音频中断恢复路径。
- [x] 实现权限、语音服务和保存失败时的可重试 UI 状态。

| 用例 | 操作与预期 | 状态 |
|---|---|---|
| A01 | 自动播放在 TTS 完成前保持正面；完成后翻面，停留后进入下一张。 | JVM 通过（`LearningEngineFollowAlongTest.a01`、`LearningEngineTest.autoPlayShowsMemoryTipBeforeEveryAdvance`）；真机通过（`StudySpeechTest.autoPlayAdvancesWithoutUserInput`，播放期麦克风峰值 > 800，无需用户操作即翻面并前进） |
| A02 | 自动跟读在朗读期间不收音；朗读完成后提示用户跟读并开启识别。 | JVM 通过（`a02`）；真机通过（`StudySpeechTest.followAlongListensAfterSpeechThenStaysPausedInBackground`：仅在朗读结束后才出现“轮到你读了”） |
| A03 | 用户只读开头、漏读结尾或重复部分内容；不自动判定完成。完整读完才前进一张。 | JVM 通过（`a03_*`、`V1TextMatcherTest`、`CompletionTrackerTest`）；纯 Kotlin 确定性规则，按[测试策略](README.md)由 JVM 覆盖 |
| A04 | 相同完成回调到达两次；只保存一条完成记录、只前进一张。 | JVM 通过（`a04`）；完成锁为确定性规则，同上由 JVM 覆盖 |
| A05 | 暂停、重读、切卡或切模式后收到旧 `operationId` 回调；当前状态不受影响。 | JVM 通过（`a05_*`、`OperationGateTest`）；真机部分覆盖（`StudySpeechTest.switchingCardStopsSpeech`：切卡立即停朗读并回到可朗读状态；`SpeechProbeTest.v03_cancelDropsLateCallbacks`：取消后旧回调被丢弃） |
| A06 | 跟读完成记录保存失败；不翻面、不前进，显示可重试错误。 | JVM 通过（`a06`：错误态与不前进）；设备通过（`AttemptTransactionTest.failedSaveLeavesNoPartialData`：外键失败整体回滚，记录与位置都不留） |
| A07 | 后台、音频中断或退出；停止朗读和收音，返回前台保持暂停。 | JVM 通过（`a07_*`）；真机通过（`StudySpeechTest.followAlongListensAfterSpeechThenStaysPausedInBackground`：退后台暂停、回前台保持暂停且不重开收音；`SpeechProbeTest.v04_backgroundStopsRecognition`） |

2026-09-20 在目标真机 24122RKC7C（Xiaomi，Android 16）执行 `./gradlew :app:connectedDebugAndroidTest`，
19 个设备用例全部通过。三种模式另经 `StudySpeechTest` 走查。

首次执行时 A07 的用例失败：离线模型解压把每个文件都建成了空目录，识别器抛"离线模型加载失败"
（原因与修法见"实现说明"）。该问题对 JVM 用例完全不可见，只有真机跑得出来。

## 代码位置

```text
app/src/main/java/com/orange/echocards/
├── domain/learning/DefaultLearningEngine.kt     三种模式的状态机与完成流程
├── domain/learning/LearningModels.kt            状态、学习记录与 Repository 接口
├── domain/learning/matching/                    跟读匹配（纯 Kotlin，可 JVM 测试）
│   ├── MatchingConfig.kt                        阈值、窗口、静音与重启上限（唯一调参入口）
│   ├── TextNormalizer.kt / V1TextMatcher.kt     文本标准化与顺序匹配
│   └── CompletionTracker.kt                     已确认进度、稳定性与完成判定
├── data/EchoRepository.kt                       saveLearningTransaction：记录与位置同事务
└── speech/vosk/                                 Vosk 端侧离线识别（见 ADR-002）
```

## 实现说明

- **识别通道**：学习页的跟读用 Vosk 离线模型（[ADR-002](../decisions/002-offline-vosk-recognition.md)），
  朗读仍用系统 TTS；探针页保留系统识别器与 SenseVoice 用于对比。
- **离线模型解压**：`VoskModelProvider` 把 `assets/model-cn/`（约 65 MiB）解压到 `filesDir`。
  区分文件与目录必须用 `assets.open()` 试探并捕获 `FileNotFoundException`——`assets.list()`
  对文件路径返回的是空数组而不是 `null`，用它判断会把每个文件都建成空目录。
  解压先写 `model-cn.tmp` 再改名，完成标记校验 `am/final.mdl` 是非空文件，
  避免半成品被当成已解压而永久缓存。
- **匹配规则与偏差**：见[跟读匹配设计](../architecture/speech-matching.md)第 20 节。
- **完成顺序**：完成锁 → 停收音 → 一次事务（`read_completed` + 下一张位置）→ 翻面 → 停留 → 切卡；
  事务失败保留当前卡片，`retry()` 补做。
- **时间参数**：`MatchingConfig` 的默认值来自设计文档，真机调优只改这一处。
- **手动模式的记录**：左滑记一条 `viewed`，右滑不记录（`learning-engine.md` 第 4 节）。

## 自动测试位置

- JVM：`CompletionTrackerTest`、`V1TextMatcherTest`、`ZhTextNormalizerTest`、
  `LearningEngineFollowAlongTest`（A01–A07）、`LearningEngineTest`。
- 设备：`StudySpeechTest`（自动播放自动前进、跟读在朗读结束后才开始收音、退回桌面进入暂停且回前台保持暂停）、
  `AttemptTransactionTest`（学习记录与位置的事务原子性）。

设备用例里"麦克风已被放开"这一点无法从页面层观察（`AudioRecordingConfiguration`
的包名是隐藏 API），因此它由 JVM 用例断言 `recognizer.cancel()` 被调用，
设备用例只记录后台期间的麦克风峰值作为佐证，不作为判定条件。

同理，"退到后台后已暂停"也不能在后台断言：页面不在前台时 Compose 不再产出帧，
语义树停在最后一帧。`StudySpeechTest` 因此只等 `ON_STOP` 派发，再回到前台断言
"仍是暂停"且没有重新出现"轮到你读了"。
