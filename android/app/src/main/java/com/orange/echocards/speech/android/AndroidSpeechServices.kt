package com.orange.echocards.speech.android

import android.content.Context
import com.orange.echocards.domain.speech.SpeechRecognizer
import com.orange.echocards.domain.speech.SpeechServices
import com.orange.echocards.speech.vosk.VoskSpeechRecognizer

/**
 * 组装 Android 语音适配器，见 docs/reference/speech-api.md 第 13 节。
 * 后续节点的 Learning Engine 从这里拿到统一的领域接口，不直接接触 Android SDK。
 */
object AndroidSpeechServices {

    /**
     * 系统语音服务：朗读与识别都用 Android 系统实现。探针页用它对比设备能力。
     *
     * @param permissionRequester UI 层注入的麦克风权限请求（Activity Result 流程），
     *   领域接口不持有 Activity，所以由调用方传进来。
     */
    fun create(context: Context, permissionRequester: (suspend () -> Boolean)? = null): SpeechServices =
        assemble(context, permissionRequester) { AndroidSpeechRecognizer(it) }

    /**
     * 学习页的语音服务：朗读用系统 TTS，识别用 Vosk 端侧离线模型，见 ADR-002。
     *
     * 离线识别不依赖网络和厂商服务，并且提供真实的部分结果，自动跟读才能做覆盖率判断。
     * 模型首次使用时会从 assets 解压到 filesDir，调用方可以先用
     * [com.orange.echocards.speech.vosk.VoskModelProvider.get] 预热。
     */
    fun createWithOfflineRecognition(
        context: Context,
        permissionRequester: (suspend () -> Boolean)? = null,
    ): SpeechServices = assemble(context, permissionRequester) { VoskSpeechRecognizer(it) }

    private fun assemble(
        context: Context,
        permissionRequester: (suspend () -> Boolean)?,
        recognizerFactory: (Context) -> SpeechRecognizer,
    ): SpeechServices {
        val appContext = context.applicationContext
        val capabilities = AndroidSpeechCapabilityService(appContext).apply {
            this.permissionRequester = permissionRequester
        }
        return SpeechServices(
            player = AndroidSpeechPlayer(appContext),
            recognizer = recognizerFactory(appContext),
            capabilities = capabilities,
            audioFocus = AndroidAudioFocusController(appContext),
        )
    }
}
