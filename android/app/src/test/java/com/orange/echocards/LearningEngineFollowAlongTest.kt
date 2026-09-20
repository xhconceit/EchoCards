package com.orange.echocards

import com.orange.echocards.domain.learning.AttemptOutcome
import com.orange.echocards.domain.learning.CardFace
import com.orange.echocards.domain.learning.DefaultLearningEngine
import com.orange.echocards.domain.learning.LearningAttemptRecord
import com.orange.echocards.domain.learning.LearningCard
import com.orange.echocards.domain.learning.LearningMode
import com.orange.echocards.domain.learning.LearningPhase
import com.orange.echocards.domain.learning.LearningProgressSink
import com.orange.echocards.domain.learning.matching.MatchingConfig
import com.orange.echocards.domain.speech.AudioInterruptionEvent
import com.orange.echocards.domain.speech.AudioRoute
import com.orange.echocards.speech.mock.MockAudioFocusController
import com.orange.echocards.speech.mock.MockScenario
import com.orange.echocards.speech.mock.MockSpeechPlayer
import com.orange.echocards.speech.mock.MockSpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动播放与自动跟读的端到端状态转换，对应 docs/development/06-automatic-learning.md 的 A01–A07。
 *
 * 时间窗口用 [MatchingConfig] 缩小到几十毫秒，仍然走真实的事件流，
 * 因此不需要虚拟时间就能在 JVM 上确定性地验证。
 */
class LearningEngineFollowAlongTest {

    private val content = "物体保持原来运动状态不变的性质叫作惯性"

    private val cards = listOf(
        LearningCard("c1", "惯性", content, null, "质量越大，惯性越大"),
        LearningCard("c2", "加速度", "速度变化量与时间的比值。", null, null),
    )

    private val config = MatchingConfig.DEFAULT.copy(
        silenceConfirmMs = 60,
        minPartialIntervalMs = 40,
        maxSessionRestarts = 3,
    )

    private inner class Harness(
        scope: CoroutineScope,
        scenario: MockScenario = MockScenario.HappyPath,
        speakDurationMs: Long = 5,
    ) {
        val sink = RecordingSink()
        val player = MockSpeechPlayer(scope, speakDurationMs)
        val recognizer = MockSpeechRecognizer(scope, scenario, stepDelayMs = 5)
        val audioFocus = MockAudioFocusController()
        val engine = DefaultLearningEngine(
            player = player,
            recognizer = recognizer,
            audioFocus = audioFocus,
            scope = scope,
            cardSource = { cards },
            progressSink = sink,
            matchingConfig = config,
        )
    }

    private class RecordingSink : LearningProgressSink {
        val saved = mutableListOf<LearningAttemptRecord?>()
        var failing = false

        override suspend fun save(
            deckId: String,
            nextCardId: String,
            mode: LearningMode,
            attempt: LearningAttemptRecord?,
        ): Boolean {
            saved += attempt
            return !failing
        }
    }

    private fun Harness.card() = engine.state.value

    @Test
    fun a01_autoPlayFlipsOnlyAfterRealTtsCompletion() = runBlocking {
        val harness = Harness(this, speakDurationMs = 100)
        try {
            harness.engine.initialize("deck", "c1", LearningMode.AUTO_PLAY, 1.0, 80)
            harness.engine.start()

            delay(40)
            assertEquals("朗读期间保持正面", CardFace.FRONT, harness.card().cardFace)
            assertEquals(LearningPhase.SPEAKING, harness.card().phase)

            delay(90)
            assertEquals("朗读完成后翻到快速记忆点", CardFace.BACK, harness.card().cardFace)
            assertEquals(LearningPhase.SHOWING_MEMORY_TIP, harness.card().phase)

            delay(100)
            assertTrue("停留结束后进入下一张", harness.card().currentIndex >= 1)
            assertEquals("新卡片从正面开始", CardFace.FRONT, harness.card().cardFace)
        } finally {
            harness.engine.dispose()
        }
    }

