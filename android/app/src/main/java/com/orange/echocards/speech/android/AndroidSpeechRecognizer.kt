package com.orange.echocards.speech.android

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer as SystemRecognizer
import com.orange.echocards.domain.speech.OperationGate
import com.orange.echocards.domain.speech.RecognitionRequest
import com.orange.echocards.domain.speech.SpeechError
import com.orange.echocards.domain.speech.SpeechErrorCode
import com.orange.echocards.domain.speech.SpeechRecognizer
import com.orange.echocards.domain.speech.SpeechRecognizerEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

/**
 * Android 语音识别适配器，见 docs/reference/speech-api.md 第 6、10 节。
 *
 * - 每个识别会话都新建 [SystemRecognizer]，结束后销毁：系统实现在 cancel/error 之后复用并不可靠。
 * - SystemRecognizer 必须在主线程创建和调用。
 * - 回调只带会话内的 operationId，取消或用例切换后由 [OperationGate] 丢弃迟到结果。
 * - `stopListening()` 保留最终结果；`cancel()` 立即丢弃并发出 Stopped。
 */
internal class AndroidSpeechRecognizer(context: Context) : SpeechRecognizer {

    private val appContext = context.applicationContext
    private val _events = MutableSharedFlow<SpeechRecognizerEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<SpeechRecognizerEvent> = _events.asSharedFlow()

    private val gate = OperationGate()
    private var session: SystemRecognizer? = null

    /** 被忽略的迟到事件数，探针页与测试用。 */
    val ignoredEvents: Int get() = gate.ignored

    override suspend fun start(request: RecognitionRequest) {
        if (!SystemRecognizer.isRecognitionAvailable(appContext)) {
            _events.tryEmit(SpeechRecognizerEvent.Error(request.operationId,
                SpeechError(SpeechErrorCode.RECOGNITION_UNAVAILABLE, "设备没有可用的语音识别服务")))
            return
        }
        gate.begin(request.operationId)
        withContext(Dispatchers.Main) {
            destroySession()
            val recognizer = createRecognizer(request)
            recognizer.setRecognitionListener(listenerFor(request.operationId, recognizer))
            session = recognizer
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, request.language)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, request.partialResults)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, request.preferOnDevice)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            val started = runCatching { recognizer.startListening(intent) }
            started.onFailure { error ->
                if (gate.acceptTerminal(request.operationId)) {
                    _events.tryEmit(SpeechRecognizerEvent.Error(request.operationId,
                        SpeechError(SpeechErrorCode.INTERNAL_ERROR, "无法启动识别", error.message, recoverable = true)))
                }
            }
        }
    }

    override suspend fun stop(operationId: String?) {
        val current = gate.activeOperationId ?: return
        if (operationId != null && operationId != current) return
        withContext(Dispatchers.Main) { runCatching { session?.stopListening() } }
    }

    override suspend fun cancel(operationId: String?) {
        val current = gate.activeOperationId ?: return
        if (operationId != null && operationId != current) return
        withContext(Dispatchers.Main) { runCatching { session?.cancel() } }
        if (gate.acceptTerminal(current)) _events.tryEmit(SpeechRecognizerEvent.Stopped(current))
    }

    override suspend fun dispose() {
        gate.invalidate()
        withContext(Dispatchers.Main) { destroySession() }
    }

    private fun createRecognizer(request: RecognitionRequest): SystemRecognizer =
        if (request.preferOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SystemRecognizer.isOnDeviceRecognitionAvailable(appContext)) {
            SystemRecognizer.createOnDeviceSpeechRecognizer(appContext)
        } else {
            SystemRecognizer.createSpeechRecognizer(appContext)
        }

    private fun destroySession() {
        val current = session ?: return
        session = null
        runCatching {
            current.cancel()
            current.destroy()
        }
    }

    private fun listenerFor(operationId: String, recognizer: SystemRecognizer) = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            if (gate.accept(operationId)) _events.tryEmit(SpeechRecognizerEvent.Ready(operationId))
        }

        override fun onBeginningOfSpeech() {
            if (gate.accept(operationId)) _events.tryEmit(SpeechRecognizerEvent.SpeechStarted(operationId))
        }

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (gate.accept(operationId)) _events.tryEmit(SpeechRecognizerEvent.SpeechEnded(operationId))
        }

        override fun onError(error: Int) {
            if (gate.acceptTerminal(operationId)) {
                _events.tryEmit(SpeechRecognizerEvent.Error(operationId, SpeechErrorMapper.fromRecognition(error)))
            }
            releaseIfCurrent(recognizer)
        }

        override fun onResults(results: Bundle?) {
            val transcript = transcriptOf(results)
            if (gate.acceptTerminal(operationId)) {
                _events.tryEmit(SpeechRecognizerEvent.FinalResult(operationId, transcript))
            }
            releaseIfCurrent(recognizer)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val transcript = transcriptOf(partialResults)
            if (transcript.isNotEmpty() && gate.accept(operationId)) {
                _events.tryEmit(SpeechRecognizerEvent.PartialResult(operationId, transcript))
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun releaseIfCurrent(recognizer: SystemRecognizer) {
        if (session !== recognizer) return
        session = null
        runCatching {
            recognizer.cancel()
            recognizer.destroy()
        }
    }

    private fun transcriptOf(bundle: Bundle?): String =
        bundle?.getStringArrayList(SystemRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
}
