package com.orange.echocards

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 真机音频取证：一边执行 [action]，一边用麦克风采样，返回采样峰值（Short 范围）。
 *
 * 只断言“引擎报告完成”或“音频流活跃”都不足以证明真的出声（音量过低、被系统策略压掉时
 * 那两条照样成立），所以关键用例用麦克风峰值作为客观证据。
 */
internal suspend fun CoroutineScope.recordMicPeak(limitMs: Long, action: suspend () -> Unit): Int {
    val peak = AtomicInteger(0)
    val recording = launch(Dispatchers.IO) {
        val sampleRate = 16_000
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer * 2,
        )
        val buffer = ShortArray(minBuffer)
        try {
            recorder.startRecording()
            while (isActive) {
                val read = recorder.read(buffer, 0, buffer.size)
                for (i in 0 until read) {
                    val value = kotlin.math.abs(buffer[i].toInt())
                    if (value > peak.get()) peak.set(value)
                }
            }
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
    }
    val watchdog = launch { delay(limitMs); recording.cancel() }
    action()
    watchdog.cancel()
    recording.cancelAndJoin()
    return peak.get()
}
