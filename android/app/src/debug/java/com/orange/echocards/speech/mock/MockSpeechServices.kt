package com.orange.echocards.speech.mock

import com.orange.echocards.domain.speech.AudioFocusController
import com.orange.echocards.domain.speech.AudioInterruptionEvent
import com.orange.echocards.domain.speech.MicrophonePermission
import com.orange.echocards.domain.speech.OperationGate
import com.orange.echocards.domain.speech.RecognitionRequest
import com.orange.echocards.domain.speech.SpeakRequest
import com.orange.echocards.domain.speech.SpeechCapabilities
import com.orange.echocards.domain.speech.SpeechCapabilityService
import com.orange.echocards.domain.speech.SpeechError
import com.orange.echocards.domain.speech.SpeechErrorCode
import com.orange.echocards.domain.speech.SpeechPlayer
import com.orange.echocards.domain.speech.SpeechPlayerEvent
import com.orange.echocards.domain.speech.SpeechRecognizer
import com.orange.echocards.domain.speech.SpeechRecognizerEvent
import com.orange.echocards.domain.speech.SpeechServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * 模拟语音服务，见 docs/reference/speech-api.md 第 13 节。
 *
 * 这些实现**故意模拟"不守规矩"的平台**：会发出取消后的迟到回调、重复的完成事件、
 * 无语音、权限拒绝和服务缺失，用来验证上层（Learning Engine 与页面）是否正确过滤。
 * 放在 debug 源集：不参与 release 包，JVM 单测与 debug 页面都能用。
 */
object MockSpeechServices {

    fun create(scope: CoroutineScope, scenario: MockScenario = MockScenario.HappyPath): SpeechServices = SpeechServices(
        player = MockSpeechPlayer(scope),
        recognizer = MockSpeechRecognizer(scope, scenario),
        capabilities = MockSpeechCapabilityService(scenario),
        audioFocus = MockAudioFocusController(),
    )
}

/** 模拟场景；对应 speech-api.md 第 13 节要求覆盖的失败与异常。 */
enum class MockScenario {
    /** 朗读完成、识别返回部分结果与最终结果。 */
    HappyPath,

    /** 识别没有听到人声。 */
    NoSpeech,

    /** 麦克风权限被拒绝。 */
    PermissionDenied,

    /** 设备缺少语音服务。 */
    ServiceUnavailable,

    /** 识别过程中服务报错。 */
    RecognitionFailure,

    /** 取消之后平台仍然送来迟到的最终结果。 */
    LateResultAfterCancel,
}

class MockSpeechPlayer(
    private val scope: CoroutineScope,
    private val speakDurationMs: Long = 40,
) : SpeechPlayer {

    private val _events = MutableSharedFlow<SpeechPlayerEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: Flow<SpeechPlayerEvent> = _events.asSharedFlow()
    private val gate = OperationGate()

    /** 记录收到的朗读请求，便于断言分段与顺序。 */
    val requests = mutableListOf<SpeakRequest>()

    /** 设置后朗读直接失败。 */
    var failWith: SpeechError? = null

    /** 模拟引擎重复回调：完成事件发两次。 */
    var duplicateCompletion = false

    /** 模拟 stop() 之后平台仍然回调完成。 */
    var lateCompletionAfterStop = false

    private var speaking: Job? = null

    /** 与真实 TTS 一致：接受请求后立即返回，完成事件稍后通过 events 回调。 */
    override suspend fun speak(request: SpeakRequest) {
        requests += request
        gate.begin(request.operationId)
        _events.emit(SpeechPlayerEvent.Started(request.operationId))
        failWith?.let { error ->
            if (gate.acceptTerminal(request.operationId)) {
                _events.emit(SpeechPlayerEvent.Error(request.operationId, error))
            }
            return
        }
        speaking?.cancel()
        speaking = scope.launch {
            if (speakDurationMs > 0) delay(speakDurationMs)
            if (gate.acceptTerminal(request.operationId)) {
                _events.emit(SpeechPlayerEvent.Completed(request.operationId))
            }
            if (duplicateCompletion && gate.acceptTerminal(request.operationId)) {
                _events.emit(SpeechPlayerEvent.Completed(request.operationId))
            }
        }
    }

    override suspend fun stop(operationId: String?) {
        val current = gate.activeOperationId ?: return
        if (operationId != null && operationId != current) return
        speaking?.cancel()
        if (gate.acceptTerminal(current)) _events.emit(SpeechPlayerEvent.Stopped(current))
        if (lateCompletionAfterStop) {
            scope.launch {
                delay(20)
                // 平台不守规矩：stop 之后仍然报完成
                _events.emit(SpeechPlayerEvent.Completed(current))
            }
        }
    }

    override suspend fun dispose() {
        speaking?.cancel()
        gate.invalidate()
    }
}

