package com.orange.echocards

import com.orange.echocards.speech.cloud.WavEncoder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavEncoderTest {

    @Test
    fun producesRiffWaveHeaderAndPcmData() {
        val pcm = shortArrayOf(0, 1, -1, 32767, -32768)
        val wav = WavEncoder.pcm16ToWav(pcm, 16_000)

        assertEquals(44 + pcm.size * 2, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))

        val buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16_000, buf.getInt(24))
        assertEquals(pcm.size * 2, buf.getInt(40))
        assertEquals(0, buf.getShort(44).toInt())
    }
}
