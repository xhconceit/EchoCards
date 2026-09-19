package com.orange.echocards

import com.orange.echocards.domain.speech.SpeechErrorCode
import com.orange.echocards.speech.cloud.SenseVoiceApiException
import com.orange.echocards.speech.cloud.SenseVoiceErrorMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SenseVoiceErrorMapperTest {

    @Test
    fun ioException_mapsToNetworkError_recoverable() {
        val error = SenseVoiceErrorMapper.fromThrowable(IOException("timeout"))
        assertEquals(SpeechErrorCode.NETWORK_ERROR, error.code)
        assertTrue(error.recoverable)
    }

    @Test
    fun genericThrowable_mapsToInternalError_recoverable() {
        val error = SenseVoiceErrorMapper.fromThrowable(RuntimeException("boom"))
        assertEquals(SpeechErrorCode.INTERNAL_ERROR, error.code)
        assertTrue(error.recoverable)
    }

    @Test
    fun apiException5xx_isRecoverable() {
        val error = SenseVoiceErrorMapper.fromThrowable(SenseVoiceApiException(500, "server error"))
        assertEquals(SpeechErrorCode.INTERNAL_ERROR, error.code)
        assertTrue(error.recoverable)
    }

    @Test
    fun apiException4xx_isNotRecoverable() {
        val error = SenseVoiceErrorMapper.fromThrowable(SenseVoiceApiException(401, "bad key"))
        assertEquals(SpeechErrorCode.INTERNAL_ERROR, error.code)
        assertEquals(false, error.recoverable)
    }
}
