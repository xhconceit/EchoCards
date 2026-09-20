package com.orange.echocards.domain.learning

import com.orange.echocards.domain.learning.matching.CompletionTracker
import com.orange.echocards.domain.learning.matching.MatchProgress
import com.orange.echocards.domain.learning.matching.MatchingConfig
import com.orange.echocards.domain.learning.matching.RecognitionSignal
import com.orange.echocards.domain.learning.matching.TextMatcher
import com.orange.echocards.domain.learning.matching.TextNormalizer
import com.orange.echocards.domain.learning.matching.V1TextMatcher
import com.orange.echocards.domain.learning.matching.ZhTextNormalizer
import com.orange.echocards.domain.speech.AudioFocusController
import com.orange.echocards.domain.speech.AudioInterruptionEvent
import com.orange.echocards.domain.speech.RecognitionRequest
import com.orange.echocards.domain.speech.SpeakRequest
import com.orange.echocards.domain.speech.SpeechError
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 学习引擎，见 docs/architecture/learning-engine.md。
 *
 * 三种模式的语音流程都由**真实事件**驱动：朗读只有收到 `completed` 才继续，
 * 不使用固定朗读时长（第 8 节）。每次朗读、重读、切卡、切模式或恢复都会新建 operationId，
 * 并使旧 ID 立即失效（第 5 节）。
 *
 * 跟读完成走"先保存、后翻面"（第 7 节）：完成锁保证同一次跟读最多推进一次，
 * 事务成功之后才翻到快速记忆点，失败时保留当前卡片并允许重试。
 */
