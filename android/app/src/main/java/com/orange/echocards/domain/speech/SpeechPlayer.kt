package com.orange.echocards.domain.speech

import kotlinx.coroutines.flow.Flow

/**
 * 朗读能力，见 docs/reference/speech-api.md 第 5 节。
 *
 * 约定：
 * - `speak()` 只表示请求被系统接受，收到 [SpeechPlayerEvent.Completed] 才算朗读完成。
 * - `stop()` 不能产生 completed；同一操作最多产生一次终止事件。
 * - 所有事件都必须携带原始 operationId。
 */
interface SpeechPlayer {
    val events: Flow<SpeechPlayerEvent>

    suspend fun speak(request: SpeakRequest)

    suspend fun stop(operationId: String? = null)

    suspend fun dispose()
}
