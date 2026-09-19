package com.orange.echocards

import com.orange.echocards.domain.speech.AudioInterruptionEvent
import com.orange.echocards.domain.speech.MicrophonePermission
import com.orange.echocards.domain.speech.OperationGate
import com.orange.echocards.domain.speech.RecognitionRequest
import com.orange.echocards.domain.speech.SpeakRequest
import com.orange.echocards.domain.speech.SpeechErrorCode
import com.orange.echocards.domain.speech.SpeechPlayerEvent
import com.orange.echocards.domain.speech.SpeechRecognizerEvent
import com.orange.echocards.speech.mock.MockAudioFocusController
import com.orange.echocards.speech.mock.MockScenario
import com.orange.echocards.speech.mock.MockSpeechRecognizer
import com.orange.echocards.speech.mock.MockSpeechServices
import com.orange.echocards.speech.mock.MockSpeechPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模拟语音服务与领域契约的一致性测试，见 docs/reference/speech-api.md 第 5、6、13 节。
 *
 * 这些用例是节点 5/6 的基础：Learning Engine 只依赖领域接口，因此失败场景（无语音、权限拒绝、
 * 服务缺失、平台迟到回调、重复完成）必须先在这里被固定下来。
 */
class SpeechContractTest {

    @Test
    fun happyPathProducesStartedThenCompleted() = runBlocking {
        val player = MockSpeechPlayer(this)
        val events = mutableListOf<SpeechPlayerEvent>()
        val collector = collect(player.events, events)

        player.speak(SpeakRequest("op", "惯性是物体保持原有运动状态的性质", "zh-CN", 1.0))
        delay(200)
        collector.cancel()

        assertEquals(listOf(SpeechPlayerEvent.Started::class, SpeechPlayerEvent.Completed::class),
            events.map { it::class })
    }

    @Test
    fun recognitionDeliversPartialResultsThenFinal() = runBlocking {
        val recognizer = MockSpeechRecognizer(this)
        val events = mutableListOf<SpeechRecognizerEvent>()
        val collector = collect(recognizer.events, events)

        recognizer.start(RecognitionRequest("op", "zh-CN", partialResults = true, preferOnDevice = false))
        delay(400)
        collector.cancel()

        assertTrue("应先 ready", events.first() is SpeechRecognizerEvent.Ready)
        assertEquals(listOf("惯性", "惯性是物体"), events.filterIsInstance<SpeechRecognizerEvent.PartialResult>().map { it.transcript })
        val final = events.filterIsInstance<SpeechRecognizerEvent.FinalResult>().single()
        assertEquals("惯性是物体保持原有运动状态的性质", final.transcript)
        assertEquals("op", final.operationId)
    }

    @Test
    fun noSpeechIsReportedAsNoSpeechError() = runBlocking {
        val recognizer = MockSpeechRecognizer(this, MockScenario.NoSpeech)
        val events = mutableListOf<SpeechRecognizerEvent>()
        val collector = collect(recognizer.events, events)

        recognizer.start(RecognitionRequest("op", "zh-CN", partialResults = true, preferOnDevice = false))
        delay(300)
        collector.cancel()

        val error = events.filterIsInstance<SpeechRecognizerEvent.Error>().single()
        assertEquals(SpeechErrorCode.NO_SPEECH, error.error.code)
        assertTrue("无语音应可重试", error.error.recoverable)
    }

    @Test
    fun permissionDeniedIsReportedByCapabilitiesAndRecognizer() = runBlocking {
        val services = MockSpeechServices.create(this, MockScenario.PermissionDenied)
        val events = mutableListOf<SpeechRecognizerEvent>()
        val collector = collect(services.recognizer.events, events)

        assertEquals(MicrophonePermission.DENIED, services.capabilities.getCapabilities("zh-CN").microphonePermission)
        assertEquals(MicrophonePermission.DENIED, services.capabilities.requestMicrophonePermission())

        services.recognizer.start(RecognitionRequest("op", "zh-CN", partialResults = false, preferOnDevice = false))
        delay(100)
        collector.cancel()

        assertEquals(SpeechErrorCode.PERMISSION_DENIED,
            events.filterIsInstance<SpeechRecognizerEvent.Error>().single().error.code)
    }

