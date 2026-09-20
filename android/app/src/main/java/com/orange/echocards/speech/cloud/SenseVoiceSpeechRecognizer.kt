package com.orange.echocards.speech.cloud

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import com.orange.echocards.BuildConfig
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * SenseVoice 云端识别适配器：录音 → 静音检测 → 上传 → 转写，实现 [SpeechRecognizer]。
 *
 * 与 [com.orange.echocards.speech.android.AndroidSpeechRecognizer] 的差异：SenseVoice 是批量接口，
 * 不产生 PartialResult；本实现自行用 [SilenceDetector] 判定「读完」后上传并发出唯一的 [SpeechRecognizerEvent.FinalResult]。
 * 终止事件由 [OperationGate] 保证每个 operation 只发一次，迟到回调被丢弃。
 */
internal class SenseVoiceSpeechRecognizer(
    private val client: SenseVoiceClient,
) : SpeechRecognizer {

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

    /** 被忽略的迟到事件数，探针页用。 */
    val ignoredEvents: Int get() = gate.ignored

    override suspend fun start(request: RecognitionRequest) {
        if (BuildConfig.SENSEVOICE_API_KEY.isBlank()) {
            _events.tryEmit(
                SpeechRecognizerEvent.Error(
                    request.operationId,
                    SpeechError(SpeechErrorCode.RECOGNITION_UNAVAILABLE, "未配置 SenseVoice API Key（local.properties 里设置 sensevoice.apiKey）"),
                )
            )
            return
        }
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
        val recorder = try {
            createRecorder()
        } catch (e: SecurityException) {
            if (gate.acceptTerminal(operationId)) {
                _events.tryEmit(SpeechRecognizerEvent.Error(operationId, SpeechError(SpeechErrorCode.PERMISSION_DENIED, "没有麦克风权限")))
            }
            return
        } catch (e: Throwable) {
            if (gate.acceptTerminal(operationId)) {
                _events.tryEmit(SpeechRecognizerEvent.Error(operationId, SpeechError(SpeechErrorCode.INTERNAL_ERROR, "无法初始化麦克风", e.message, recoverable = true)))
            }
            return
        }

        val detector = SilenceDetector(SPEECH_START_RMS, SPEECH_END_RMS, TRAILING_SILENCE_MS)
        val pcm = ArrayList<Short>(SAMPLE_RATE * MAX_RECORD_MS.toInt() / 1000)
        val buffer = ShortArray(FRAME_SAMPLES)
        var speechStarted = false

        try {
            recorder.startRecording()
            val startedAt = System.currentTimeMillis()
            loop@ while (true) {
                if (cancelRequested.get()) return
                if (finishRequested.get()) break@loop
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    if (cancelRequested.get()) return
                    if (finishRequested.get()) break@loop
                    continue
                }
                for (i in 0 until read) pcm.add(buffer[i])
                val now = System.currentTimeMillis() - startedAt
                val state = detector.accept(rms(buffer, read), now)
                if (state == VadState.SPEAKING && !speechStarted) {
                    speechStarted = true
                    if (gate.accept(operationId)) _events.tryEmit(SpeechRecognizerEvent.SpeechStarted(operationId))
                }
                when {
                    state == VadState.ENDED -> break@loop
                    now >= MAX_RECORD_MS -> break@loop
                    !speechStarted && now >= NO_SPEECH_TIMEOUT_MS -> {
                        if (gate.acceptTerminal(operationId)) {
                            _events.tryEmit(SpeechRecognizerEvent.Error(operationId, SpeechError(SpeechErrorCode.NO_SPEECH, "没有听到声音", recoverable = true)))
                        }
                        return
                    }
                }
            }
        } catch (e: Throwable) {
            if (gate.acceptTerminal(operationId)) {
                _events.tryEmit(SpeechRecognizerEvent.Error(operationId,
                    SpeechError(SpeechErrorCode.INTERNAL_ERROR, "录音失败：${e.message ?: e::class.simpleName}", e.message, recoverable = true)))
            }
            return
        } finally {
            runCatching { recorder.release() }
        }

        if (cancelRequested.get()) return
        if (gate.accept(operationId)) _events.tryEmit(SpeechRecognizerEvent.SpeechEnded(operationId))

        val transcript = try {
            client.transcribe(WavEncoder.pcm16ToWav(pcm.toShortArray(), SAMPLE_RATE))
        } catch (t: Throwable) {
            if (gate.acceptTerminal(operationId)) {
                _events.tryEmit(SpeechRecognizerEvent.Error(operationId, SenseVoiceErrorMapper.fromThrowable(t)))
            }
            return
        }
        if (gate.acceptTerminal(operationId)) {
            _events.tryEmit(SpeechRecognizerEvent.FinalResult(operationId, transcript))
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

    private fun rms(buffer: ShortArray, length: Int): Int {
        var sum = 0L
        for (i in 0 until length) {
            val v = buffer[i].toInt()
            sum += v * v
        }
        return sqrt(sum.toDouble() / length).toInt()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 512
        const val SPEECH_START_RMS = 500
        const val SPEECH_END_RMS = 300
        const val TRAILING_SILENCE_MS = 800L
        const val MAX_RECORD_MS = 30_000L
        const val NO_SPEECH_TIMEOUT_MS = 10_000L
    }
}
