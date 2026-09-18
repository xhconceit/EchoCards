# 语音接口设计

状态：草稿  
接口版本：1

## 1. 目标

语音模块向 Learning Engine 提供统一能力：

- 将卡片文字朗读出来
- 通知朗读开始、完成或失败
- 识别用户跟读内容
- 返回临时和最终识别结果
- 检查设备能力和麦克风权限
- 处理停止、取消和系统音频中断

Android 系统服务的生命周期、权限和错误必须封装在语音模块内部。

本文中的 Kotlin 接口是待实现的设计契约，使用 `data class`、`sealed interface`、协程与 `Flow`。下文流程图中的 `completed`、`partial_result` 等事件名，分别对应 Kotlin 的 `Completed`、`PartialResult` 等类型。

Engine 在发起语音操作前开始收集事件流；事件流不重放上一操作的事件，并须保证当前操作的终止事件不会丢失。退出时取消收集任务并释放适配器。权限请求由 Android UI 层承接 Activity Result 流程，领域接口不持有 Activity。

## 2. 模块边界

```mermaid
flowchart LR
    Engine[Learning Engine]
    Contract[Kotlin 领域接口]
    Adapter[Android 语音适配器]
    Services[系统语音服务]

    Engine --> Contract
    Contract --> Adapter
    Adapter --> Services
```

Learning Engine 不直接调用 Android SDK API。

## 3. 文件结构

```text
app/src/main/java/.../
├── domain/speech/
│   ├── SpeechModels.kt
│   ├── SpeechPlayer.kt
│   ├── SpeechRecognizer.kt
│   └── SpeechCapabilityService.kt
└── speech/android/
    ├── AndroidSpeechPlayer.kt
    ├── AndroidSpeechRecognizer.kt
    ├── AndroidSpeechCapabilityService.kt
    ├── AudioFocusController.kt
    └── SpeechErrorMapper.kt
```

第一版由 Kotlin 适配器直接调用 Android 系统语音服务。

## 4. 设备能力

```kotlin
enum class MicrophonePermission { UNDETERMINED, GRANTED, DENIED, RESTRICTED }

data class SpeechCapabilities(
    val synthesisAvailable: Boolean,
    val recognitionAvailable: Boolean,
    val microphonePermission: MicrophonePermission,
    val supportedRecognitionLanguages: List<String>,
    val supportedSynthesisLanguages: List<String>,
    val onDeviceRecognitionAvailable: Boolean,
)
```

`RESTRICTED` 仅表示设备策略等限制；普通拒绝映射为 `DENIED`。适配器需要结合权限状态和请求历史区分首次请求与拒绝。

`onDeviceRecognitionAvailable` 表示当前设备存在端侧识别能力，不表示所有语言都可以离线识别。

提供能力检查接口：

```kotlin
interface SpeechCapabilityService {
    suspend fun getCapabilities(language: String? = null): SpeechCapabilities
    suspend fun requestMicrophonePermission(): MicrophonePermission
}
```

只有用户进入自动跟读功能后，才请求麦克风权限。

第一版语言固定为 `zh-CN`。自动播放只调用朗读和音频焦点接口，不请求麦克风权限。

## 5. 朗读接口

```kotlin
data class SpeakRequest(
    val operationId: String,
    val text: String,
    val language: String,
    val rate: Double,
)

sealed interface SpeechPlayerEvent {
    val operationId: String

    data class Started(override val operationId: String) : SpeechPlayerEvent
    data class Completed(override val operationId: String) : SpeechPlayerEvent
    data class Stopped(override val operationId: String) : SpeechPlayerEvent
    data class Error(
        override val operationId: String,
        val error: SpeechError,
    ) : SpeechPlayerEvent
}

interface SpeechPlayer {
    val events: kotlinx.coroutines.flow.Flow<SpeechPlayerEvent>
    suspend fun speak(request: SpeakRequest)
    suspend fun stop(operationId: String? = null)
    suspend fun dispose()
}
```