    @Test
    fun missingServiceIsReportedAsUnavailable() = runBlocking {
        val services = MockSpeechServices.create(this, MockScenario.ServiceUnavailable)
        val capabilities = services.capabilities.getCapabilities("zh-CN")

        assertFalse("缺少服务时不应报告朗读可用", capabilities.synthesisAvailable)
        assertFalse("缺少服务时不应报告识别可用", capabilities.recognitionAvailable)
        assertTrue(capabilities.supportedRecognitionLanguages.isEmpty())

        val events = mutableListOf<SpeechRecognizerEvent>()
        val collector = collect(services.recognizer.events, events)
        services.recognizer.start(RecognitionRequest("op", "zh-CN", partialResults = false, preferOnDevice = false))
        delay(100)
        collector.cancel()

        assertEquals(SpeechErrorCode.RECOGNITION_UNAVAILABLE,
            events.filterIsInstance<SpeechRecognizerEvent.Error>().single().error.code)
    }

    @Test
    fun recognitionFailureIsRecoverable() = runBlocking {
        val recognizer = MockSpeechRecognizer(this, MockScenario.RecognitionFailure)
        val events = mutableListOf<SpeechRecognizerEvent>()
        val collector = collect(recognizer.events, events)

        recognizer.start(RecognitionRequest("op", "zh-CN", partialResults = false, preferOnDevice = false))
        delay(300)
        collector.cancel()

        val error = events.filterIsInstance<SpeechRecognizerEvent.Error>().single()
        assertEquals(SpeechErrorCode.INTERNAL_ERROR, error.error.code)
        assertTrue(error.error.recoverable)
    }

    /** 平台会送出取消后的迟到结果；上层必须靠 operationId 丢弃它。 */
    @Test
    fun lateResultAfterCancelIsDroppedByConsumerGate() = runBlocking {
        val recognizer = MockSpeechRecognizer(this, MockScenario.LateResultAfterCancel)
        val gate = OperationGate()
        val accepted = mutableListOf<SpeechRecognizerEvent>()
        val collector = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            recognizer.events.collect { event ->
                if (event is SpeechRecognizerEvent.FinalResult && gate.acceptTerminal(event.operationId)) {
                    accepted += event
                }
            }
        }

        gate.begin("op")
        recognizer.start(RecognitionRequest("op", "zh-CN", partialResults = true, preferOnDevice = false))
        delay(150)
        recognizer.cancel("op")
        gate.invalidate()
        delay(200)
        collector.cancel()

        assertTrue("取消后的迟到结果不能被采纳：$accepted", accepted.isEmpty())
        assertTrue("迟到回调应被记为忽略", gate.ignored > 0)
    }

    /** 重复完成回调只允许被采纳一次。 */
    @Test
    fun duplicateCompletionIsAcceptedOnceOnly() = runBlocking {
        val player = MockSpeechPlayer(this, speakDurationMs = 10)
        player.duplicateCompletion = true
        val gate = OperationGate()
        var accepted = 0
        val collector = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            player.events.collect { event ->
                if (event is SpeechPlayerEvent.Completed && gate.acceptTerminal(event.operationId)) accepted++
            }
        }

        gate.begin("op")
        player.speak(SpeakRequest("op", "惯性", "zh-CN", 1.0))
        delay(200)
        collector.cancel()

        assertEquals("重复完成只应采纳一次", 1, accepted)
    }

    @Test
    fun audioFocusMockTracksConfigurationAndInterruptions() = runBlocking {
        val audioFocus = MockAudioFocusController()
        val seen = mutableListOf<AudioInterruptionEvent>()
        val collector = collect(audioFocus.interruptions, seen)

        audioFocus.configureForPlayback()
        audioFocus.configureForRecognition()
        audioFocus.emit(AudioInterruptionEvent.InterruptionStarted("AUDIOFOCUS_LOSS_TRANSIENT"))
        delay(100)
        audioFocus.deactivate()
        collector.cancel()

        assertTrue(audioFocus.playbackConfigured)
        assertTrue(audioFocus.recognitionConfigured)
        assertTrue(audioFocus.deactivated)
        assertEquals(1, seen.size)
    }

    /** UNDISPATCHED：先完成订阅再返回，避免 SharedFlow 在订阅前丢事件。 */
    private fun <T> CoroutineScope.collect(flow: Flow<T>, into: MutableList<T>): Job =
        launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { flow.collect { into += it } }
}