class MockSpeechRecognizer(
    private val scope: CoroutineScope,
    private val scenario: MockScenario = MockScenario.HappyPath,
    private val stepDelayMs: Long = 30,
) : SpeechRecognizer {

    private val _events = MutableSharedFlow<SpeechRecognizerEvent>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: Flow<SpeechRecognizerEvent> = _events.asSharedFlow()
    private val gate = OperationGate()

    /** 模拟部分识别结果，逐条发出。 */
    var partials: List<String> = listOf("惯性", "惯性是物体")
    var finalTranscript: String = "惯性是物体保持原有运动状态的性质"

    private var session: Job? = null

    /** 与真实识别器一致：start() 只发起会话，事件异步回调。 */
    override suspend fun start(request: RecognitionRequest) {
        gate.begin(request.operationId)
        session?.cancel()
        session = scope.launch { runScenario(request) }
    }

    private suspend fun runScenario(request: RecognitionRequest) {
        when (scenario) {
            MockScenario.PermissionDenied -> {
                _events.emit(SpeechRecognizerEvent.Error(request.operationId,
                    SpeechError(SpeechErrorCode.PERMISSION_DENIED, "没有麦克风权限")))
                return
            }
            MockScenario.ServiceUnavailable -> {
                _events.emit(SpeechRecognizerEvent.Error(request.operationId,
                    SpeechError(SpeechErrorCode.RECOGNITION_UNAVAILABLE, "设备没有可用的语音识别服务")))
                return
            }
            else -> Unit
        }
        _events.emit(SpeechRecognizerEvent.Ready(request.operationId))
        delay(stepDelayMs)
        _events.emit(SpeechRecognizerEvent.SpeechStarted(request.operationId))
        when (scenario) {
            MockScenario.NoSpeech -> {
                delay(stepDelayMs)
                _events.emit(SpeechRecognizerEvent.SpeechEnded(request.operationId))
                if (gate.acceptTerminal(request.operationId)) {
                    _events.emit(SpeechRecognizerEvent.Error(request.operationId,
                        SpeechError(SpeechErrorCode.NO_SPEECH, "没有听清，请再读一次", recoverable = true)))
                }
            }
            MockScenario.RecognitionFailure -> {
                delay(stepDelayMs)
                if (gate.acceptTerminal(request.operationId)) {
                    _events.emit(SpeechRecognizerEvent.Error(request.operationId,
                        SpeechError(SpeechErrorCode.INTERNAL_ERROR, "识别服务返回错误", recoverable = true)))
                }
            }
            MockScenario.LateResultAfterCancel -> {
                // 结果要等到取消之后才到：这里只发部分结果
                partials.forEach { partial ->
                    delay(stepDelayMs)
                    _events.emit(SpeechRecognizerEvent.PartialResult(request.operationId, partial))
                }
            }
            else -> {
                partials.forEach { partial ->
                    delay(stepDelayMs)
                    _events.emit(SpeechRecognizerEvent.PartialResult(request.operationId, partial))
                }
                delay(stepDelayMs)
                _events.emit(SpeechRecognizerEvent.SpeechEnded(request.operationId))
                if (gate.acceptTerminal(request.operationId)) {
                    _events.emit(SpeechRecognizerEvent.FinalResult(request.operationId, finalTranscript))
                }
            }
        }
    }

    override suspend fun stop(operationId: String?) {
        val current = gate.activeOperationId ?: return
        if (operationId != null && operationId != current) return
        // stop 允许系统给出最终结果，因此这里不发终止事件
        _events.emit(SpeechRecognizerEvent.SpeechEnded(current))
    }

    override suspend fun cancel(operationId: String?) {
        val current = gate.activeOperationId ?: return
        if (operationId != null && operationId != current) return
        session?.cancel()
        if (gate.acceptTerminal(current)) _events.emit(SpeechRecognizerEvent.Stopped(current))
        if (scenario == MockScenario.LateResultAfterCancel) {
            scope.launch {
                delay(stepDelayMs)
                // 平台不守规矩：取消之后仍然送来结果
                _events.emit(SpeechRecognizerEvent.FinalResult(current, finalTranscript))
            }
        }
    }

    override suspend fun dispose() {
        session?.cancel()
        gate.invalidate()
    }
}

class MockSpeechCapabilityService(
    private val scenario: MockScenario = MockScenario.HappyPath,
) : SpeechCapabilityService {

    var permission: MicrophonePermission = MicrophonePermission.GRANTED
    var synthesisAvailable: Boolean = true
    var onDeviceRecognition: Boolean = false

    override suspend fun getCapabilities(language: String?): SpeechCapabilities {
        val lang = language ?: "zh-CN"
        val recognition = scenario != MockScenario.ServiceUnavailable
        val effectivePermission = if (scenario == MockScenario.PermissionDenied) MicrophonePermission.DENIED else permission
        return SpeechCapabilities(
            synthesisAvailable = synthesisAvailable && scenario != MockScenario.ServiceUnavailable,
            recognitionAvailable = recognition,
            microphonePermission = effectivePermission,
            supportedRecognitionLanguages = if (recognition) listOf(lang) else emptyList(),
            supportedSynthesisLanguages = if (synthesisAvailable) listOf(lang) else emptyList(),
            onDeviceRecognitionAvailable = onDeviceRecognition,
        )
    }

    override suspend fun requestMicrophonePermission(): MicrophonePermission =
        if (scenario == MockScenario.PermissionDenied) MicrophonePermission.DENIED else MicrophonePermission.GRANTED
}

class MockAudioFocusController : AudioFocusController {

    private val _interruptions = MutableSharedFlow<AudioInterruptionEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val interruptions: Flow<AudioInterruptionEvent> = _interruptions.asSharedFlow()

    var playbackConfigured = false
        private set
    var recognitionConfigured = false
        private set
    var deactivated = false
        private set

    override suspend fun configureForPlayback() {
        playbackConfigured = true
    }

    override suspend fun configureForRecognition() {
        recognitionConfigured = true
    }

    override suspend fun deactivate() {
        deactivated = true
    }

    /** 测试里手动触发中断。 */
    fun emit(event: AudioInterruptionEvent) {
        _interruptions.tryEmit(event)
    }
}
