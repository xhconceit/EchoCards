package com.orange.echocards.speech.cloud

import com.orange.echocards.domain.speech.SpeechError
import com.orange.echocards.domain.speech.SpeechErrorCode
import java.io.IOException

/**
 * 把 SenseVoice 上传/解析过程中抛出的异常映射为领域 [SpeechError]。
 *
 * 纯 Kotlin，可在 JVM 测试。
 */
object SenseVoiceErrorMapper {
    fun fromThrowable(t: Throwable): SpeechError = when (t) {
        is SenseVoiceApiException -> SpeechError(
            code = SpeechErrorCode.INTERNAL_ERROR,
            message = t.message ?: "识别服务返回错误",
            nativeCode = t.statusCode.toString(),
            recoverable = t.statusCode >= 500,
        )
        is IOException -> SpeechError(
            code = SpeechErrorCode.NETWORK_ERROR,
            message = "网络不可用，请稍后重试",
            nativeCode = t.message,
            recoverable = true,
        )
        else -> SpeechError(
            code = SpeechErrorCode.INTERNAL_ERROR,
            message = "识别失败：${t.message ?: t::class.simpleName}",
            nativeCode = t.message,
            recoverable = true,
        )
    }
}
