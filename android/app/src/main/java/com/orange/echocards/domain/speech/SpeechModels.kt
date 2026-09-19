package com.orange.echocards.domain.speech

import kotlinx.coroutines.flow.Flow

/**
 * 语音领域模型与事件，见 docs/reference/speech-api.md。
 *
 * 这一层是纯 Kotlin：不依赖 Android SDK、Compose 或 Room，方便在 JVM 上测试。
 * Android 系统服务的生命周期、权限和错误封装在 speech/android 适配器里。
 */

enum class MicrophonePermission { UNDETERMINED, GRANTED, DENIED, RESTRICTED }

data class SpeechCapabilities(
    val synthesisAvailable: Boolean,
    val recognitionAvailable: Boolean,
    val microphonePermission: MicrophonePermission,
    val supportedRecognitionLanguages: List<String>,
    val supportedSynthesisLanguages: List<String>,
    val onDeviceRecognitionAvailable: Boolean,
)

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
    data class Error(override val operationId: String, val error: SpeechError) : SpeechPlayerEvent
}

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
    data class PartialResult(override val operationId: String, val transcript: String) : SpeechRecognizerEvent
    data class FinalResult(override val operationId: String, val transcript: String) : SpeechRecognizerEvent
    data class SpeechEnded(override val operationId: String) : SpeechRecognizerEvent
    data class Stopped(override val operationId: String) : SpeechRecognizerEvent
    data class Error(override val operationId: String, val error: SpeechError) : SpeechRecognizerEvent
}

enum class SpeechErrorCode {
    PERMISSION_DENIED,
    RECOGNITION_UNAVAILABLE,
    SYNTHESIS_UNAVAILABLE,
    LANGUAGE_UNSUPPORTED,
    NO_SPEECH,
    NETWORK_REQUIRED,
    NETWORK_ERROR,
    AUDIO_BUSY,
    AUDIO_INTERRUPTED,
    RECOGNITION_TIMEOUT,
    CANCELLED,
    INTERNAL_ERROR,
}

/** nativeCode 只用于排查，页面不能直接展示。 */
data class SpeechError(
    val code: SpeechErrorCode,
    val message: String,
    val nativeCode: String? = null,
    val recoverable: Boolean = false,
)

enum class AudioRoute { SPEAKER, RECEIVER, HEADPHONES, BLUETOOTH }

sealed interface AudioInterruptionEvent {
    data class InterruptionStarted(val reason: String?) : AudioInterruptionEvent
    data class InterruptionEnded(val shouldResume: Boolean) : AudioInterruptionEvent
    data class RouteChanged(val route: AudioRoute) : AudioInterruptionEvent
}

/**
 * 音频焦点与中断。接口放在领域层，Android 实现见 speech/android/AudioFocusController.kt。
 * 即使系统提示 shouldResume = true，App 也不自动开启麦克风。
 */
interface AudioFocusController {
    val interruptions: Flow<AudioInterruptionEvent>
    suspend fun configureForPlayback()
    suspend fun configureForRecognition()
    suspend fun deactivate()
}

data class SpeechServices(
    val player: SpeechPlayer,
    val recognizer: SpeechRecognizer,
    val capabilities: SpeechCapabilityService,
    val audioFocus: AudioFocusController,
)