    @Test
    fun a02_followAlongListensOnlyAfterSpeechCompletes() = runBlocking {
        val harness = Harness(this, speakDurationMs = 80)
        harness.recognizer.partials = listOf("物体保持")
        harness.recognizer.endWithoutFinal = true
        try {
            harness.engine.initialize("deck", "c1", LearningMode.FOLLOW_ALONG, 1.0, 40)
            harness.engine.start()

            delay(40)
            assertTrue("朗读期间不收音", harness.recognizer.requests.isEmpty())

            delay(80)
            assertEquals("朗读完成后才发起一次识别", 1, harness.recognizer.requests.size)
            assertTrue("跟读需要部分结果", harness.recognizer.requests.single().partialResults)
            assertEquals(LearningPhase.LISTENING, harness.card().phase)
        } finally {
            harness.engine.dispose()
        }
    }

    @Test
    fun a03_partialReadingNeverCompletes() = runBlocking {
        val harness = Harness(this)
        harness.recognizer.partials = listOf("物体保持", "物体保持原来运动状态")
        harness.recognizer.finalTranscript = "物体保持原来运动状态"
        try {
            harness.engine.initialize("deck", "c1", LearningMode.FOLLOW_ALONG, 1.0, 40)
            harness.engine.start()
            delay(400)

            assertTrue("没读完不写任何记录", harness.sink.saved.isEmpty())
            assertEquals("没读完不切卡", 0, harness.card().currentIndex)
            assertEquals(CardFace.FRONT, harness.card().cardFace)
            assertEquals("会话重启到上限后给出可重试错误", "RECOGNITION_RETRY_EXHAUSTED", harness.card().error?.code)
            assertTrue("错误可重试", harness.card().error?.recoverable == true)
        } finally {
            harness.engine.dispose()
        }
    }

    @Test
    fun a03_readingOnlyTheBeginningNeverCompletes() = runBlocking {
        val harness = Harness(this)
        // 只读结尾：覆盖率不达标，同样不能完成
        harness.recognizer.partials = listOf("叫作惯性")
        harness.recognizer.finalTranscript = "叫作惯性"
        try {
            harness.engine.initialize("deck", "c1", LearningMode.FOLLOW_ALONG, 1.0, 40)
            harness.engine.start()
            delay(400)

            assertTrue("只读结尾不写完成记录", harness.sink.saved.isEmpty())
            assertEquals(0, harness.card().currentIndex)
        } finally {
            harness.engine.dispose()
        }
    }

    @Test
    fun a04_duplicateCompletionAdvancesExactlyOnce() = runBlocking {
        val harness = Harness(this)
        harness.recognizer.partials = listOf(content, content)
        harness.recognizer.partialIntervalMs = config.minPartialIntervalMs
        harness.recognizer.duplicateFinal = true
        harness.player.duplicateCompletion = true
        try {
            harness.engine.initialize("deck", "c1", LearningMode.FOLLOW_ALONG, 1.0, 40)
            harness.engine.start()
            delay(300)

            assertEquals("重复完成只写一条记录", 1, harness.sink.saved.size)
            assertEquals(AttemptOutcome.READ_COMPLETED, harness.sink.saved.single()?.outcome)
            assertEquals("只前进一张", 1, harness.card().currentIndex)
        } finally {
            harness.engine.dispose()
        }
    }

    @Test
    fun a05_lateCallbacksAfterPauseAreIgnored() = runBlocking {
        val harness = Harness(this, MockScenario.LateResultAfterCancel)
        // 只读开头：既不达标，也不会被静音确认成完成，状态停在监听
        harness.recognizer.partials = listOf("物体保持")
        try {
            harness.engine.initialize("deck", "c1", LearningMode.FOLLOW_ALONG, 1.0, 40)
            harness.engine.start()
            delay(150)
            assertEquals(LearningPhase.LISTENING, harness.card().phase)

            harness.engine.pause()
            delay(150)

            assertEquals("暂停后保持暂停", LearningPhase.PAUSED, harness.card().phase)
            assertTrue("旧回调不产生记录", harness.sink.saved.isEmpty())
            assertEquals(0, harness.card().currentIndex)
            assertEquals("暂停要停麦", 1, harness.recognizer.cancelCount)
        } finally {
            harness.engine.dispose()
        }
    }