class DefaultLearningEngine(
    private val player: SpeechPlayer,
    private val recognizer: SpeechRecognizer,
    private val audioFocus: AudioFocusController,
    private val scope: CoroutineScope,
    private val cardSource: LearningCardSource,
    private val progressSink: LearningProgressSink,
    private val normalizer: TextNormalizer = ZhTextNormalizer,
    matcher: TextMatcher = V1TextMatcher(),
    private val matchingConfig: MatchingConfig = MatchingConfig.DEFAULT,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /**
     * 麦克风权限探针。引擎不做权限请求（那需要 Activity），但需要在开始跟读前确认真实状态，
     * 权限被撤销后也能停下来。
     */
    private val microphoneGranted: suspend () -> Boolean = { true },
) {

    private val _state = MutableStateFlow(LearningEngineState())
    val state: StateFlow<LearningEngineState> = _state.asStateFlow()

    private var segments: List<String> = emptyList()
    private var speechRate = 1.0
    private var autoAdvanceDelayMs = 600L
    private var counter = 0
    private var advanceJob: Job? = null
    private var silenceJob: Job? = null
    private var cardEnteredAtMs = 0L
    private var pendingCompletion: PendingCompletion? = null
    private var pendingAdvance: PendingAdvance? = null
    private val matching = CompletionTracker(matcher, matchingConfig)

    /** 跟读完成锁：同一张卡片的完成流程只跑一次（A04）。 */
    private val completionLock = AtomicBoolean(false)

    private val playerJob: Job = scope.launch { player.events.collect(::onPlayerEvent) }
    private val recognizerJob: Job = scope.launch { recognizer.events.collect(::onRecognizerEvent) }
    private val audioFocusJob: Job = scope.launch { audioFocus.interruptions.collect(::onAudioInterruption) }

    private data class PendingCompletion(
        val attempt: LearningAttemptRecord,
        val progress: MatchProgress,
        val targetIndex: Int,
        val nextCardId: String,
    )

    private data class PendingAdvance(
        val targetIndex: Int,
        val attempt: LearningAttemptRecord?,
    )

    suspend fun initialize(
        deckId: String,
        startCardId: String?,
        mode: LearningMode,
        rate: Double,
        autoDelayMs: Long,
    ) {
        speechRate = rate
        autoAdvanceDelayMs = autoDelayMs
        matching.reset()
        completionLock.set(false)
        pendingCompletion = null
        pendingAdvance = null
        _state.update {
            it.copy(deckId = deckId, mode = mode, phase = LearningPhase.LOADING,
                speechRate = rate, matchProgress = null, error = null)
        }
        val loaded = cardSource.loadCards(deckId)
        val index = loaded.indexOfFirst { it.id == startCardId }.let { if (it < 0) 0 else it }
        segments = emptyList()
        cardEnteredAtMs = nowMs()
        _state.update {
            it.copy(cards = loaded, currentIndex = index, cardFace = CardFace.FRONT, phase = LearningPhase.READY,
                operationId = null, speechSegmentIndex = 0, segmentCount = 0, error = null)
        }
    }

    /** 手动模式开始朗读；自动模式从当前卡片起步。 */
    suspend fun start() {
        val current = _state.value
        if (current.cards.isEmpty() || current.phase == LearningPhase.DISPOSED) return
        if (current.mode == LearningMode.FOLLOW_ALONG && !microphoneGranted()) {
            failWith("PERMISSION_DENIED", "没有麦克风权限，无法自动跟读", recoverable = true)
            return
        }
        speakFrom(0)
    }

    /** 暂停：先失效旧 ID 再停语音与收音，保留分段位置。 */
    suspend fun pause() {
        val current = _state.value
        if (current.phase == LearningPhase.PAUSED || current.phase == LearningPhase.IDLE ||
            current.phase == LearningPhase.DISPOSED
        ) {
            return
        }
        invalidateOperation()
        advanceJob?.cancel()
        stopListening()
        matching.reset()
        completionLock.set(false)
        audioFocus.deactivate()
        _state.update { it.copy(phase = LearningPhase.PAUSED, operationId = null, matchProgress = null) }
    }

    /** 继续：手动从保存的分段开头继续；自动模式继续当前流程。 */
    suspend fun resume() {
        val current = _state.value
        if (current.phase != LearningPhase.PAUSED) return
        when {
            current.cardFace == CardFace.BACK && current.mode != LearningMode.MANUAL -> {
                _state.update { it.copy(phase = LearningPhase.SHOWING_MEMORY_TIP) }
                // 跟读完成时位置已经写过，自动播放还没有：这里统一补一次写，重复写同一张无害
                scheduleAdvance(nextIndex(current.currentIndex, current.cards.size), persist = true)
            }
            current.mode == LearningMode.FOLLOW_ALONG && !microphoneGranted() ->
                failWith("PERMISSION_DENIED", "没有麦克风权限，无法自动跟读", recoverable = true)
            else -> speakFrom(current.speechSegmentIndex)
        }
    }

    /** 重读：回到当前卡片第一段，并清空临时跟读进度。 */
    suspend fun replay() {
        invalidateOperation()
        advanceJob?.cancel()
        stopListening()
        matching.reset()
        completionLock.set(false)
        pendingCompletion = null
        segments = emptyList()
        _state.update {
            it.copy(cardFace = CardFace.FRONT, phase = LearningPhase.READY, matchProgress = null, error = null)
        }
        speakFrom(0)
    }

    suspend fun flip() {
        _state.update {
            it.copy(cardFace = if (it.cardFace == CardFace.FRONT) CardFace.BACK else CardFace.FRONT)
        }
    }

    /** 左滑：切下一张；保存失败时不切卡。手动模式记录一次浏览。 */
    suspend fun next() {
        val current = _state.value
        val attempt = if (current.mode == LearningMode.MANUAL) current.currentCard?.let { viewedRecord(it) } else null
        moveTo(nextIndex(current.currentIndex, current.cards.size), attempt)
    }

    /** 右滑：切上一张。倒着看不记录学习记录。 */
    suspend fun previous() {
        moveTo(previousIndex(_state.value.currentIndex, _state.value.cards.size), attempt = null)
    }

    suspend fun setMode(mode: LearningMode) {
        val current = _state.value
        if (current.mode == mode) return
        invalidateOperation()
        advanceJob?.cancel()
        stopListening()
        matching.reset()
        completionLock.set(false)
        pendingCompletion = null
        pendingAdvance = null
        segments = emptyList()
        _state.update {
            it.copy(mode = mode, cardFace = CardFace.FRONT, phase = LearningPhase.READY,
                speechSegmentIndex = 0, segmentCount = 0, matchProgress = null, error = null)
        }
        if (mode == LearningMode.FOLLOW_ALONG && !microphoneGranted()) {
            failWith("PERMISSION_DENIED", "没有麦克风权限，无法自动跟读", recoverable = true)
            return
        }
        if (mode != LearningMode.MANUAL) speakFrom(0)
    }

    /** 语速从下一段朗读开始生效，见 learning-engine.md 第 8 节。 */
    suspend fun setSpeechRate(rate: Double) {
        speechRate = rate
        _state.update { it.copy(speechRate = rate) }
    }

    /**
     * 重试：优先补做失败的事务，其次按当前模式重新开始当前卡片。
     * 语音服务与权限错误都通过这里恢复（第 11 节）。
     */
    suspend fun retry() {
        val current = _state.value
        if (current.phase == LearningPhase.DISPOSED) return
        // 暂停中只清掉错误提示：不能在用户按了暂停之后自己开口朗读或翻页
        if (current.phase == LearningPhase.PAUSED) {
            _state.update { it.copy(error = null) }
            return
        }

        val pending = pendingCompletion
        if (pending != null) {
            val saved = progressSink.save(current.deckId, pending.nextCardId, current.mode, pending.attempt)
            if (saved) {
                pendingCompletion = null
                showMemoryTipAndAdvance(pending.targetIndex, pending.progress, persist = false)
            } else {
                failWith("SAVE_FAILED", "保存学习记录失败，请重试", recoverable = true)
            }
            return
        }

        val pendingMove = pendingAdvance
        val targetCard = pendingMove?.let { current.cards.getOrNull(it.targetIndex) }
        if (pendingMove != null && targetCard != null) {
            val saved = progressSink.save(current.deckId, targetCard.id, current.mode, pendingMove.attempt)
            if (saved) {
                pendingAdvance = null
                applyCardChange(pendingMove.targetIndex)
            } else {
                failWith("SAVE_FAILED", "保存学习位置失败，请重试", recoverable = true)
            }
            return
        }

        if (current.mode == LearningMode.FOLLOW_ALONG && !microphoneGranted()) {
            failWith("PERMISSION_DENIED", "没有麦克风权限，无法自动跟读", recoverable = true)
            return
        }
        _state.update { it.copy(error = null, phase = LearningPhase.READY) }
        speakFrom(0)
    }

    suspend fun dispose() {
        invalidateOperation()
        advanceJob?.cancel()
        stopListening()
        playerJob.cancel()
        recognizerJob.cancel()
        audioFocusJob.cancel()
        matching.reset()
        completionLock.set(false)
        pendingCompletion = null
        pendingAdvance = null
        segments = emptyList()
        _state.update {
            it.copy(phase = LearningPhase.DISPOSED, operationId = null, speechSegmentIndex = 0,
                segmentCount = 0, matchProgress = null)
        }
        audioFocus.deactivate()
        player.dispose()
        recognizer.dispose()
    }

    // ---- 朗读 ----

    private suspend fun speakFrom(index: Int) {
        val card = _state.value.currentCard ?: return
        if (_state.value.phase == LearningPhase.DISPOSED) return
        if (segments.isEmpty()) segments = SpeechSegmenter.split(card.targetText)
        if (segments.isEmpty()) {
            failWith("EMPTY_TEXT", "这张卡片没有可朗读的内容", recoverable = false)
            return
        }
        val safeIndex = index.coerceIn(0, segments.lastIndex)
        val operationId = "learn-${++counter}"
        _state.update {
            it.copy(phase = LearningPhase.SPEAKING, operationId = operationId,
                speechSegmentIndex = safeIndex, segmentCount = segments.size, error = null)
        }
        audioFocus.configureForPlayback()
        player.speak(SpeakRequest(operationId, segments[safeIndex], LANGUAGE, speechRate))
    }

    private fun onPlayerEvent(event: SpeechPlayerEvent) {
        if (event.operationId != _state.value.operationId) return
        when (event) {
            is SpeechPlayerEvent.Completed -> {
                // 先让旧 ID 失效再处理，平台重复报完成时不会把后面的分段跳过
                if (_state.value.phase != LearningPhase.SPEAKING) return
                _state.update { it.copy(operationId = null) }
                scope.launch { onSegmentCompleted() }
            }
            is SpeechPlayerEvent.Error -> failWith(event.error.code.name, event.error.message, event.error.recoverable)
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
            LearningMode.AUTO_PLAY -> showMemoryTipAndAdvance(
                nextIndex(current.currentIndex, current.cards.size), progress = null, persist = true,
            )
            LearningMode.FOLLOW_ALONG -> beginListening()
        }
    }

    // ---- 自动跟读 ----

    private suspend fun beginListening() {
        val current = _state.value
        if (current.phase == LearningPhase.DISPOSED || completionLock.get()) return
        val card = current.currentCard ?: return
        val target = normalizer.normalize(card.targetText, LANGUAGE)
        if (target.isEmpty()) {
            failWith("EMPTY_TARGET_TEXT", "这张卡片没有可跟读的内容", recoverable = false)
            return
        }
        if (!microphoneGranted()) {
            failWith("PERMISSION_DENIED", "没有麦克风权限，无法自动跟读", recoverable = true)
            return
        }
        matching.beginAttempt(target)
        startRecognitionSession()
    }

    private suspend fun startRecognitionSession() {
        val operationId = "listen-${++counter}"
        _state.update {
            it.copy(phase = LearningPhase.LISTENING, operationId = operationId,
                matchProgress = matching.bestProgress, error = null)
        }
        audioFocus.configureForRecognition()
        recognizer.start(
            RecognitionRequest(
                operationId = operationId,
                language = LANGUAGE,
                partialResults = true,
                preferOnDevice = false,
            )
        )
    }

    /** 会话结束但还没读完：保留已确认进度，按上限重启（第 14 节）。 */
    private suspend fun restartRecognitionSession() {
        if (!matching.canRestart()) {
            failWith(
                "RECOGNITION_RETRY_EXHAUSTED",
                "没有听清，请再读一次（已自动重试 ${matching.restartCount} 次）",
                recoverable = true,
            )
            return
        }
        matching.restartSession()
        startRecognitionSession()
    }

    private fun onRecognizerEvent(event: SpeechRecognizerEvent) {
        if (event.operationId != _state.value.operationId) return
        when (event) {
            is SpeechRecognizerEvent.PartialResult -> scope.launch { onSnapshot(RecognitionSignal.PARTIAL, event.transcript) }
            is SpeechRecognizerEvent.FinalResult -> scope.launch { onFinalResult(event.transcript) }
            is SpeechRecognizerEvent.SpeechEnded -> if (_state.value.phase == LearningPhase.LISTENING) restartSilenceTimer()
            is SpeechRecognizerEvent.Error -> scope.launch { onRecognitionError(event.error) }
            is SpeechRecognizerEvent.Ready, is SpeechRecognizerEvent.SpeechStarted, is SpeechRecognizerEvent.Stopped -> Unit
        }
    }

    private suspend fun onSnapshot(signal: RecognitionSignal, transcript: String) {
        if (_state.value.phase != LearningPhase.LISTENING || completionLock.get()) return
        val progress = matching.onResult(signal, transcript, nowMs())
        _state.update { it.copy(matchProgress = progress) }
        if (progress.completed) {
            completeFollowAlong(progress)
            return
        }
        if (signal == RecognitionSignal.PARTIAL) restartSilenceTimer()
    }

    private suspend fun onFinalResult(transcript: String) {
        if (_state.value.phase != LearningPhase.LISTENING || completionLock.get()) return
        if (transcript.isBlank()) {
            // 会话结束但没听到内容：算一次会话结束，按上限重启
            restartRecognitionSession()
            return
        }
        val progress = matching.onResult(RecognitionSignal.FINAL, transcript, nowMs())
        _state.update { it.copy(matchProgress = progress) }
        if (progress.completed) completeFollowAlong(progress) else restartRecognitionSession()
    }

    private suspend fun onRecognitionError(error: SpeechError) {
        if (_state.value.phase != LearningPhase.LISTENING || completionLock.get()) return
        when (error.code) {
            SpeechErrorCode.PERMISSION_DENIED -> failWith("PERMISSION_DENIED", error.message, recoverable = true)
            // 识别服务不可用重启也没用，直接把原因交给用户
            SpeechErrorCode.RECOGNITION_UNAVAILABLE, SpeechErrorCode.SYNTHESIS_UNAVAILABLE,
            SpeechErrorCode.LANGUAGE_UNSUPPORTED,
            -> failWith(error.code.name, error.message, recoverable = true)
            else -> {
                if (matching.canRestart()) restartRecognitionSession()
                else failWith(error.code.name, error.message, recoverable = true)
            }
        }
    }

    /**
     * 条件 C：用户停下来一段时间就确认当前结果，只确认已经达标的文本（第 12 节）。
     * 计时由引擎负责，每次新快照都会重置。
     */
    private fun restartSilenceTimer() {
        silenceJob?.cancel()
        silenceJob = scope.launch {
            delay(matchingConfig.silenceConfirmMs)
            if (_state.value.phase != LearningPhase.LISTENING || completionLock.get()) return@launch
            val progress = matching.onSilence()
            _state.update { it.copy(matchProgress = progress) }
            if (progress.completed) completeFollowAlong(progress)
        }
    }

    /** 停止收音：先取消本地计时，再让识别器丢弃当前会话。 */
    private suspend fun stopListening() {
        silenceJob?.cancel()
        recognizer.cancel(null)
    }

    /**
     * 跟读完成：先停收音、再写事务，事务成功之后才翻面（第 7 节）。
     * transaction 失败时保留当前卡片，等用户重试（A06）。
     */
    private suspend fun completeFollowAlong(progress: MatchProgress) {
        if (!completionLock.compareAndSet(false, true)) return
        val current = _state.value
        silenceJob?.cancel()
        val operationId = current.operationId
        _state.update { it.copy(phase = LearningPhase.EVALUATING, operationId = null, matchProgress = progress) }
        if (operationId != null) recognizer.cancel(operationId)

        val card = current.currentCard
        if (card == null) {
            completionLock.set(false)
            return
        }
        val targetIndex = nextIndex(current.currentIndex, current.cards.size)
        val nextCard = current.cards[targetIndex]
        val attempt = LearningAttemptRecord(
            cardId = card.id,
            cardRevision = card.revision,
            outcome = AttemptOutcome.READ_COMPLETED,
            coverage = progress.coverage,
            endingMatched = progress.endingMatched,
            algorithmVersion = progress.algorithmVersion,
            startedAtMs = cardEnteredAtMs,
            endedAtMs = nowMs(),
        )
        if (!progressSink.save(current.deckId, nextCard.id, current.mode, attempt)) {
            pendingCompletion = PendingCompletion(attempt, progress, targetIndex, nextCard.id)
            completionLock.set(false)
            _state.update {
                it.copy(phase = LearningPhase.ERROR, cardFace = CardFace.FRONT,
                    error = LearningError("SAVE_FAILED", "保存学习记录失败，请重试", recoverable = true))
            }
            return
        }
        // 写库是挂起操作：这期间用户可能已经切卡（切卡会把完成锁放开），
        // 此时不能再翻面前进，并把位置修回用户实际停留的那张卡
        if (_state.value.currentCard?.id != card.id) {
            _state.value.currentCard?.let { progressSink.save(_state.value.deckId, it.id, _state.value.mode, null) }
            return
        }
        pendingCompletion = null
        showMemoryTipAndAdvance(targetIndex, progress, persist = false)
    }

    /**
     * 翻到快速记忆点并等待停留时间；完成锁在切卡时才释放（A04）。
     *
     * [persist] 表示这次前进还要不要写位置：跟读完成的那个事务已经写过，自动播放没有，
     * 见 data-model.md 第 8 节"自动播放只更新位置"。
     */
    private suspend fun showMemoryTipAndAdvance(targetIndex: Int, progress: MatchProgress?, persist: Boolean) {
        audioFocus.deactivate()
        _state.update {
            it.copy(cardFace = CardFace.BACK, phase = LearningPhase.SHOWING_MEMORY_TIP,
                operationId = null, matchProgress = progress ?: it.matchProgress, error = null)
        }
        scheduleAdvance(targetIndex, persist)
    }

    private fun scheduleAdvance(targetIndex: Int, persist: Boolean) {
        advanceJob?.cancel()
        advanceJob = scope.launch {
            delay(autoAdvanceDelayMs)
            advanceTo(targetIndex, persist)
        }
    }

    /** 停留结束后的前进：需要写库时先写，失败就保留当前卡片等重试。 */
    private suspend fun advanceTo(targetIndex: Int, persist: Boolean) {
        val current = _state.value
        if (persist) {
            val target = current.cards.getOrNull(targetIndex) ?: return
            if (!progressSink.save(current.deckId, target.id, current.mode, null)) {
                pendingAdvance = PendingAdvance(targetIndex, attempt = null)
                failWith("SAVE_FAILED", "保存学习位置失败，请重试", recoverable = true)
                return
            }
            pendingAdvance = null
        }
        applyCardChange(targetIndex)
    }

    // ---- 切卡与保存 ----

    private suspend fun moveTo(targetIndex: Int, attempt: LearningAttemptRecord?) {
        val current = _state.value
        if (current.cards.isEmpty() || targetIndex == current.currentIndex) return
        invalidateOperation()
        advanceJob?.cancel()
        stopListening()
        matching.reset()
        completionLock.set(false)
        pendingCompletion = null

        val target = current.cards[targetIndex]
        if (!progressSink.save(current.deckId, target.id, current.mode, attempt)) {
            // M05：保存失败不切卡，保留当前卡片并可重试
            pendingAdvance = PendingAdvance(targetIndex, attempt)
            _state.update {
                it.copy(phase = LearningPhase.ERROR,
                    error = LearningError("SAVE_FAILED", "保存学习位置失败，请重试", recoverable = true))
            }
            return
        }
        pendingAdvance = null
        applyCardChange(targetIndex)
    }

    /** 只更新界面与语音，不再写库：写库由调用方的事务负责。 */
    private suspend fun applyCardChange(targetIndex: Int) {
        val current = _state.value
        if (current.phase == LearningPhase.DISPOSED) return
        silenceJob?.cancel()
        completionLock.set(false)
        matching.reset()
        pendingCompletion = null
        pendingAdvance = null
        segments = emptyList()
        cardEnteredAtMs = nowMs()
        _state.update {
            it.copy(currentIndex = targetIndex, cardFace = CardFace.FRONT, phase = LearningPhase.READY,
                speechSegmentIndex = 0, segmentCount = 0, operationId = null, matchProgress = null, error = null)
        }
        if (current.mode != LearningMode.MANUAL) speakFrom(0)
    }

    private fun viewedRecord(card: LearningCard) = LearningAttemptRecord(
        cardId = card.id,
        cardRevision = card.revision,
        outcome = AttemptOutcome.VIEWED,
        startedAtMs = cardEnteredAtMs,
        endedAtMs = nowMs(),
    )

    private fun nextIndex(current: Int, size: Int) = if (size <= 0) 0 else (current + 1) % size

    private fun previousIndex(current: Int, size: Int) = if (size <= 0) 0 else (current - 1 + size) % size

    // ---- 音频焦点与错误 ----

    private fun onAudioInterruption(event: AudioInterruptionEvent) {
        when (event) {
            // 只处理真正的焦点丢失：恢复与路由变化都不自动继续，也不自动开麦（第 10 节）
            is AudioInterruptionEvent.InterruptionStarted -> {
                if (_state.value.phase != LearningPhase.IDLE && _state.value.phase != LearningPhase.DISPOSED) {
                    scope.launch { pause() }
                }
            }
            is AudioInterruptionEvent.InterruptionEnded, is AudioInterruptionEvent.RouteChanged -> Unit
        }
    }

    private fun failWith(code: String, message: String, recoverable: Boolean) {
        silenceJob?.cancel()
        scope.launch { audioFocus.deactivate() }
        _state.update {
            it.copy(phase = LearningPhase.ERROR, operationId = null,
                error = LearningError(code, message, recoverable))
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
