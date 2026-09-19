package com.orange.echocards.domain.speech

import kotlinx.coroutines.flow.Flow

/**
 * 识别能力，见 docs/reference/speech-api.md 第 6 节。
 *
 * `stop()` 与 `cancel()` 的区别：stop 允许系统处理已收到的音频并可能返回最终结果；
 * cancel 立即丢弃结果。即使调用了 cancel，调用方仍必须靠 operationId 忽略迟到回调。
 */
interface SpeechRecognizer {
    val events: Flow<SpeechRecognizerEvent>

    suspend fun start(request: RecognitionRequest)

    suspend fun stop(operationId: String? = null)

    suspend fun cancel(operationId: String? = null)

    suspend fun dispose()
}
