package com.orange.echocards.speech.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.orange.echocards.domain.speech.AudioFocusController
import com.orange.echocards.domain.speech.AudioInterruptionEvent
import com.orange.echocards.domain.speech.AudioRoute
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 音频焦点与中断适配器，见 docs/reference/speech-api.md 第 8 节。
 *
 * 即使系统提示 shouldResume = true，也只发出中断结束事件，是否继续由上层（Engine）决定：
 * App 不自动重新开启麦克风。
 */
internal class AndroidAudioFocusController(context: Context) : AudioFocusController {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val _interruptions = MutableSharedFlow<AudioInterruptionEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val interruptions: Flow<AudioInterruptionEvent> = _interruptions.asSharedFlow()

    private var focusRequest: AudioFocusRequest? = null
    private var holding = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> {
                holding = false
                _interruptions.tryEmit(AudioInterruptionEvent.InterruptionStarted(reasonOf(change)))
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                holding = true
                _interruptions.tryEmit(AudioInterruptionEvent.InterruptionEnded(shouldResume = false))
            }
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = emitRoute()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = emitRoute()
    }

    override suspend fun configureForPlayback() {
        registerDeviceCallback()
        requestFocus(AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_SPEECH)
    }

    override suspend fun configureForRecognition() {
        registerDeviceCallback()
        requestFocus(AudioAttributes.USAGE_ASSISTANT, AudioAttributes.CONTENT_TYPE_SPEECH)
    }

    override suspend fun deactivate() {
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        if (!holding) return
        holding = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
    }

    private suspend fun requestFocus(usage: Int, contentType: Int) {
        if (holding) return
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder().setUsage(usage).setContentType(contentType).build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(focusListener)
                .setWillPauseWhenDucked(false)
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        holding = granted
    }

    private fun registerDeviceCallback() {
        runCatching { audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper())) }
    }

    private fun emitRoute() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val route = when {
            devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } ->
                AudioRoute.BLUETOOTH
            devices.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET } ->
                AudioRoute.HEADPHONES
            devices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } -> AudioRoute.SPEAKER
            devices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE } -> AudioRoute.RECEIVER
            else -> AudioRoute.SPEAKER
        }
        _interruptions.tryEmit(AudioInterruptionEvent.RouteChanged(route))
    }

    private fun reasonOf(change: Int): String = when (change) {
        AudioManager.AUDIOFOCUS_LOSS -> "AUDIOFOCUS_LOSS"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "AUDIOFOCUS_LOSS_TRANSIENT"
        else -> "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK"
    }
}