    @Test
    fun a05_lateCallbacksAfterReplayAndModeSwitchAreIgnored() = runBlocking {
        val harness = Harness(this, MockScenario.LateResultAfterCancel)
        // 只读开头：既不达标，也不会被静音确认成完成，状态停在监听
        harness.recognizer.partials = listOf("物体保持")
        try {
            harness.engine.initialize("deck", "c1", LearningMode.FOLLOW_ALONG, 1.0, 40)
            harness.engine.start()
            delay(150)

            harness.engine.replay()
            delay(150)
            assertTrue("重读后旧回调不产生记录", harness.sink.saved.isEmpty())
            assertEquals(0, harness.card().currentIndex)
            assertEquals(LearningMode.FOLLOW_ALONG, harness.card().mode)

            harness.engine.setMode(LearningMode.MANUAL)
            delay(150)
            assertEquals(LearningMode.MANUAL, harness.card().mode)
            assertTrue(harness.sink.saved.isEmpty())
        } finally {
            harness.engine.dispose()
        }
    }

    @Test
    fun a06_saveFailureKeepsCardAndRetryCompletes() = runBlocking {
        val harness = Harness(this)
        harness.recognizer.partials = listOf(content, content)
        harness.recognizer.partialIntervalMs = config.minPartialIntervalMs
        harness.sink.failing = true
        try {
            harness.engine.initialize("deck", "c1", LearningMode.FOLLOW_ALONG, 1.0, 40)
            harness.engine.start()
            delay(200)

            assertEquals("保存失败显示可重试错误", "SAVE_FAILED", harness.card().error?.code)
            assertEquals("保存失败不翻面", CardFace.FRONT, harness.card().cardFace)
            assertEquals("保存失败不切卡", 0, harness.card().currentIndex)
            assertEquals("只尝试写一次", 1, harness.sink.saved.size)

            harness.sink.failing = false
            harness.engine.retry()
            delay(200)

            assertEquals("重试补写成功", 2, harness.sink.saved.size)
            assertEquals("成功后前进一张", 1, harness.card().currentIndex)
            assertEquals(null, harness.card().error)
        } finally {
            harness.engine.dispose()
        }
    }

    @Test
    fun a07_backgroundStopsMicrophoneAndStaysPaused() = runBlocking {
        val harness = Harness(this)
        harness.recognizer.partials = listOf("物体保持")
        harness.recognizer.endWithoutFinal = true
        try {
            harness.engine.initialize("deck", "c1", LearningMode.FOLLOW_ALONG, 1.0, 40)
            harness.engine.start()
            delay(150)
            assertEquals(LearningPhase.LISTENING, harness.card().phase)

            harness.engine.pause()
            assertEquals(LearningPhase.PAUSED, harness.card().phase)
            assertEquals("进入后台应停止收音", 1, harness.recognizer.cancelCount)

            harness.audioFocus.emit(AudioInterruptionEvent.InterruptionEnded(shouldResume = true))
            delay(100)
            assertEquals("回到前台保持暂停，不自动开麦", LearningPhase.PAUSED, harness.card().phase)
            assertEquals(1, harness.recognizer.cancelCount)
        } finally {
            harness.engine.dispose()
        }
    }

    @Test
    fun a07_audioInterruptionPausesButRouteChangeDoesNot() = runBlocking {
        val harness = Harness(this, speakDurationMs = 300)
        try {
            harness.engine.initialize("deck", "c1", LearningMode.MANUAL, 1.0, 40)
            harness.engine.start()
            delay(50)
            assertEquals(LearningPhase.SPEAKING, harness.card().phase)

            harness.audioFocus.emit(AudioInterruptionEvent.RouteChanged(AudioRoute.HEADPHONES))
            delay(50)
            assertEquals("换音频路由不暂停", LearningPhase.SPEAKING, harness.card().phase)

            harness.audioFocus.emit(AudioInterruptionEvent.InterruptionStarted("电话"))
            delay(50)
            assertEquals("焦点丢失要暂停", LearningPhase.PAUSED, harness.card().phase)
        } finally {
            harness.engine.dispose()
        }
    }

    @Test
    fun a07_disposeStopsMicrophoneAndReleasesAudioFocus() = runBlocking {
        val harness = Harness(this)
        harness.recognizer.partials = listOf("物体保持")
        harness.recognizer.endWithoutFinal = true
        harness.engine.initialize("deck", "c1", LearningMode.FOLLOW_ALONG, 1.0, 40)
        harness.engine.start()
        delay(150)
        assertEquals("应已进入收音状态", LearningPhase.LISTENING, harness.card().phase)

        harness.engine.dispose()

        assertEquals(LearningPhase.DISPOSED, harness.card().phase)
        assertEquals("退出时要停麦", 1, harness.recognizer.cancelCount)
        assertTrue("退出时释放音频焦点", harness.audioFocus.deactivated)
    }
}
