package com.orange.echocards.speech.android

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.speech.SpeechRecognizer as SystemRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import com.orange.echocards.domain.speech.MicrophonePermission
import com.orange.echocards.domain.speech.SpeechCapabilities
import com.orange.echocards.domain.speech.SpeechCapabilityService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * 设备语音能力与麦克风权限，见 docs/reference/speech-api.md 第 4、11 节。
 *
 * 权限请求由 UI 层承接 Activity Result 流程，领域接口不持有 Activity：
 * UI 通过 [permissionRequester] 注入“发起请求并等待结果”的挂起函数。
 */
internal class AndroidSpeechCapabilityService(context: Context) : SpeechCapabilityService {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 由 UI 层注入；返回是否已授权。未注入时按拒绝处理。 */
    var permissionRequester: (suspend () -> Boolean)? = null

    override suspend fun getCapabilities(language: String?): SpeechCapabilities {
        val lang = language ?: DEFAULT_LANGUAGE
        val synthesisLanguageSupported = checkSynthesisLanguage(lang)
        val recognitionAvailable = SystemRecognizer.isRecognitionAvailable(appContext)
        return SpeechCapabilities(
            synthesisAvailable = synthesisLanguageSupported != null,
            recognitionAvailable = recognitionAvailable,
            microphonePermission = currentMicrophonePermission(),
            supportedRecognitionLanguages = if (recognitionAvailable) listOf(lang) else emptyList(),
            supportedSynthesisLanguages = if (synthesisLanguageSupported == true) listOf(lang) else emptyList(),
            onDeviceRecognitionAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SystemRecognizer.isOnDeviceRecognitionAvailable(appContext),
        )
    }

    override suspend fun requestMicrophonePermission(): MicrophonePermission {
        if (currentMicrophonePermission() == MicrophonePermission.GRANTED) return MicrophonePermission.GRANTED
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
        val granted = permissionRequester?.invoke() ?: false
        return if (granted) MicrophonePermission.GRANTED else currentMicrophonePermission()
    }

    private fun currentMicrophonePermission(): MicrophonePermission {
        val granted = ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return MicrophonePermission.GRANTED
        val restricted = runCatching {
            val manager = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            manager?.getPermissionPolicy(null) == DevicePolicyManager.PERMISSION_POLICY_AUTO_DENY
        }.getOrDefault(false)
        if (restricted) return MicrophonePermission.RESTRICTED
        return if (prefs.getBoolean(KEY_ASKED, false)) MicrophonePermission.DENIED else MicrophonePermission.UNDETERMINED
    }

    /** 用一次性的 TTS 实例判断语言可用性；引擎不可用时返回 null。 */
    private suspend fun checkSynthesisLanguage(language: String): Boolean? = withContext(Dispatchers.Main) {
        val ready = CompletableDeferred<Boolean>()
        val tts = TextToSpeech(appContext) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
        val engineReady = withTimeoutOrNull(PROBE_TIMEOUT_MS) { ready.await() } ?: false
        val supported = if (engineReady) {
            tts.isLanguageAvailable(Locale.forLanguageTag(language)) >= TextToSpeech.LANG_AVAILABLE
        } else {
            null
        }
        runCatching { tts.shutdown() }
        supported
    }

    internal companion object {
        const val DEFAULT_LANGUAGE = "zh-CN"
        private const val PREFS = "speech_permission"
        private const val KEY_ASKED = "microphone_asked"
        private const val PROBE_TIMEOUT_MS = 5_000L
    }
}
