package com.orange.echocards.speech.cloud

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 把 16-bit mono PCM 采样封装成带 44 字节头的 WAV，供 SenseVoice 上传。
 *
 * 纯 Kotlin，可在 JVM 测试。
 */
object WavEncoder {
    fun pcm16ToWav(pcm: ShortArray, sampleRate: Int): ByteArray {
        val dataSize = pcm.size * 2
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)                     // fmt chunk 大小
        buffer.putShort(1)                    // PCM
        buffer.putShort(1)                    // mono
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * 2)         // byte rate = sampleRate * channels * bytesPerSample
        buffer.putShort(2)                    // block align
        buffer.putShort(16)                   // bits per sample
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)
        for (sample in pcm) buffer.putShort(sample)
        return buffer.array()
    }
}
