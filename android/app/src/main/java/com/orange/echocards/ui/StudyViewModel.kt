package com.orange.echocards.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.orange.echocards.data.EchoRepository
import com.orange.echocards.data.storageName
import com.orange.echocards.domain.learning.DefaultLearningEngine
import com.orange.echocards.domain.learning.LearningCard
import com.orange.echocards.domain.learning.LearningEngineState
import com.orange.echocards.domain.learning.LearningMode
import com.orange.echocards.domain.learning.LearningPhase
import com.orange.echocards.domain.speech.MicrophonePermission
import com.orange.echocards.speech.android.AndroidSpeechServices
import com.orange.echocards.speech.vosk.VoskModelProvider
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 学习页与 Learning Engine 之间的桥：Composable 只读状态、发命令，
 * 不直接接触 TextToSpeech / SpeechRecognizer（见 AGENTS.md 与 learning-engine.md）。
 */
class StudyViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = EchoRepository(application)

    /** 朗读用系统 TTS，跟读用 Vosk 离线识别，见 ADR-002。 */
    private val speech = AndroidSpeechServices.createWithOfflineRecognition(
        application,
        permissionRequester = { ensureMicrophonePermission() },
    )

    /**
     * 引擎每次进入学习页新建：dispose() 会取消事件收集协程，复用已释放的实例会收不到回调。
     */
    private var engine: DefaultLearningEngine? = null
    private var stateJob: Job? = null
    private var autoAdvanceDelayMs = 600L
    private val _state = MutableStateFlow(LearningEngineState())
    val state: StateFlow<LearningEngineState> = _state.asStateFlow()

    /** 自动跟读需要麦克风权限；由学习页注入 Activity Result 流程。 */
    var launchPermissionRequest: (() -> Unit)? = null
    private var permissionContinuation: CancellableContinuation<Boolean>? = null

    fun open(deckId: String, mode: LearningMode, speechRate: Double, autoAdvanceDelayMs: Long, startCardId: String?) {
        this.autoAdvanceDelayMs = autoAdvanceDelayMs
        viewModelScope.launch {
            if (mode == LearningMode.FOLLOW_ALONG) {
                ensureMicrophonePermission()
                // 不等它：模型的解压与加载和第一张卡片的朗读同时进行，第一次跟读就不用干等
                launch { warmUpOfflineModel() }
            }
            engine?.let { viewModelScope.launch { it.dispose() } }
            stateJob?.cancel()
            val created = DefaultLearningEngine(
                player = speech.player,
                recognizer = speech.recognizer,
                audioFocus = speech.audioFocus,
                scope = viewModelScope,
                cardSource = { id -> repository.loadCards(id).map { it.toLearningCard() } },
                progressSink = { id, nextCardId, studyMode, attempt ->
                    repository.saveLearningTransaction(id, nextCardId, studyMode, attempt)
                },
                microphoneGranted = { hasMicrophonePermission() },
            )
            engine = created
            stateJob = viewModelScope.launch { created.state.collect { _state.value = it } }
            created.initialize(deckId, startCardId, mode, speechRate, autoAdvanceDelayMs)
            if (mode != LearningMode.MANUAL) created.start()
        }
    }

    fun pause() = viewModelScope.launch { engine?.pause() }
    fun resume() = viewModelScope.launch { engine?.resume() }
    fun replay() = viewModelScope.launch { engine?.replay() }
    fun next() = viewModelScope.launch { engine?.next() }
    fun previous() = viewModelScope.launch { engine?.previous() }
    fun flip() = viewModelScope.launch { engine?.flip() }
    fun setMode(mode: LearningMode) = viewModelScope.launch {
        // 切到自动跟读前先要权限，引擎只负责确认状态
        if (mode == LearningMode.FOLLOW_ALONG) {
            ensureMicrophonePermission()
            launch { warmUpOfflineModel() }
        }
        engine?.setMode(mode)
    }

    /**
     * 重试：跟读出错时可能要重新申请权限，因此先把权限要回来再交给引擎补做事务或重开当前卡片。
     */
    fun retry() = viewModelScope.launch {
        if (_state.value.mode == LearningMode.FOLLOW_ALONG) ensureMicrophonePermission()
        engine?.retry()
    }

    /** 循环语速 0.75 → 1.0 → 1.25 → 1.5，并记住选择。 */
    fun cycleSpeechRate() = viewModelScope.launch {
        val current = _state.value
        val next = SPEECH_RATES.firstOrNull { it > current.speechRate + 0.001 } ?: SPEECH_RATES.first()
        engine?.setSpeechRate(next)
        val settings = repository.settings.first()
        repository.saveSettings(
            mode = settings?.defaultMode ?: current.mode.toUiMode(),
            rate = next,
            delay = settings?.autoAdvanceDelayMs ?: autoAdvanceDelayMs,
        )
    }

    /** 学习页的朗读按钮：未开始就播放，朗读中则暂停，暂停中则继续。 */
    fun toggleSpeech() = viewModelScope.launch {
        val current = engine ?: return@launch
        when (_state.value.phase) {
            LearningPhase.SPEAKING -> current.pause()
            LearningPhase.PAUSED -> current.resume()
            else -> current.start()
        }
    }

    /** 进入后台：停止朗读与识别并保存位置，回前台保持暂停。 */
    fun onEnterBackground() {
        viewModelScope.launch { engine?.pause() }
    }

    fun onReturnForeground() = Unit

    /** 退出学习页：保存当前位置后释放资源。 */
    fun close() = viewModelScope.launch {
        val current = engine ?: return@launch
        val value = _state.value
        value.currentCard?.let { repository.saveLearningPosition(value.deckId, it.id, value.mode.storageName) }
        current.dispose()
        engine = null
    }

    fun onPermissionResult(granted: Boolean) {
        val continuation = permissionContinuation ?: return
        permissionContinuation = null
        if (continuation.isActive) continuation.resume(granted)
    }

    /** 触发离线模型解压与加载；失败由识别器在真正用到时报告，这里只做预热。 */
    private suspend fun warmUpOfflineModel() {
        runCatching { VoskModelProvider.get(getApplication()) }
    }

    private suspend fun hasMicrophonePermission(): Boolean =
        speech.capabilities.getCapabilities(LANGUAGE).microphonePermission == MicrophonePermission.GRANTED

    private suspend fun ensureMicrophonePermission(): Boolean {
        if (hasMicrophonePermission()) return true
        return suspendCancellableCoroutine { continuation ->
            // 先登记 continuation，再拉起系统权限框，避免极快返回的权限结果
            // 在 continuation 尚未保存时被丢弃，导致跟读流程永久等待。
            if (launchPermissionRequest == null) {
                continuation.resume(false)
                return@suspendCancellableCoroutine
            }
            permissionContinuation?.cancel()
            permissionContinuation = continuation
            continuation.invokeOnCancellation { permissionContinuation = null }
            launchPermissionRequest?.invoke()
        }
    }

    override fun onCleared() {
        engine?.let { viewModelScope.launch { it.dispose() } }
        super.onCleared()
    }

    private companion object {
        const val LANGUAGE = "zh-CN"
        val SPEECH_RATES = listOf(0.75, 1.0, 1.25, 1.5)
    }
}

/** 界面里的模式字符串（manual / follow_along / auto_play）与领域枚举互转。 */
internal fun String.toLearningMode(): LearningMode = when (this) {
    "follow_along" -> LearningMode.FOLLOW_ALONG
    "auto_play" -> LearningMode.AUTO_PLAY
    else -> LearningMode.MANUAL
}

internal fun LearningMode.toUiMode(): String = when (this) {
    LearningMode.FOLLOW_ALONG -> "follow_along"
    LearningMode.AUTO_PLAY -> "auto_play"
    LearningMode.MANUAL -> "manual"
}

private fun com.orange.echocards.data.CardEntity.toLearningCard() = LearningCard(
    id = id,
    title = title,
    content = content,
    speechText = speechText,
    memoryTip = memoryTip,
    revision = revision,
)
