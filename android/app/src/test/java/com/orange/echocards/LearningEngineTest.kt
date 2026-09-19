package com.orange.echocards

import com.orange.echocards.domain.learning.CardFace
import com.orange.echocards.domain.learning.DefaultLearningEngine
import com.orange.echocards.domain.learning.LearningCard
import com.orange.echocards.domain.learning.LearningCardSource
import com.orange.echocards.domain.learning.LearningMode
import com.orange.echocards.domain.learning.LearningPhase
import com.orange.echocards.domain.learning.LearningProgressSink
import com.orange.echocards.domain.learning.SpeechSegmenter
import com.orange.echocards.speech.mock.MockSpeechPlayer
import com.orange.echocards.speech.mock.MockSpeechRecognizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Learning Engine 的状态转换，对应 docs/development/05-manual-learning.md 的 M01–M05。
 * 语音用节点 1 的模拟实现，因此可以在 JVM 上确定性地验证“等真实完成事件”的流程。
 */
class LearningEngineTest {

    private val cards = listOf(
        LearningCard("c1", "惯性", "物体保持原有运动状态的性质。质量越大惯性越大。", null, "质量越大，惯性越大"),
        LearningCard("c2", "加速度", "速度变化量与时间的比值。", null, null),
        LearningCard("c3", "摩擦力", "阻碍相对运动的力。", null, "方向与相对运动相反"),
    )

    private fun source(items: List<LearningCard> = cards) = LearningCardSource { items }

    /**
     * 引擎的事件收集协程是常驻的，必须显式 dispose，否则 runBlocking 会一直等它们。
     */
    private fun learningTest(
        speakDurationMs: Long = 10,
        sink: LearningProgressSink = LearningProgressSink { _, _, _ -> true },
        body: suspend kotlinx.coroutines.CoroutineScope.(DefaultLearningEngine, MockSpeechPlayer) -> Unit,
    ) = runBlocking {
        val player = MockSpeechPlayer(this, speakDurationMs)
        val engine = DefaultLearningEngine(player, MockSpeechRecognizer(this), this, source(), sink)
        try {
            body(engine, player)
        } finally {
            engine.dispose()
        }
    }

    @Test
    fun manualPlaybackSpeaksSegmentBySegment() = learningTest { engine, player ->
        engine.initialize("deck", "c1", LearningMode.MANUAL, 1.0, 50)
        engine.start()
        delay(400)

        val spoken = player.requests.map { it.text }
        assertEquals("应按分段顺序朗读", SpeechSegmenter.split(cards[0].spokenText), spoken)
        assertEquals(LearningPhase.READY, engine.state.value.phase)
        assertEquals(0, engine.state.value.segmentCount)
    }

    @Test
    fun pauseKeepsSegmentAndResumeContinuesFromIt() = learningTest(speakDurationMs = 60) { engine, player ->
        engine.initialize("deck", "c1", LearningMode.MANUAL, 1.0, 50)
        engine.start()
        delay(20)
        engine.pause()

        assertEquals(LearningPhase.PAUSED, engine.state.value.phase)
        assertEquals("暂停时应记住当前分段", 0, engine.state.value.speechSegmentIndex)
        val beforeResume = player.requests.size

        engine.resume()
        delay(30)
        val resumed = player.requests.drop(beforeResume)
        assertTrue("继续应重新发起朗读", resumed.isNotEmpty())
        assertEquals("继续应从保存的分段开头开始", SpeechSegmenter.split(cards[0].spokenText).first(), resumed.first().text)
    }

    @Test
    fun pauseStopsSpeechAndLateCompletionIsIgnored() = learningTest(speakDurationMs = 80) { engine, player ->
        engine.initialize("deck", "c1", LearningMode.MANUAL, 1.0, 50)
        engine.start()
        delay(20)
        engine.pause()
        delay(200)

        // 模拟实现会在 stop 之后继续抛出旧操作的 completed，引擎必须忽略
        assertEquals(LearningPhase.PAUSED, engine.state.value.phase)
        assertEquals(1, player.requests.size)
    }

