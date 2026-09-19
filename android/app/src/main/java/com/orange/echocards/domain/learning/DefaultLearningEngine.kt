package com.orange.echocards.domain.learning

import com.orange.echocards.domain.speech.SpeakRequest
import com.orange.echocards.domain.speech.SpeechErrorCode
import com.orange.echocards.domain.speech.SpeechPlayer
import com.orange.echocards.domain.speech.SpeechPlayerEvent
import com.orange.echocards.domain.speech.SpeechRecognizer
import com.orange.echocards.domain.speech.SpeechRecognizerEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 卡片来源与位置保存通过接口注入，引擎本身不依赖 Room。 */
fun interface LearningCardSource {
    suspend fun loadCards(deckId: String): List<LearningCard>
}

fun interface LearningProgressSink {
    /** 返回是否保存成功；失败时引擎必须保留当前卡片。 */
    suspend fun savePosition(deckId: String, cardId: String, mode: LearningMode): Boolean
}

/**
 * 学习引擎，见 docs/architecture/learning-engine.md。
 *
 * 三种模式的语音流程都由**真实事件**驱动：朗读只有收到 `completed` 才继续，
 * 不使用固定朗读时长（第 8 节）。每次朗读、重读、切卡、切模式或恢复都会新建 operationId，
 * 并使旧 ID 立即失效（第 5 节）。
 */
