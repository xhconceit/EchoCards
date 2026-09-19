package com.orange.echocards.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.orange.echocards.data.EchoRepository
import com.orange.echocards.domain.learning.DefaultLearningEngine
import com.orange.echocards.domain.learning.LearningCard
import com.orange.echocards.domain.learning.LearningEngineState
import com.orange.echocards.domain.learning.LearningMode
import com.orange.echocards.domain.learning.LearningPhase
import com.orange.echocards.speech.android.AndroidSpeechServices
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 学习页与 Learning Engine 之间的桥：Composable 只读状态、发命令，
 * 不直接接触 TextToSpeech / SpeechRecognizer（见 AGENTS.md 与 learning-engine.md）。
 */
class StudyViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = EchoRepository(application)
    private val speech = AndroidSpeechServices.create(application, permissionRequester = { ensureMicrophonePermission() })

    /**
     * 引擎每次进入学习页新建：dispose() 会取消事件收集协程，复用已释放的实例会收不到回调。
     */
    private var engine: DefaultLearningEngine? = null
    private var stateJob: Job? = null
    private val _state = MutableStateFlow(LearningEngineState())
    val state: StateFlow<LearningEngineState> = _state.asStateFlow()

    /** 自动跟读需要麦克风权限；由学习页注入 Activity Result 流程。 */
    var launchPermissionRequest: (() -> Unit)? = null
    private var permissionContinuation: CancellableContinuation<Boolean>? = null
    private var backgrounded = false

    fun open(deckId: String, mode: LearningMode, speechRate: Double, autoAdvanceDelayMs: Long, startCardId: String?) {
        viewModelScope.launch {
            if (mode == LearningMode.FOLLOW_ALONG) ensureMicrophonePermission()
            engine?.let { viewModelScope.launch { it.dispose() } }
            stateJob?.cancel()
            val created = DefaultLearningEngine(
                player = speech.player,
                recognizer = speech.recognizer,
                scope = viewModelScope,
                cardSource = { id -> repository.loadCards(id).map { it.toLearningCard() } },
                progressSink = { id, cardId, m -> repository.saveLearningPosition(id, cardId, m.storageName) },
            )
            engine = created
            stateJob = viewModelScope.launch { created.state.collect { _state.value = it } }
            created.initialize(deckId, startCardId, mode, speechRate, autoAdvanceDelayMs)
            if (mode != LearningMode.MANUAL) created.start()
        }
    }

    fun play() = viewModelScope.launch { engine?.start() }
    fun pause() = viewModelScope.launch { engine?.pause() }
    fun resume() = viewModelScope.launch { engine?.resume() }
    fun replay() = viewModelScope.launch { engine?.replay() }
    fun next() = viewModelScope.launch { engine?.next() }
    fun previous() = viewModelScope.launch { engine?.previous() }
    fun flip() = viewModelScope.launch { engine?.flip() }
    fun setMode(mode: LearningMode) = viewModelScope.launch { engine?.setMode(mode) }
    fun setSpeechRate(rate: Double) = viewModelScope.launch { engine?.setSpeechRate(rate) }

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
        backgrounded = true
        viewModelScope.launch { engine?.pause() }
    }

    fun onReturnForeground() {
        backgrounded = false
    }

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

    private suspend fun ensureMicrophonePermission(): Boolean {
        val capabilities = speech.capabilities.getCapabilities(LANGUAGE)
        if (capabilities.microphonePermission == com.orange.echocards.domain.speech.MicrophonePermission.GRANTED) return true
        launchPermissionRequest?.invoke() ?: return false
        return suspendCancellableCoroutine { continuation ->
            permissionContinuation = continuation
            continuation.invokeOnCancellation { permissionContinuation = null }
        }
    }

    override fun onCleared() {
        engine?.let { viewModelScope.launch { it.dispose() } }
        super.onCleared()
    }

    private companion object {
        const val LANGUAGE = "zh-CN"
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

/** 存进 Room 的模式名与既有数据保持一致。 */
internal val LearningMode.storageName: String get() = toUiMode()

private fun com.orange.echocards.data.CardEntity.toLearningCard() = LearningCard(
    id = id,
    title = title,
    content = content,
    speechText = speechText,
    memoryTip = memoryTip,
)
