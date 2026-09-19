package com.orange.echocards.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.orange.echocards.BuildConfig
import com.orange.echocards.domain.speech.MicrophonePermission
import com.orange.echocards.domain.speech.RecognitionRequest
import com.orange.echocards.domain.speech.SpeakRequest
import com.orange.echocards.domain.speech.SpeechCapabilities
import com.orange.echocards.domain.speech.SpeechPlayerEvent
import com.orange.echocards.domain.speech.SpeechRecognizerEvent
import com.orange.echocards.speech.android.AndroidSpeechServices
import com.orange.echocards.speech.cloud.OkHttpSenseVoiceClient
import com.orange.echocards.speech.cloud.SenseVoiceSpeechRecognizer
import com.orange.echocards.speech.vosk.VoskSpeechRecognizer
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 节点 1 语音探针的界面状态与动作，见 docs/development/01-voice-prototype.md。
 *
 * 探针只做三件事：朗读固定卡片、朗读完成后收音识别、取消后验证旧回调被忽略。
 * 识别文字只在内存里显示，不写数据库也不写日志（遵守 speech-api.md 第 12 节隐私边界）。
 */
class SpeechProbeViewModel(application: Application) : AndroidViewModel(application) {

    private val services = AndroidSpeechServices.create(application, permissionRequester = { awaitPermission() })
    private val player = services.player
    private val recognizer = services.recognizer
    private val capabilityService = services.capabilities
    private val senseVoice = SenseVoiceSpeechRecognizer(OkHttpSenseVoiceClient(BuildConfig.SENSEVOICE_API_KEY))
    private val vosk = VoskSpeechRecognizer(application)

    data class ProbeState(
        val capabilities: SpeechCapabilities? = null,
        val synthesis: String = "未开始",
        val recognition: String = "未开始",
        val transcript: String = "",
        val permission: MicrophonePermission = MicrophonePermission.UNDETERMINED,
        val ignoredEvents: Int = 0,
        val senseVoiceRecognition: String = "未开始",
        val senseVoiceTranscript: String = "",
        val voskRecognition: String = "未开始",
        val voskTranscript: String = "",
        val selfCheck: String = "",
        val mediaVolume: Int = -1,
        val mediaVolumeMax: Int = -1,
        val log: List<String> = emptyList(),
    )

    private val _state = MutableStateFlow(ProbeState())
    val state: StateFlow<ProbeState> = _state.asStateFlow()

    /** 由探针页面注入：发起系统权限请求。领域层不持有 Activity。 */
    var launchPermissionRequest: (() -> Unit)? = null

    private var permissionContinuation: CancellableContinuation<Boolean>? = null
    private var backgrounded = false
    private var ttsCounter = 0
    private var asrCounter = 0
    private var currentTtsOperation: String? = null
    private var currentAsrOperation: String? = null
    private var currentSenseVoiceOperation: String? = null
    private var currentVoskOperation: String? = null
    private var ttsOutcome: CompletableDeferred<Boolean>? = null
    private var asrOutcome: CompletableDeferred<String?>? = null
    private var senseVoiceCounter = 0
    private var voskCounter = 0
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    init {
        viewModelScope.launch { player.events.collect(::onPlayerEvent) }
        viewModelScope.launch { recognizer.events.collect(::onRecognizerEvent) }
        viewModelScope.launch { senseVoice.events.collect(::onSenseVoiceEvent) }
        viewModelScope.launch { vosk.events.collect(::onVoskEvent) }
        refreshCapabilities()
    }

