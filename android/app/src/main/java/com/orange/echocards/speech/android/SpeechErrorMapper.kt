package com.orange.echocards.speech.android

import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import com.orange.echocards.domain.speech.SpeechError
import com.orange.echocards.domain.speech.SpeechErrorCode

/** 把 Android 原生错误码翻译成统一错误码；nativeCode 只用于排查，页面不展示。 */
internal object SpeechErrorMapper {

    fun fromRecognition(error: Int): SpeechError = when (error) {
        SpeechRecognizer.ERROR_AUDIO ->
            SpeechError(SpeechErrorCode.AUDIO_BUSY, "录音出错，请重试", "ERROR_AUDIO", recoverable = true)
        SpeechRecognizer.ERROR_CLIENT ->
            SpeechError(SpeechErrorCode.CANCELLED, "识别已被取消", "ERROR_CLIENT", recoverable = true)
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            SpeechError(SpeechErrorCode.PERMISSION_DENIED, "没有麦克风权限", "ERROR_INSUFFICIENT_PERMISSIONS")
        SpeechRecognizer.ERROR_NETWORK ->
            SpeechError(SpeechErrorCode.NETWORK_ERROR, "识别需要网络，当前网络不可用", "ERROR_NETWORK", recoverable = true)
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            SpeechError(SpeechErrorCode.NETWORK_ERROR, "识别网络超时，请重试", "ERROR_NETWORK_TIMEOUT", recoverable = true)
        SpeechRecognizer.ERROR_NO_MATCH ->
            SpeechError(SpeechErrorCode.NO_SPEECH, "没有听清，请再读一次", "ERROR_NO_MATCH", recoverable = true)
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            SpeechError(SpeechErrorCode.AUDIO_BUSY, "识别服务正忙，请稍后重试", "ERROR_RECOGNIZER_BUSY", recoverable = true)
        SpeechRecognizer.ERROR_SERVER ->
            SpeechError(SpeechErrorCode.INTERNAL_ERROR, "识别服务返回错误", "ERROR_SERVER", recoverable = true)
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            SpeechError(SpeechErrorCode.RECOGNITION_TIMEOUT, "没有检测到说话", "ERROR_SPEECH_TIMEOUT", recoverable = true)
        else ->
            SpeechError(SpeechErrorCode.INTERNAL_ERROR, "识别失败", "ERROR_$error", recoverable = true)
    }

    fun fromSynthesis(errorCode: Int): SpeechError = when (errorCode) {
        TextToSpeech.ERROR_SYNTHESIS ->
            SpeechError(SpeechErrorCode.INTERNAL_ERROR, "朗读失败", "ERROR_SYNTHESIS", recoverable = true)
        TextToSpeech.ERROR_SERVICE ->
            SpeechError(SpeechErrorCode.SYNTHESIS_UNAVAILABLE, "朗读服务不可用", "ERROR_SERVICE")
        TextToSpeech.ERROR_OUTPUT ->
            SpeechError(SpeechErrorCode.INTERNAL_ERROR, "音频输出失败", "ERROR_OUTPUT", recoverable = true)
        TextToSpeech.ERROR_NETWORK ->
            SpeechError(SpeechErrorCode.NETWORK_ERROR, "朗读需要网络，当前网络不可用", "ERROR_NETWORK", recoverable = true)
        TextToSpeech.ERROR_NETWORK_TIMEOUT ->
            SpeechError(SpeechErrorCode.NETWORK_ERROR, "朗读网络超时", "ERROR_NETWORK_TIMEOUT", recoverable = true)
        TextToSpeech.ERROR_NOT_INSTALLED_YET ->
            SpeechError(SpeechErrorCode.SYNTHESIS_UNAVAILABLE, "语音数据尚未下载完成", "ERROR_NOT_INSTALLED_YET", recoverable = true)
        else ->
            SpeechError(SpeechErrorCode.INTERNAL_ERROR, "朗读失败", "ERROR_$errorCode", recoverable = true)
    }
}