class DefaultLearningEngine(
    private val player: SpeechPlayer,
    private val recognizer: SpeechRecognizer,
    private val scope: CoroutineScope,
    private val cardSource: LearningCardSource,
    private val progressSink: LearningProgressSink,
) {

    private val _state = MutableStateFlow(LearningEngineState())
    val state: StateFlow<LearningEngineState> = _state.asStateFlow()

    private var segments: List<String> = emptyList()
    private var speechRate = 1.0
    private var autoAdvanceDelayMs = 600L
    private var counter = 0
    private var advanceJob: Job? = null
    private val playerJob: Job = scope.launch { player.events.collect(::onPlayerEvent) }
    private val recognizerJob: Job = scope.launch { recognizer.events.collect(::onRecognizerEvent) }

    suspend fun initialize(deckId: String, startCardId: String?, mode: LearningMode, rate: Double, autoDelayMs: Long) {
        speechRate = rate
        autoAdvanceDelayMs = autoDelayMs
        _state.update { it.copy(deckId = deckId, mode = mode, phase = LearningPhase.LOADING) }
        val loaded = cardSource.loadCards(deckId)
        val index = loaded.indexOfFirst { it.id == startCardId }.let { if (it < 0) 0 else it }
        segments = emptyList()
        _state.update {
            it.copy(cards = loaded, currentIndex = index, cardFace = CardFace.FRONT, phase = LearningPhase.READY,
                operationId = null, speechSegmentIndex = 0, segmentCount = 0, error = null)
        }
    }

    /** 手动模式开始朗读；自动模式从当前卡片起步。 */
    suspend fun start() {
        if (_state.value.cards.isEmpty()) return
        if (_state.value.mode == LearningMode.MANUAL) speakFrom(0) else speakFrom(0)
    }

    /** 暂停：先失效旧 ID 再停语音，保留分段位置。 */
    suspend fun pause() {
        if (_state.value.phase == LearningPhase.PAUSED) return
        invalidateOperation()
        advanceJob?.cancel()
        _state.update { it.copy(phase = LearningPhase.PAUSED) }
    }

    /** 继续：手动从保存的分段开头继续；自动模式继续当前流程。 */
    suspend fun resume() {
        val current = _state.value
        if (current.phase != LearningPhase.PAUSED) return
        when {
            current.cardFace == CardFace.BACK && current.mode != LearningMode.MANUAL -> {
                // 停在快速记忆点：继续等停留时间后前进
                _state.update { it.copy(phase = LearningPhase.SHOWING_MEMORY_TIP) }
                scheduleAdvance()
            }
            else -> speakFrom(current.speechSegmentIndex)
        }
    }

    /** 重读：回到当前卡片第一段。 */
    suspend fun replay() {
        invalidateOperation()
        advanceJob?.cancel()
        _state.update { it.copy(cardFace = CardFace.FRONT) }
        speakFrom(0)
    }

    suspend fun flip() {
        _state.update {
            it.copy(cardFace = if (it.cardFace == CardFace.FRONT) CardFace.BACK else CardFace.FRONT)
        }
    }

    /** 左滑：切下一张；保存失败时不切卡。 */
    suspend fun next() = moveTo(nextIndex(_state.value.currentIndex, _state.value.cards.size))

    /** 右滑：切上一张。 */
    suspend fun previous() = moveTo(previousIndex(_state.value.currentIndex, _state.value.cards.size))

    suspend fun setMode(mode: LearningMode) {
        if (_state.value.mode == mode) return
        invalidateOperation()
        advanceJob?.cancel()
        recognizer.cancel(null)
        segments = emptyList()
        _state.update {
            it.copy(mode = mode, cardFace = CardFace.FRONT, phase = LearningPhase.READY,
                speechSegmentIndex = 0, segmentCount = 0, error = null)
        }
        if (mode != LearningMode.MANUAL) speakFrom(0)
    }

    suspend fun setSpeechRate(rate: Double) {
        speechRate = rate
    }

    suspend fun dispose() {
        invalidateOperation()
        advanceJob?.cancel()
        playerJob.cancel()
        recognizerJob.cancel()
        recognizer.cancel(null)
        segments = emptyList()
        _state.update { it.copy(phase = LearningPhase.DISPOSED, operationId = null, speechSegmentIndex = 0, segmentCount = 0) }
        player.dispose()
        recognizer.dispose()
    }

    // ---- 内部实现 ----

    private suspend fun moveTo(targetIndex: Int) {
        val current = _state.value
        if (current.cards.isEmpty() || targetIndex == current.currentIndex) return
        invalidateOperation()
        advanceJob?.cancel()
        recognizer.cancel(null)

        val target = current.cards[targetIndex]
        val saved = progressSink.savePosition(current.deckId, target.id, current.mode)
        if (!saved) {
            // M05：保存失败不切卡，保留当前状态并可重试
            _state.update {
                it.copy(phase = LearningPhase.ERROR, error = LearningError("SAVE_FAILED", "保存学习位置失败，请重试", true))
            }
            return
        }
        segments = emptyList()
        _state.update {
            it.copy(currentIndex = targetIndex, cardFace = CardFace.FRONT, phase = LearningPhase.READY,
                speechSegmentIndex = 0, segmentCount = 0, error = null)
        }
        if (current.mode != LearningMode.MANUAL) speakFrom(0)
    }

    private fun nextIndex(current: Int, size: Int) = if (size <= 0) 0 else (current + 1) % size

    private fun previousIndex(current: Int, size: Int) = if (size <= 0) 0 else (current - 1 + size) % size

    /** 从第 [index] 段开始朗读；自动模式下会一路读到本卡结束。 */
    private suspend fun speakFrom(index: Int) {
        val card = _state.value.currentCard ?: return
        if (segments.isEmpty()) segments = SpeechSegmenter.split(card.spokenText)
        if (segments.isEmpty()) {
            _state.update { it.copy(phase = LearningPhase.ERROR, error = LearningError("EMPTY_TEXT", "这张卡片没有可朗读的内容", false)) }
            return
        }
        val safeIndex = index.coerceIn(0, segments.lastIndex)
        val operationId = "learn-${++counter}"
        _state.update {
            it.copy(phase = LearningPhase.SPEAKING, operationId = operationId,
                speechSegmentIndex = safeIndex, segmentCount = segments.size, error = null)
        }
        player.speak(SpeakRequest(operationId, segments[safeIndex], LANGUAGE, speechRate))
    }

    private fun onPlayerEvent(event: SpeechPlayerEvent) {
        if (event.operationId != _state.value.operationId) return
        when (event) {
            is SpeechPlayerEvent.Completed -> scope.launch { onSegmentCompleted() }
            is SpeechPlayerEvent.Error -> _state.update {
                it.copy(phase = LearningPhase.ERROR, operationId = null,
                    error = LearningError(event.error.code.name, event.error.message, event.error.recoverable))
            }
            is SpeechPlayerEvent.Started, is SpeechPlayerEvent.Stopped -> Unit
        }
    }

    /** 一段读完：还有下一段就继续，否则按模式进入下一步。 */
    private suspend fun onSegmentCompleted() {
        val current = _state.value
        val nextSegment = current.speechSegmentIndex + 1
        if (nextSegment < segments.size) {
            speakFrom(nextSegment)
            return
        }
        segments = emptyList()
        _state.update { it.copy(speechSegmentIndex = 0, segmentCount = 0, operationId = null) }
        when (current.mode) {
            LearningMode.MANUAL -> _state.update { it.copy(phase = LearningPhase.READY) }
            LearningMode.AUTO_PLAY -> showMemoryTipThenAdvance()
            LearningMode.FOLLOW_ALONG -> startListening()
        }
    }

    private suspend fun showMemoryTipThenAdvance() {
        _state.update { it.copy(phase = LearningPhase.SHOWING_MEMORY_TIP, cardFace = CardFace.BACK) }
        scheduleAdvance()
    }

    private fun scheduleAdvance() {
        advanceJob?.cancel()
        advanceJob = scope.launch {
            delay(autoAdvanceDelayMs)
            moveTo(nextIndex(_state.value.currentIndex, _state.value.cards.size))
        }
    }

    /**
     * 自动跟读的收音步骤。
     *
     * 暂定：识别到任意非空结果即视为完成，真正的覆盖率匹配与完成锁由节点 6 实现
     * （docs/architecture/speech-matching.md）。
     */
    private suspend fun startListening() {
        val operationId = "listen-${++counter}"
        _state.update { it.copy(phase = LearningPhase.LISTENING, operationId = operationId) }
        recognizer.start(
            com.orange.echocards.domain.speech.RecognitionRequest(
                operationId = operationId,
                language = LANGUAGE,
                partialResults = false,
                preferOnDevice = false,
            )
        )
    }

    private fun onRecognizerEvent(event: SpeechRecognizerEvent) {
        if (event.operationId != _state.value.operationId) return
        when (event) {
            is SpeechRecognizerEvent.FinalResult -> scope.launch {
                _state.update { it.copy(phase = LearningPhase.EVALUATING, operationId = null) }
                if (event.transcript.isNotBlank()) showMemoryTipThenAdvance()
                else startListening()
            }
            is SpeechRecognizerEvent.Error -> _state.update {
                it.copy(phase = LearningPhase.ERROR, operationId = null,
                    error = LearningError(event.error.code.name, event.error.message, event.error.recoverable))
            }
            else -> Unit
        }
    }

    private fun invalidateOperation() {
        val previous = _state.value.operationId
        _state.update { it.copy(operationId = null) }
        if (previous != null) scope.launch { player.stop(previous) }
    }

    private companion object {
        const val LANGUAGE = "zh-CN"
    }
}
