package com.orange.echocards.speech.android

import android.content.Context
import com.orange.echocards.domain.speech.SpeechServices

/**
 * 组装 Android 语音适配器，见 docs/reference/speech-api.md 第 13 节。
 * 后续节点的 Learning Engine 从这里拿到统一的领域接口，不直接接触 Android SDK。
 */
object AndroidSpeechServices {

    /**
     * @param permissionRequester UI 层注入的麦克风权限请求（Activity Result 流程），
     *   领域接口不持有 Activity，所以由调用方传进来。
     */
    fun create(context: Context, permissionRequester: (suspend () -> Boolean)? = null): SpeechServices {
        val appContext = context.applicationContext
        val capabilities = AndroidSpeechCapabilityService(appContext).apply {
            this.permissionRequester = permissionRequester
        }
        return SpeechServices(
            player = AndroidSpeechPlayer(appContext),
            recognizer = AndroidSpeechRecognizer(appContext),
            capabilities = capabilities,
            audioFocus = AndroidAudioFocusController(appContext),
        )
    }
}
