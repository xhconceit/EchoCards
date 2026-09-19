package com.orange.echocards.speech.cloud

/**
 * 静音检测（VAD）：判定「用户是否开始说话」以及「说完后是否静音到可以结束」。
 *
 * 纯 Kotlin，不依赖 Android，可在 JVM 测试。状态流转：
 *
 * - [VadState.IDLE]：尚未检测到说话，RMS 超过 [speechStartRms] 进入 [VadState.SPEAKING]。
 * - [VadState.SPEAKING]：说话中；RMS 低于 [speechEndRms] 累计满 [trailingSilenceMs] 进入 [VadState.ENDED]，
 *   期间若再次超过阈值则重新计时。
 * - [VadState.ENDED]：终态，不再变化。
 */
class SilenceDetector(
    private val speechStartRms: Int,
    private val speechEndRms: Int,
    private val trailingSilenceMs: Long,
) {
    private var state = VadState.IDLE
    private var silenceSinceMs: Long? = null

    /** 喂入一帧的 RMS 与单调递增的时间戳（毫秒），返回当前状态。 */
    fun accept(rms: Int, timestampMs: Long): VadState {
        when (state) {
            VadState.IDLE -> {
                if (rms > speechStartRms) {
                    state = VadState.SPEAKING
                    silenceSinceMs = null
                }
            }
            VadState.SPEAKING -> {
                if (rms < speechEndRms) {
                    val since = silenceSinceMs ?: timestampMs
                    silenceSinceMs = since
                    if (timestampMs - since >= trailingSilenceMs) {
                        state = VadState.ENDED
                    }
                } else {
                    silenceSinceMs = null
                }
            }
            VadState.ENDED -> Unit
        }
        return state
    }
}

enum class VadState { IDLE, SPEAKING, ENDED }
