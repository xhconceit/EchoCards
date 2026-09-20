package com.orange.echocards.speech.vosk

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import com.orange.echocards.domain.speech.OperationGate
import com.orange.echocards.domain.speech.RecognitionRequest
import com.orange.echocards.domain.speech.SpeechError
import com.orange.echocards.domain.speech.SpeechErrorCode
import com.orange.echocards.domain.speech.SpeechRecognizer
import com.orange.echocards.domain.speech.SpeechRecognizerEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.vosk.Recognizer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Vosk 端侧离线识别适配器：实时喂 `acceptWaveForm`，轮询部分结果发 [SpeechRecognizerEvent.PartialResult]，
 * 端点/停止/超时后取最终结果发 [SpeechRecognizerEvent.FinalResult]。完全离线，不依赖网络。
 *
 * 终止事件由 [OperationGate] 保证每个 operation 只发一次；录音/加载异常统一兜底为 Error，不崩 App。
 */
internal class VoskSpeechRecognizer(context: Context) : SpeechRecognizer {

    private val appContext = context.applicationContext
    private val _events = MutableSharedFlow<SpeechRecognizerEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<SpeechRecognizerEvent> = _events.asSharedFlow()

    private val gate = OperationGate()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val finishRequested = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)
    private var recordJob: Job? = null

    val ignoredEvents: Int get() = gate.ignored

    override suspend fun start(request: RecognitionRequest) {
        gate.begin(request.operationId)
        finishRequested.set(false)
        cancelRequested.set(false)
        _events.tryEmit(SpeechRecognizerEvent.Ready(request.operationId))
        recordJob?.cancel()
        recordJob = scope.launch { runRecognition(request) }
    }

    override suspend fun stop(operationId: String?) {
        val current = gate.activeOperationId ?: return
        if (operationId != null && operationId != current) return
        finishRequested.set(true)
    }

    override suspend fun cancel(operationId: String?) {
        val current = gate.activeOperationId ?: return
        if (operationId != null && operationId != current) return
        cancelRequested.set(true)
        if (gate.acceptTerminal(current)) _events.tryEmit(SpeechRecognizerEvent.Stopped(current))
    }

    override suspend fun dispose() {
        cancelRequested.set(true)
        gate.invalidate()
        recordJob?.cancel()
        recordJob = null
    }

    private suspend fun runRecognition(request: RecognitionRequest) {
        val operationId = request.operationId
        val recognizer = try {
            val model = VoskModelProvider.get(appContext)
            Recognizer(model, SAMPLE_RATE.toFloat()).apply {
                setWords(true)
                setPartialWords(true)
            }
        } catch (e: SecurityException) {
            if (gate.acceptTerminal(operationId)) {
                _events.tryEmit(SpeechRecognizerEvent.Error(operationId, SpeechError(SpeechErrorCode.PERMISSION_DENIED, e.message ?: "没有麦克风权限")))
            }
            return
        } catch (e: Throwable) {
            if (gate.acceptTerminal(operationId)) {
                _events.tryEmit(SpeechRecognizerEvent.Error(operationId, SpeechError(SpeechErrorCode.RECOGNITION_UNAVAILABLE, "离线模型加载失败：${e.message ?: e::class.simpleName}", e.message, recoverable = true)))
            }
            return
        }

        val recorder = try {
            createRecorder()
        } catch (e: SecurityException) {
            runCatching { recognizer.close() }
            if (gate.acceptTerminal(operationId)) {
                _events.tryEmit(SpeechRecognizerEvent.Error(operationId, SpeechError(SpeechErrorCode.PERMISSION_DENIED, e.message ?: "没有麦克风权限")))
            }
            return
        } catch (e: Throwable) {
            runCatching { recognizer.close() }
            if (gate.acceptTerminal(operationId)) {
                _events.tryEmit(SpeechRecognizerEvent.Error(operationId, SpeechError(SpeechErrorCode.INTERNAL_ERROR, "无法初始化麦克风", e.message, recoverable = true)))
            }
            return
        }

        try {
            recorder.startRecording()
            val startedAt = System.currentTimeMillis()
            val buffer = ShortArray(FRAME_SAMPLES)
            var speechStarted = false
            loop@ while (true) {
                if (cancelRequested.get()) return
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    if (cancelRequested.get()) return
                    continue
                }
                val endOfSpeech = recognizer.acceptWaveForm(buffer, read)
                val partial = VoskResultParser.partial(recognizer.partialResult)
                if (partial.isNotEmpty()) {
                    if (!speechStarted) {
                        speechStarted = true
                        if (gate.accept(operationId)) _events.tryEmit(SpeechRecognizerEvent.SpeechStarted(operationId))
                    }
                    if (gate.accept(operationId)) _events.tryEmit(SpeechRecognizerEvent.PartialResult(operationId, partial))
                }
                val now = System.currentTimeMillis() - startedAt
                if (endOfSpeech || finishRequested.get() || now >= MAX_RECORD_MS) {
                    // 与系统识别器一致：用户停下来时先发 SpeechEnded，上层用它启动静音确认
                    if (gate.accept(operationId)) _events.tryEmit(SpeechRecognizerEvent.SpeechEnded(operationId))
                    break@loop
                }
            }
            if (cancelRequested.get()) return

            val text = VoskResultParser.finalText(recognizer.finalResult)
            if (text.isBlank()) {
                if (gate.acceptTerminal(operationId)) {
                    _events.tryEmit(SpeechRecognizerEvent.Error(operationId, SpeechError(SpeechErrorCode.NO_SPEECH, "没有识别到内容", recoverable = true)))
                }
                return
            }
            if (gate.acceptTerminal(operationId)) {
                _events.tryEmit(SpeechRecognizerEvent.FinalResult(operationId, text))
            }
        } catch (e: Throwable) {
            if (gate.acceptTerminal(operationId)) {
                _events.tryEmit(SpeechRecognizerEvent.Error(operationId, SpeechError(SpeechErrorCode.INTERNAL_ERROR, "录音失败：${e.message ?: e::class.simpleName}", e.message, recoverable = true)))
            }
        } finally {
            runCatching { recognizer.close() }
            runCatching { recorder.release() }
        }
    }

    /** 调用方必须在 try/catch 里处理 SecurityException，见下面的录制流程。 */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun createRecorder(): AudioRecord {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) throw IllegalStateException("设备不支持 16kHz mono PCM 录音")
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer * 2,
        )
        if (recorder.state == AudioRecord.STATE_UNINITIALIZED) {
            recorder.release()
            throw SecurityException("麦克风未就绪，请检查录音权限")
        }
        return recorder
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 4096
        const val MAX_RECORD_MS = 30_000L
    }
}
