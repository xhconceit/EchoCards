package com.orange.echocards.domain.speech

/**
 * 设备语音能力与麦克风权限，见 docs/reference/speech-api.md 第 4 节。
 *
 * 只有用户进入自动跟读后才请求麦克风权限；自动播放不请求。
 */
interface SpeechCapabilityService {
    suspend fun getCapabilities(language: String? = null): SpeechCapabilities

    suspend fun requestMicrophonePermission(): MicrophonePermission
}