### 朗读规则

- `text` 去除首尾空格后不能为空。
- 开始新朗读前先停止上一条朗读。
- `speak()` 表示请求已被系统接受，不表示朗读完成。
- 只有收到 `completed` 事件才表示朗读完成。
- `stop()` 不能产生 `completed` 事件。
- 所有事件都必须携带原始 `operationId`。
- 同一个操作最多产生一次终止事件：`completed`、`stopped` 或 `error`。

手动模式的“暂停 / 继续”由 Learning Engine 将文本分段实现：暂停时调用 `stop()` 并记住当前分段，继续时从该分段开头发起新的 `SpeakRequest`。适配器不承诺在一个句子中间精确恢复。

## 6. 识别接口

```kotlin
data class RecognitionRequest(
    val operationId: String,
    val language: String,
    val partialResults: Boolean,
    val preferOnDevice: Boolean,
)

sealed interface SpeechRecognizerEvent {
    val operationId: String

    data class Ready(override val operationId: String) : SpeechRecognizerEvent
    data class SpeechStarted(override val operationId: String) : SpeechRecognizerEvent
    data class PartialResult(
        override val operationId: String,
        val transcript: String,
    ) : SpeechRecognizerEvent
    data class FinalResult(
        override val operationId: String,
        val transcript: String,
    ) : SpeechRecognizerEvent
    data class SpeechEnded(override val operationId: String) : SpeechRecognizerEvent
    data class Stopped(override val operationId: String) : SpeechRecognizerEvent
    data class Error(
        override val operationId: String,
        val error: SpeechError,
    ) : SpeechRecognizerEvent
}

interface SpeechRecognizer {
    val events: kotlinx.coroutines.flow.Flow<SpeechRecognizerEvent>
    suspend fun start(request: RecognitionRequest)
    suspend fun stop(operationId: String? = null)
    suspend fun cancel(operationId: String? = null)
    suspend fun dispose()
}
```

### `stop` 与 `cancel` 的区别

`stop()`：

- 停止继续收音
- 允许系统处理已经收到的音频
- 仍可能返回最终识别结果
- 适合用户自然读完后的结束处理

`cancel()`：

- 立即取消当前识别
- 丢弃尚未处理的结果
- 不能用后续结果更新当前学习状态
- 适合暂停、翻页、重读和退出页面

即使调用了 `cancel()`，Learning Engine 仍然必须通过 `operationId` 忽略迟到回调。

## 7. 错误格式

```kotlin
enum class SpeechErrorCode {
    PERMISSION_DENIED, RECOGNITION_UNAVAILABLE, SYNTHESIS_UNAVAILABLE,
    LANGUAGE_UNSUPPORTED, NO_SPEECH, NETWORK_REQUIRED, NETWORK_ERROR,
    AUDIO_BUSY, AUDIO_INTERRUPTED, RECOGNITION_TIMEOUT, CANCELLED, INTERNAL_ERROR,
}

data class SpeechError(
    val code: SpeechErrorCode,
    val message: String,
    val nativeCode: String?,
    val recoverable: Boolean,
)
```

原生平台错误必须先转换为统一错误码，再传给 Learning Engine。

页面不能直接展示 `nativeCode`。

## 8. 音频状态接口

```kotlin
enum class AudioRoute { SPEAKER, RECEIVER, HEADPHONES, BLUETOOTH }

sealed interface AudioInterruptionEvent {
    data class InterruptionStarted(val reason: String?) : AudioInterruptionEvent
    data class InterruptionEnded(val shouldResume: Boolean) : AudioInterruptionEvent
    data class RouteChanged(val route: AudioRoute) : AudioInterruptionEvent
}

interface AudioFocusController {
    val interruptions: kotlinx.coroutines.flow.Flow<AudioInterruptionEvent>
    suspend fun configureForPlayback()
    suspend fun configureForRecognition()
    suspend fun deactivate()
}
```

