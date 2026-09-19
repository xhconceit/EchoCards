package com.orange.echocards.speech.android

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.orange.echocards.domain.speech.OperationGate
import com.orange.echocards.domain.speech.SpeakRequest
import com.orange.echocards.domain.speech.SpeechError
import com.orange.echocards.domain.speech.SpeechErrorCode
import com.orange.echocards.domain.speech.SpeechPlayer
import com.orange.echocards.domain.speech.SpeechPlayerEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Android TTS 适配器，见 docs/reference/speech-api.md 第 5、10 节。
 *
 * - utterance ID 直接用 operationId，`onDone` 才表示朗读完成。
 * - `stop()` 只产生 Stopped，永不产生 Completed。
 * - 迟到的回调由 [OperationGate] 丢弃，同一个操作最多一次终止事件。
 * - TTS 引擎延迟到第一次朗读时才初始化，避免 App 启动就拉起引擎。
 */
internal class AndroidSpeechPlayer(context: Context) : SpeechPlayer {

    private val appContext = context.applicationContext
    private val _events = MutableSharedFlow<SpeechPlayerEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<SpeechPlayerEvent> = _events.asSharedFlow()

    private val gate = OperationGate()
    private val initLock = Mutex()
    private var engine: TextToSpeech? = null

    /** 被忽略的迟到事件数，探针页与测试用。 */
    val ignoredEvents: Int get() = gate.ignored

    private val utteranceListener = object : UtteranceProgressListener() {

        override fun onStart(utteranceId: String?) {
            val id = utteranceId ?: return
            if (gate.accept(id)) _events.tryEmit(SpeechPlayerEvent.Started(id))
        }

        override fun onDone(utteranceId: String?) {
            val id = utteranceId ?: return
            if (gate.acceptTerminal(id)) _events.tryEmit(SpeechPlayerEvent.Completed(id))
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            onError(utteranceId, TextToSpeech.ERROR)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            val id = utteranceId ?: return
            if (gate.acceptTerminal(id)) {
                _events.tryEmit(SpeechPlayerEvent.Error(id, SpeechErrorMapper.fromSynthesis(errorCode)))
            }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            // stop() 已经发过 Stopped 并终止操作，这里通常会被 gate 忽略
            val id = utteranceId ?: return
            if (gate.acceptTerminal(id)) _events.tryEmit(SpeechPlayerEvent.Stopped(id))
        }
    }

    override suspend fun speak(request: SpeakRequest) {
        val text = request.text.trim()
        gate.begin(request.operationId)
        if (text.isEmpty()) {
            fail(request.operationId, SpeechError(SpeechErrorCode.INTERNAL_ERROR, "朗读文本为空"))
            return
        }
        val tts = acquireEngine()
        if (tts == null) {
            fail(request.operationId, SpeechError(SpeechErrorCode.SYNTHESIS_UNAVAILABLE, "设备没有可用的朗读服务"))
            return
        }
        withContext(Dispatchers.Main) {
            val locale = Locale.forLanguageTag(request.language)
            val availability = tts.isLanguageAvailable(locale)
            if (availability < TextToSpeech.LANG_AVAILABLE) {
                fail(request.operationId, SpeechError(
                    SpeechErrorCode.LANGUAGE_UNSUPPORTED, "朗读引擎不支持 ${request.language}",
                    nativeCode = "LANG_$availability"))
                return@withContext
            }
            tts.language = locale
            tts.setSpeechRate(request.rate.toFloat().coerceIn(0.5f, 2.0f))
            val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, request.operationId)
            if (result == TextToSpeech.ERROR) {
                fail(request.operationId, SpeechError(SpeechErrorCode.INTERNAL_ERROR, "朗读请求被系统拒绝"))
            }
        }
    }

    override suspend fun stop(operationId: String?) {
        val current = gate.activeOperationId ?: return
        if (operationId != null && operationId != current) return
        withContext(Dispatchers.Main) { engine?.stop() }
        if (gate.acceptTerminal(current)) _events.tryEmit(SpeechPlayerEvent.Stopped(current))
    }

    override suspend fun dispose() {
        gate.invalidate()
        val tts = engine ?: return
        engine = null
        withContext(Dispatchers.Main) {
            tts.stop()
            tts.shutdown()
        }
    }

    private fun fail(operationId: String, error: SpeechError) {
        if (gate.acceptTerminal(operationId)) _events.tryEmit(SpeechPlayerEvent.Error(operationId, error))
    }

    /** 引擎只初始化一次；初始化失败或超时返回 null。 */
    private suspend fun acquireEngine(): TextToSpeech? = initLock.withLock {
        engine?.let { return@withLock it }
        val ready = CompletableDeferred<Boolean>()
        val created = withContext(Dispatchers.Main) {
            TextToSpeech(appContext) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
        }
        created.setOnUtteranceProgressListener(utteranceListener)
        val ok = withTimeoutOrNull(INIT_TIMEOUT_MS) { ready.await() } ?: false
        if (!ok) {
            withContext(Dispatchers.Main) { created.shutdown() }
            return@withLock null
        }
        engine = created
        created
    }

    private companion object {
        const val INIT_TIMEOUT_MS = 5_000L
    }
}