    /** 朗读类功能必须能看出“音量是不是太低”，否则用户只会觉得没声音。 */
    private fun refreshMediaVolume() {
        val audio = getApplication<Application>().getSystemService(android.content.Context.AUDIO_SERVICE)
            as android.media.AudioManager
        _state.update {
            it.copy(
                mediaVolume = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC),
                mediaVolumeMax = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC),
            )
        }
    }

    fun refreshCapabilities() {
        refreshMediaVolume()
        viewModelScope.launch {
            val capabilities = capabilityService.getCapabilities(LANGUAGE)
            _state.update { it.copy(capabilities = capabilities, permission = capabilities.microphonePermission) }
            log("能力刷新：朗读=${capabilities.synthesisAvailable} 识别=${capabilities.recognitionAvailable} " +
                "端侧=${capabilities.onDeviceRecognitionAvailable} 音量=${_state.value.mediaVolume}/${_state.value.mediaVolumeMax}")
        }
    }

    /** V01：朗读固定卡片，等 Completed 事件。 */
    fun speakFixedCard() {
        refreshMediaVolume()
        val operationId = "probe-tts-${++ttsCounter}"
        currentTtsOperation = operationId
        _state.update { it.copy(synthesis = "请求中…") }
        log("speak  $operationId")
        viewModelScope.launch {
            player.speak(SpeakRequest(operationId, FIXED_CARD_TEXT, LANGUAGE, rate = 1.0))
        }
    }

    /**
     * 一键自检：朗读 → 等 completed → 再收音 → 等最终结果。
     *
     * 顺序由代码保证：**收到 completed 之前不会启动识别**（docs/reference/speech-api.md 第 9 节），
     * 所以这也是 V02 的可断言部分。
     */
    fun runSelfCheck() {
        refreshMediaVolume()
        viewModelScope.launch {
            _state.update { it.copy(selfCheck = "1/3 朗读中…") }
            val speech = CompletableDeferred<Boolean>()
            ttsOutcome = speech
            speakFixedCard()
            val spoken = withTimeoutOrNull(SELF_CHECK_TIMEOUT_MS) { speech.await() } ?: false
            if (!spoken) {
                _state.update { it.copy(selfCheck = "1/3 朗读未完成：检查设备语音服务") }
                return@launch
            }
            _state.update { it.copy(selfCheck = "2/3 请照着卡片朗读") }
            delay(SELF_CHECK_GAP_MS)
            val recognition = CompletableDeferred<String?>()
            asrOutcome = recognition
            startRecognition()
            val transcript = withTimeoutOrNull(SELF_CHECK_TIMEOUT_MS) { recognition.await() }
            _state.update {
                it.copy(selfCheck = when {
                    transcript == null -> "3/3 没有收到识别结果"
                    transcript.isEmpty() -> "3/3 收到了空结果"
                    else -> "3/3 通过：识别到 ${transcript.length} 个字符"
                })
            }
        }
    }

    fun stopSpeaking() {
        val operationId = currentTtsOperation ?: return
        log("stop   $operationId")
        viewModelScope.launch { player.stop(operationId) }
    }

    /** V02：朗读完成后才收音，收到部分与最终识别结果。 */
    fun startRecognition() {
        val operationId = "probe-asr-${++asrCounter}"
        currentAsrOperation = operationId
        _state.update { it.copy(recognition = "启动中…", transcript = "") }
        log("listen $operationId")
        viewModelScope.launch {
            recognizer.start(RecognitionRequest(operationId, LANGUAGE, partialResults = true, preferOnDevice = false))
        }
    }

    fun stopRecognition() {
        val operationId = currentAsrOperation ?: return
        log("stopListening $operationId")
        viewModelScope.launch { recognizer.stop(operationId) }
    }

    /** V03：取消后到达的旧回调必须被忽略。 */
    fun cancelRecognition() {
        val operationId = currentAsrOperation ?: return
        log("cancel $operationId")
        viewModelScope.launch { recognizer.cancel(operationId) }
    }

    /** SenseVoice 云端识别：录音 → 静音检测 → 上传转写。 */
    fun startSenseVoiceRecognition() {
        val operationId = "cloud-asr-${++senseVoiceCounter}"
        currentSenseVoiceOperation = operationId
        _state.update { it.copy(senseVoiceRecognition = "录音中…", senseVoiceTranscript = "") }
        log("senseVoice listen $operationId")
        viewModelScope.launch {
            senseVoice.start(RecognitionRequest(operationId, LANGUAGE, partialResults = false, preferOnDevice = false))
        }
    }

    fun stopSenseVoiceRecognition() {
        val operationId = currentSenseVoiceOperation ?: return
        log("senseVoice stop $operationId")
        viewModelScope.launch { senseVoice.stop(operationId) }
    }

    fun cancelSenseVoiceRecognition() {
        val operationId = currentSenseVoiceOperation ?: return
        log("senseVoice cancel $operationId")
        viewModelScope.launch { senseVoice.cancel(operationId) }
    }

    /** Vosk 离线识别：实时部分结果 + 端点后最终结果，不联网。 */
    fun startVoskRecognition() {
        val operationId = "vosk-asr-${++voskCounter}"
        currentVoskOperation = operationId
        _state.update { it.copy(voskRecognition = "启动中…", voskTranscript = "") }
        log("vosk listen $operationId")
        viewModelScope.launch {
            vosk.start(RecognitionRequest(operationId, LANGUAGE, partialResults = true, preferOnDevice = true))
        }
    }

    fun stopVoskRecognition() {
        val operationId = currentVoskOperation ?: return
        log("vosk stop $operationId")
        viewModelScope.launch { vosk.stop(operationId) }
    }

    fun cancelVoskRecognition() {
        val operationId = currentVoskOperation ?: return
        log("vosk cancel $operationId")
        viewModelScope.launch { vosk.cancel(operationId) }
    }

    fun requestMicrophonePermission() {
        viewModelScope.launch {
            val result = capabilityService.requestMicrophonePermission()
            _state.update { it.copy(permission = result) }
            log("权限结果：$result")
        }
    }

    /** V04：进入后台立即停止收音，回到前台保持暂停。 */
    fun onEnterBackground() {
        backgrounded = true
        log("进入后台：取消收音")
        cancelRecognition()
    }

    fun onReturnForeground() {
        if (!backgrounded) return
        backgrounded = false
        log("回到前台：保持暂停，不自动收音")
    }

    fun onPermissionResult(granted: Boolean) {
        val continuation = permissionContinuation ?: return
        permissionContinuation = null
        if (continuation.isActive) continuation.resume(granted)
    }

    private suspend fun awaitPermission(): Boolean {
        launchPermissionRequest?.invoke() ?: return false
        return suspendCancellableCoroutine { continuation ->
            permissionContinuation = continuation
            continuation.invokeOnCancellation { permissionContinuation = null }
        }
    }

    private fun onPlayerEvent(event: SpeechPlayerEvent) {
        when (event) {
            is SpeechPlayerEvent.Started -> {
                _state.update { it.copy(synthesis = "朗读中") }
                log("→ started   ${event.operationId}")
            }
            is SpeechPlayerEvent.Completed -> {
                _state.update { it.copy(synthesis = "已完成（收到 completed）") }
                log("→ completed ${event.operationId}")
                ttsOutcome?.complete(true)
            }
            is SpeechPlayerEvent.Stopped -> {
                _state.update { it.copy(synthesis = "已停止") }
                log("→ stopped   ${event.operationId}")
                ttsOutcome?.complete(false)
            }
            is SpeechPlayerEvent.Error -> {
                _state.update { it.copy(synthesis = "失败：${event.error.message}") }
                log("→ error     ${event.operationId} ${event.error.code}")
                ttsOutcome?.complete(false)
            }
        }
        refreshIgnored()
    }

    private fun onRecognizerEvent(event: SpeechRecognizerEvent) {
        when (event) {
            is SpeechRecognizerEvent.Ready -> {
                _state.update { it.copy(recognition = "已就绪，等待说话") }
                log("→ ready     ${event.operationId}")
            }
            is SpeechRecognizerEvent.SpeechStarted -> {
                _state.update { it.copy(recognition = "收音中") }
                log("→ speechStarted ${event.operationId}")
            }
            is SpeechRecognizerEvent.PartialResult -> {
                _state.update { it.copy(recognition = "部分结果", transcript = event.transcript) }
                log("→ partial   ${event.operationId} ${preview(event.transcript)}")
            }
            is SpeechRecognizerEvent.FinalResult -> {
                _state.update { it.copy(recognition = "已完成（最终结果）", transcript = event.transcript) }
                log("→ final     ${event.operationId} ${preview(event.transcript)}")
                asrOutcome?.complete(event.transcript)
            }
            is SpeechRecognizerEvent.SpeechEnded -> log("→ speechEnded ${event.operationId}")
            is SpeechRecognizerEvent.Stopped -> {
                _state.update { it.copy(recognition = "已取消") }
                log("→ stopped   ${event.operationId}")
            }
            is SpeechRecognizerEvent.Error -> {
                _state.update { it.copy(recognition = "失败：${event.error.message}") }
                log("→ error     ${event.operationId} ${event.error.code}")
                asrOutcome?.complete(null)
            }
        }
        refreshIgnored()
    }

    private fun onSenseVoiceEvent(event: SpeechRecognizerEvent) {
        when (event) {
            is SpeechRecognizerEvent.Ready -> {
                _state.update { it.copy(senseVoiceRecognition = "已就绪，等待说话") }
                log("→ sv ready     ${event.operationId}")
            }
            is SpeechRecognizerEvent.SpeechStarted -> {
                _state.update { it.copy(senseVoiceRecognition = "收音中") }
                log("→ sv speechStarted ${event.operationId}")
            }
            is SpeechRecognizerEvent.SpeechEnded -> {
                _state.update { it.copy(senseVoiceRecognition = "上传转写中…") }
                log("→ sv speechEnded ${event.operationId}")
            }
            is SpeechRecognizerEvent.FinalResult -> {
                _state.update { it.copy(senseVoiceRecognition = "已完成（最终结果）", senseVoiceTranscript = event.transcript) }
                log("→ sv final     ${event.operationId} ${preview(event.transcript)}")
            }
            is SpeechRecognizerEvent.Stopped -> {
                _state.update { it.copy(senseVoiceRecognition = "已取消") }
                log("→ sv stopped   ${event.operationId}")
            }
            is SpeechRecognizerEvent.Error -> {
                _state.update { it.copy(senseVoiceRecognition = "失败：${event.error.message}") }
                log("→ sv error     ${event.operationId} ${event.error.code}")
            }
            is SpeechRecognizerEvent.PartialResult -> Unit
        }
        refreshIgnored()
    }

    private fun onVoskEvent(event: SpeechRecognizerEvent) {
        when (event) {
            is SpeechRecognizerEvent.Ready -> {
                _state.update { it.copy(voskRecognition = "已就绪，等待说话") }
                log("→ vk ready     ${event.operationId}")
            }
            is SpeechRecognizerEvent.SpeechStarted -> {
                _state.update { it.copy(voskRecognition = "收音中") }
                log("→ vk speechStarted ${event.operationId}")
            }
            is SpeechRecognizerEvent.PartialResult -> {
                _state.update { it.copy(voskRecognition = "部分结果", voskTranscript = event.transcript) }
                log("→ vk partial   ${event.operationId} ${preview(event.transcript)}")
            }
            is SpeechRecognizerEvent.FinalResult -> {
                _state.update { it.copy(voskRecognition = "已完成（最终结果）", voskTranscript = event.transcript) }
                log("→ vk final     ${event.operationId} ${preview(event.transcript)}")
            }
            is SpeechRecognizerEvent.Stopped -> {
                _state.update { it.copy(voskRecognition = "已取消") }
                log("→ vk stopped   ${event.operationId}")
            }
            is SpeechRecognizerEvent.Error -> {
                _state.update { it.copy(voskRecognition = "失败：${event.error.message}") }
                log("→ vk error     ${event.operationId} ${event.error.code}")
            }
            is SpeechRecognizerEvent.SpeechEnded -> Unit
        }
        refreshIgnored()
    }

    /** 日志只留片段，避免把完整跟读内容写进任何地方。 */
    private fun preview(transcript: String): String =
        if (transcript.length <= 8) transcript else transcript.take(8) + "…(" + transcript.length + ")"

    private fun refreshIgnored() {
        _state.update { it.copy(ignoredEvents = ignoredCount()) }
    }

    private fun ignoredCount(): Int {
        val playerIgnored = (player as? com.orange.echocards.speech.android.AndroidSpeechPlayer)?.ignoredEvents ?: 0
        val recognizerIgnored = (recognizer as? com.orange.echocards.speech.android.AndroidSpeechRecognizer)?.ignoredEvents ?: 0
        val senseVoiceIgnored = senseVoice.ignoredEvents
        val voskIgnored = vosk.ignoredEvents
        return playerIgnored + recognizerIgnored + senseVoiceIgnored + voskIgnored
    }

    private fun log(line: String) {
        val entry = "${clock.format(Date())}  $line"
        _state.update { it.copy(log = (it.log + entry).takeLast(MAX_LOG)) }
    }

    override fun onCleared() {
        viewModelScope.launch {
            vosk.dispose()
            senseVoice.dispose()
            recognizer.dispose()
            player.dispose()
            services.audioFocus.deactivate()
        }
        super.onCleared()
    }

    private companion object {
        const val LANGUAGE = "zh-CN"
        const val FIXED_CARD_TEXT = "惯性是物体保持原有运动状态的性质。"
        const val MAX_LOG = 40
        const val SELF_CHECK_TIMEOUT_MS = 20_000L
        const val SELF_CHECK_GAP_MS = 1_200L
    }
}