    @Test
    fun nextAndPreviousWrapAround() = learningTest { engine, _ ->
        engine.initialize("deck", "c1", LearningMode.MANUAL, 1.0, 50)

        engine.next()
        assertEquals(1, engine.state.value.currentIndex)
        engine.next()
        engine.next()
        assertEquals("最后一张的下一张应回到第一张", 0, engine.state.value.currentIndex)
        engine.previous()
        assertEquals("第一张的上一张应回到最后一张", cards.lastIndex, engine.state.value.currentIndex)
    }

    @Test
    fun saveFailureKeepsCurrentCard() = runBlocking {
        val player = MockSpeechPlayer(this, speakDurationMs = 10)
        val savedCards = mutableListOf<String>()
        val engine = DefaultLearningEngine(
            player = player,
            recognizer = MockSpeechRecognizer(this),
            scope = this,
            cardSource = source(),
            progressSink = LearningProgressSink { _, cardId, _ ->
                savedCards += cardId
                false
            },
        )
        try {
            engine.initialize("deck", "c1", LearningMode.MANUAL, 1.0, 50)
            engine.next()

            assertEquals("保存失败不能切卡", 0, engine.state.value.currentIndex)
            assertEquals(LearningPhase.ERROR, engine.state.value.phase)
            assertEquals("SAVE_FAILED", engine.state.value.error?.code)
            assertTrue("应可重试", engine.state.value.error?.recoverable == true)
            assertEquals(listOf("c2"), savedCards)
        } finally {
            engine.dispose()
        }
    }

    @Test
    fun flipTogglesFaceAndReplayReturnsToFront() = learningTest { engine, _ ->
        engine.initialize("deck", "c2", LearningMode.MANUAL, 1.0, 50)

        engine.flip()
        assertEquals(CardFace.BACK, engine.state.value.cardFace)
        engine.replay()
        assertEquals(CardFace.FRONT, engine.state.value.cardFace)
    }

    /** 自动播放必须等真实完成事件，再翻到快速记忆点，停留后才前进。 */
    @Test
    fun autoPlayShowsMemoryTipThenAdvancesAfterDelay() = learningTest { engine, _ ->
        engine.initialize("deck", "c1", LearningMode.AUTO_PLAY, 1.0, 120)
        engine.start()

        delay(300)
        assertEquals("应先翻到快速记忆点", CardFace.BACK, engine.state.value.cardFace)
        assertTrue("停留期间仍在展示记忆点",
            engine.state.value.phase == LearningPhase.SHOWING_MEMORY_TIP || engine.state.value.currentIndex == 1)

        delay(400)
        // 自动播放会一直循环下去，这里只断言“确实前进过”，不锁定某一刻的翻面状态
        assertTrue("停留结束后应切到下一张", engine.state.value.currentIndex >= 1)
    }

    @Test
    fun autoPlayPausesAtMemoryTip() = learningTest { engine, _ ->
        engine.initialize("deck", "c1", LearningMode.AUTO_PLAY, 1.0, 400)
        engine.start()
        delay(200)
        engine.pause()
        val indexAtPause = engine.state.value.currentIndex

        delay(600)
        assertEquals("暂停后不得继续前进", indexAtPause, engine.state.value.currentIndex)
        assertEquals(LearningPhase.PAUSED, engine.state.value.phase)
    }

    @Test
    fun settingModeStopsSpeechAndResetsFace() = learningTest(speakDurationMs = 80) { engine, _ ->
        engine.initialize("deck", "c2", LearningMode.MANUAL, 1.0, 50)
        engine.start()
        delay(20)
        engine.flip()
        engine.setMode(LearningMode.MANUAL)

        assertEquals("同一模式不应重置状态", CardFace.BACK, engine.state.value.cardFace)
        engine.setMode(LearningMode.AUTO_PLAY)
        assertEquals(CardFace.FRONT, engine.state.value.cardFace)
        assertFalse(engine.state.value.phase == LearningPhase.DISPOSED)
    }

    @Test
    fun segmenterKeepsSentencesAndSplitsLongOnes() {
        val segments = SpeechSegmenter.split("第一句。第二句！这是很长的一句话，需要按停顿切开，否则一次朗读太久了。")
        assertTrue("应至少切出三句", segments.size >= 3)
        assertEquals("第一句。", segments[0])
        assertEquals("第二句！", segments[1])
        assertTrue("每段都不应过长", segments.all { it.length <= 40 })
    }
}
