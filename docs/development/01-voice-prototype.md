# 节点 1：目标真机语音探针

状态：完成
返回：[开发总览](README.md)

交付：固定卡片的 TTS、识别、麦克风权限及生命周期适配。该节点先于完整页面开发。

开发清单：

- [x] 建立 `domain/speech` 事件与接口，事件携带 `operationId`。
- [x] 实现 `speech/android` 的 TTS、识别、权限与音频焦点适配器。
- [x] 用固定卡片接通“朗读→识别→完成”探针页面。
- [x] 取消或切卡时先使旧 ID 失效，再停止系统服务。
- [x] 处理后台停麦、权限拒绝和服务缺失，记录目标真机结果。

| 用例 | 操作与预期 | 状态 |
|---|---|---|
| V01 | 在目标真机启动普通话朗读；实际听到声音并收到一次完成事件。 | 通过 |
| V02 | TTS 完成前不启动识别；完成后再收音，并收到部分或最终识别结果。 | 通过 |
| V03 | 识别期间切卡或取消；随后到达的旧回调不改变当前卡片。 | 通过 |
| V04 | App 进入后台；麦克风停止使用，回到前台不自动重新收音。 | 通过 |
| V05 | 拒绝权限或设备缺少语音服务；页面说明原因且仍可进入手动模式。 | 通过 |

V01、V02 的证据是自动采集的，不依赖人耳：

- V01：`v01c` 在朗读期间轮询 `AudioManager.isMusicActive`，系统侧观察到活跃音频输出，配合 `started → completed` 事件；
  人耳听感可随时用探针页「朗读固定卡片」复核。
- V02：`v02c` 用 `TextToSpeech.synthesizeToFile` 合成测试音频，从扬声器播放让识别器收音，识别结果与卡片开头匹配：

  ```text
  fixture=惯性是物体保持原有运动状态的性质。
  transcript=惯性是物体保持原有运动
  error=null
  ```

  顺序要求由 `v02b` 与探针页「一键自检」保证：先拿到 `completed` 才启动识别。

## 代码位置

```text
app/src/main/java/com/orange/echocards/
├── domain/speech/          事件、接口、OperationGate（纯 Kotlin，可 JVM 测试）
└── speech/android/         TTS、识别、能力与权限、音频焦点适配器
app/src/debug/java/com/orange/echocards/speech/mock/
└── MockSpeechServices.kt   模拟实现（不参与 release），覆盖无语音、权限拒绝、服务缺失、迟到回调、重复完成
app/src/main/java/com/orange/echocards/ui/
├── SpeechProbePage.kt      探针页面（仅 debug 构建从「我的 → 调试 → 语音探针」进入）
└── SpeechProbeViewModel.kt
app/src/test/java/com/orange/echocards/
├── OperationGateTest.kt    旧回调失效规则
└── SpeechContractTest.kt   模拟服务与领域契约（节点 5/6 的基础）
```

`AudioFocusController` 的接口放在 `domain/speech/SpeechModels.kt`（`SpeechServices` 要引用它），
Android 实现仍在 `speech/android/AudioFocusController.kt`，与接口文档的目录约定只差这一处。

## 目标真机结果

设备 24122RKC7C（HyperOS / Android 16）：

| 项 | 结果 |
|---|---|
| TTS 引擎 | `com.xiaomi.mibrain.speech/.tts.TtsService`（`tts_default_synth` 未显式设置，系统按优先级选中） |
| 识别服务 | `com.xiaomi.mibrain.speech/.asr.AsrService`，**第三方 App 可正常使用**（`SpeechRecognizer` 能收到 `ready`） |
| 设备上没有的 | Google 语音服务（`com.google.android.tts` / Google 识别服务）；`settings get secure tts_default_synth` 为 null |
| 端侧识别 | 以 `SpeechRecognizer.isOnDeviceRecognitionAvailable` 为准，探针页会显示 |

已知平台行为：

- 撤销 `RECORD_AUDIO` 会杀掉 App 进程，所以 V05 必须在**独立的一次运行**里做，先由 adb 撤销权限再跑该用例，不能在同一进程里撤销。
- 小米识别服务把 `beginningOfSpeech` 发在 `readyForSpeech` **之前**（探针日志里是 `speechStarted` → `ready`）。上层状态机不能假设 ready 先到。
- 同一次测试运行中，如果别的用例已经授予了麦克风权限，V05 会跳过（`Assume`）而不是误报失败；
  要真正验证未授权路径，请按上面的命令先 `pm revoke` 再单独跑。
- 该设备 `isOnDeviceRecognitionAvailable` 为 false，识别走的是**在线服务**：实测会在网络抖动时报
  `ERROR_NETWORK_TIMEOUT`。错误映射把它归为可重试的 `NETWORK_ERROR`，页面需要给出重试入口；
  `v02c` 在遇到该错误时跳过（`Assume`）而不是误报失败。这条约束直接影响节点 6 的自动跟读。

## 执行命令

```bash
cd android
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home   # 工程需要 JDK 21 toolchain
export ANDROID_HOME=~/Library/Android/sdk
GRADLE="-Dhttp.nonProxyHosts=* -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true"

# V01–V04（含探针页面用例）
adb shell pm grant com.orange.echocards.debug android.permission.RECORD_AUDIO
./gradlew $GRADLE -Pandroid.testInstrumentationRunnerArguments.class=com.orange.echocards.SpeechProbeTest :app:connectedDebugAndroidTest

# V05：先撤销权限（会杀进程），再单独跑
adb shell pm revoke com.orange.echocards.debug android.permission.RECORD_AUDIO
./gradlew $GRADLE -Pandroid.testInstrumentationRunnerArguments.class=com.orange.echocards.SpeechProbeDeniedTest :app:connectedDebugAndroidTest
```

人工确认步骤（约 1 分钟）：装好 debug 包 → 「我的 → 调试 → 语音探针」→
点「一键自检」→ 听到普通话朗读后，在「2/3 请照着卡片朗读」时读出
“惯性是物体保持原有运动状态的性质” → 看是否出现「3/3 通过：识别到 N 个字符」与识别文字。
只想单独确认朗读或识别时，也可以用页面上的「朗读固定卡片」「开始识别」按钮。

## 自动测试位置

- JVM：`OperationGateTest`（旧回调失效、终止事件只发一次）、`SpeechContractTest`（模拟服务覆盖
  无语音、权限拒绝、服务缺失、识别失败、取消后迟到结果、重复完成）。
- 真机：`SpeechProbeTest`（V01–V04 + 一键自检顺序断言）、`SpeechProbeDeniedTest`（V05，需先撤销权限）。

“服务缺失”这条路径无法在目标真机上制造（系统语音服务不可卸载），由 `SpeechContractTest` 用模拟服务覆盖。

探针页的「一键自检」把朗读 → 等待 completed → 收音 → 等待最终结果串成一次操作；
`SpeechProbeTest.v02b` 用日志时间戳断言 **`listen` 一定晚于 `completed`**，
也就是 V02 的“TTS 完成前不启动识别”由自动用例兜住。房间安静时自检会诚实停在
「3/3 没有收到识别结果」，此时识别状态是「失败：没有听清，请再读一次」。