即使系统提示 `shouldResume = true`，App 也不自动开启麦克风。中断结束后保持暂停，等待用户点击继续。

## 9. 朗读与识别切换

自动跟读的音频流程：

```mermaid
sequenceDiagram
    participant Engine
    participant Audio as Android Audio Focus
    participant Player
    participant Recognizer

    Engine->>Recognizer: cancel()
    Engine->>Audio: configureForPlayback()
    Engine->>Player: speak()
    Player-->>Engine: completed
    Engine->>Player: stop()
    Engine->>Audio: configureForRecognition()
    Engine->>Recognizer: start()
    Recognizer-->>Engine: ready
    Recognizer-->>Engine: partial_result
    Recognizer-->>Engine: final_result
```

不能在系统朗读期间开启语音识别，否则可能把 App 自己的声音当作用户跟读。

朗读结束后是否需要短暂等待，由真机测试决定，不在接口中写死。

## 10. Android 实现

Android 适配器使用 Kotlin 封装：

- `android.speech.tts.TextToSpeech`
- `android.speech.SpeechRecognizer`
- `RecognitionListener`
- 系统麦克风权限
- Audio Focus 和音频中断

需要处理：

- 初始化 TextToSpeech 后再调用朗读
- 使用 utterance ID 对应 `operationId`
- 将 `onDone` 转换成 `completed`
- 将 `onPartialResults` 转换成 `partial_result`
- 将 `onResults` 转换成 `final_result`
- 检查语音识别服务是否可用
- 在主线程调用要求运行在主线程的系统 API
- 页面退出后销毁识别器和朗读资源

不能假设所有 Android 手机都有相同的语音引擎、语言包或离线识别能力。

## 11. 权限流程

自动跟读入口的权限流程：

```text
用户打开自动跟读
        ↓
检查麦克风权限和语音识别服务
        ↓
尚未决定 → 显示用途说明 → 请求权限
        ↓
    ┌───┴────┐
  已允许     已拒绝
    ↓          ↓
开始跟读    显示设置入口
             保留手动模式
```

权限说明应表达：

> 知声卡需要使用麦克风判断你是否完成跟读。第一版不会保存录音。

不能在 App 启动时无理由请求麦克风权限。

## 12. 隐私边界

第一版遵守以下规则：

- 不保存原始录音。
- 不保存完整识别文字。
- 只保存完成结果和覆盖率。
- 若系统识别需要联网，应在隐私说明中告知用户。
- 日志中不能记录用户完整跟读内容。
- 调试日志在正式构建中关闭或脱敏。
- 切换到后台后立即停止收音。

## 13. 模拟实现

为了在没有真机语音环境时开发页面，需要提供模拟实现：

```kotlin
data class SpeechServices(
    val player: SpeechPlayer,
    val recognizer: SpeechRecognizer,
    val capabilities: SpeechCapabilityService,
    val audioFocus: AudioFocusController,
)
```

开发环境可以注入：

```kotlin
val mockSpeechServices = SpeechServices(
    player = MockSpeechPlayer(),
    recognizer = MockSpeechRecognizer(),
    capabilities = MockSpeechCapabilityService(),
    audioFocus = MockAudioFocusController(),
)
```

模拟识别器应支持：

- 发送部分识别结果
- 发送最终识别结果
- 模拟无语音
- 模拟权限拒绝
- 模拟识别失败
- 模拟迟到回调
- 模拟重复完成回调

## 14. 第一版验收条件

- Android 语音实现遵守统一的 Kotlin 领域接口。
- App 朗读时不同时收音。
- 朗读完成事件可以正确关联到当前卡片。
- 部分识别结果能够实时返回。
- 暂停和翻页后，识别任务能够取消。
- 旧任务回调不能影响新卡片。
- 权限拒绝后仍可进入手动模式。
- App 进入后台后停止使用麦克风。
- 原生异常被转换为统一错误码。
- 语音模块释放后不再发送有效事件。
