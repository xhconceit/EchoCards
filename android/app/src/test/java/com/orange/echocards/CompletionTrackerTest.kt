package com.orange.echocards

import com.orange.echocards.domain.learning.matching.CompletionTracker
import com.orange.echocards.domain.learning.matching.MATCHING_ALGORITHM_VERSION
import com.orange.echocards.domain.learning.matching.MatchProgress
import com.orange.echocards.domain.learning.matching.MatchingConfig
import com.orange.echocards.domain.learning.matching.RecognitionSignal
import com.orange.echocards.domain.learning.matching.ZhTextNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖 docs/architecture/speech-matching.md 第 9 至 14 节：已确认进度、结尾匹配、
 * 完成阈值、稳定性条件 A/B/C、短文本与识别会话重启。
 */
class CompletionTrackerTest {

    private val config = MatchingConfig.DEFAULT

    /** 目标较长时按静音确认（条件 C）。 */
    private val longTarget = normalized("物体保持原来运动状态不变的性质叫作惯性")
    private val shortTarget = normalized("光合作用")
    private val fullLongTranscript = normalized("物体保持原来运动状态不变的性质叫作惯性")

    private fun normalized(text: String) = ZhTextNormalizer.normalize(text, "zh-CN")

    private fun tracker(target: String = longTarget) = CompletionTracker(config = config).apply { beginAttempt(target) }

    @Test
    fun finalResultAtFullCoverageCompletes() {
        val progress = tracker().onResult(RecognitionSignal.FINAL, fullLongTranscript, nowMs = 0)

        assertTrue("最终结果达到阈值且匹配结尾即可完成", progress.completed)
        assertEquals(1.0, progress.coverage, 0.0001)
        assertTrue(progress.endingMatched)
    }

    @Test
    fun partialReadingNeverCompletes() {
        val result = tracker().onResult(RecognitionSignal.PARTIAL, normalized("物体保持原来运动状态"), nowMs = 0)

        assertFalse("只读开头不能完成", result.completed)
        assertTrue("覆盖率低于阈值", result.coverage < 0.9)
    }

    @Test
    fun twoPartialsEnoughApartStabilize() {
        val tracker = tracker()
        val first = tracker.onResult(RecognitionSignal.PARTIAL, fullLongTranscript, nowMs = 0)
        assertFalse("一次部分结果不足以判定", first.completed)

        val second = tracker.onResult(RecognitionSignal.PARTIAL, fullLongTranscript, nowMs = 400)
        assertTrue("两次间隔足够的部分结果可以判定", second.completed)
        assertEquals(2, second.consecutiveCompletedResults)
    }

    @Test
    fun twoPartialsTooCloseDoNotStabilize() {
        val tracker = tracker()
        tracker.onResult(RecognitionSignal.PARTIAL, fullLongTranscript, nowMs = 0)
        val second = tracker.onResult(RecognitionSignal.PARTIAL, fullLongTranscript, nowMs = 100)

        assertFalse("间隔不足 300 毫秒不算连续达标", second.completed)
    }

    @Test
    fun partialsBelowThresholdDoNotStartTheStreak() {
        val tracker = tracker()
        val belowThreshold = tracker.onResult(RecognitionSignal.PARTIAL, normalized("物体保持"), nowMs = 0)
        assertEquals("不达标的结果不计数", 0, belowThreshold.consecutiveCompletedResults)

        val first = tracker.onResult(RecognitionSignal.PARTIAL, fullLongTranscript, nowMs = 400)
        assertFalse("一次达标还不够", first.completed)
        assertEquals(1, first.consecutiveCompletedResults)

        val second = tracker.onResult(RecognitionSignal.PARTIAL, fullLongTranscript, nowMs = 800)
        assertTrue("两次达标完成", second.completed)
    }

    @Test
    fun unrelatedSnapshotCannotCompleteAnAlreadyConfirmedReading() {
        val tracker = tracker()
        tracker.onResult(RecognitionSignal.PARTIAL, fullLongTranscript, nowMs = 0)

        // 已确认进度是单调的，所以这次快照的"累计覆盖率"仍然达标；
        // 但它自己只匹配到两个字，不能算作条件 B 的第二次达标。
        val after = tracker.onResult(RecognitionSignal.PARTIAL, normalized("物体"), nowMs = 500)

        assertFalse("不能靠已确认进度把无关结果补成完成", after.completed)
        assertEquals(1, after.consecutiveCompletedResults)
    }

    @Test
    fun silenceConfirmsAnAlreadyQualifyingResult() {
        val tracker = tracker()
        val partial = tracker.onResult(RecognitionSignal.PARTIAL, fullLongTranscript, nowMs = 0)
        assertFalse(partial.completed)

        val silence = tracker.onSilence()
        assertTrue("静音可以确认已经达标的文本", silence.completed)
        assertTrue(silence.stable)
    }

    @Test
    fun silenceAloneCannotCompleteShortText() {
        val tracker = tracker(shortTarget)
        tracker.onResult(RecognitionSignal.PARTIAL, shortTarget, nowMs = 0)

        assertFalse("短文本不能靠静音完成", tracker.onSilence().completed)

        val final = tracker.onResult(RecognitionSignal.FINAL, shortTarget, nowMs = 400)
        assertTrue("短文本收到最终结果可以完成", final.completed)
    }

    @Test
    fun shortTextNeedsTwoSeparatedPartials() {
        val tracker = tracker(shortTarget)
        tracker.onResult(RecognitionSignal.PARTIAL, shortTarget, nowMs = 0)
        assertFalse("短文本一次部分结果不够", tracker.bestProgress.completed)

        val second = tracker.onResult(RecognitionSignal.PARTIAL, shortTarget, nowMs = 500)
        assertTrue("短文本两次间隔足够的部分结果可以完成", second.completed)
    }

    @Test
    fun emptyTranscriptDoesNotChangeProgress() {
        val tracker = tracker()
        val before = tracker.onResult(RecognitionSignal.PARTIAL, fullLongTranscript, nowMs = 0)
        val after = tracker.onResult(RecognitionSignal.FINAL, "", nowMs = 100)

        assertEquals(before, after)
    }

    @Test
    fun endingMatchSticksWhenALaterResultIsTruncated() {
        val tracker = tracker()
        tracker.onResult(RecognitionSignal.PARTIAL, fullLongTranscript, nowMs = 0)
        // 系统把最终结果截短：已读到结尾的事实不能被抹掉，否则读完永远不翻页
        val progress = tracker.onResult(RecognitionSignal.FINAL, normalized("物体保持原来运动状态不变的性质叫"), nowMs = 200)

        assertTrue(progress.endingMatched)
        assertTrue(progress.completed)
    }

    @Test
    fun confirmedProgressIsMonotoneAndSurvivesSessionRestart() {
        val tracker = tracker()
        val confirmed = tracker.onResult(RecognitionSignal.PARTIAL, normalized("物体保持原来运动状态"), nowMs = 0).matchedCharacterCount
        assertEquals(10, confirmed)

        tracker.restartSession()
        assertEquals("会话重启保留已确认进度", confirmed, tracker.confirmedCharacterCount)
        assertEquals("读到新内容的重启不消耗额度", 0, tracker.restartCount)

        val progress = tracker.onResult(RecognitionSignal.FINAL, normalized("不变的性质叫作惯性"), nowMs = 100)
        assertEquals("重启后接着未完成部分匹配", tracker.targetCharacterCount, progress.matchedCharacterCount)
        assertTrue(progress.completed)

        val degraded = tracker.onResult(RecognitionSignal.PARTIAL, normalized("物体保持"), nowMs = 200)
        assertTrue("覆盖率不因临时结果倒退", degraded.coverage >= 0.9)
    }

    @Test
    fun restartLimitIsEnforced() {
        val tracker = tracker()
        repeat(config.maxSessionRestarts) {
            assertTrue("限制内可以重启", tracker.canRestart())
            tracker.restartSession()
        }
        assertFalse("达到上限后不再自动重启", tracker.canRestart())
    }

    @Test
    fun restartsWithProgressDoNotExhaustTheBudget() {
        val tracker = tracker()
        // 每段会话都读到新内容：长句跨多次会话也不该耗尽额度
        listOf(
            "物体保持原来运动状态",
            "不变的性质",
            "叫作惯性",
        ).forEach { piece ->
            tracker.onResult(RecognitionSignal.FINAL, normalized(piece), nowMs = 100)
            if (!tracker.bestProgress.completed) tracker.restartSession()
        }
        assertTrue("有进展的会话不该消耗重启额度", tracker.canRestart())
        assertEquals("读完仍然可以完成", true, tracker.bestProgress.completed)
    }

    @Test
    fun fruitlessRestartsExhaustTheBudget() {
        val tracker = tracker()
        repeat(config.maxSessionRestarts) {
            assertTrue(tracker.canRestart())
            // 识别结果和目标完全对不上：这一段没有任何确认进度
            tracker.onResult(RecognitionSignal.FINAL, normalized("加速度"), nowMs = 100)
            tracker.restartSession()
        }
        assertFalse("一直没有听清就不再自动重启", tracker.canRestart())
    }

    @Test
    fun resetClearsEverything() {
        val tracker = tracker()
        tracker.onResult(RecognitionSignal.FINAL, fullLongTranscript, nowMs = 0)
        tracker.reset()

        assertFalse("重置后没有目标文本", tracker.hasTarget)
        assertEquals(0, tracker.confirmedCharacterCount)
        assertFalse(tracker.bestProgress.completed)
    }

    @Test
    fun progressNeverCarriesTheTranscript() {
        // 识别文本与目标必须不同，否则断言会退化
        val transcript = normalized("物体保持原来运动状态不变的特性叫作惯性")
        val progress = tracker().onResult(RecognitionSignal.FINAL, transcript, nowMs = 0)
        assertTrue("样例应能完成，否则这个守护没有意义", progress.completed)

        assertEquals(MATCHING_ALGORITHM_VERSION, progress.algorithmVersion)
        MatchProgress::class.java.declaredFields
            .filter { it.type == String::class.java }
            .forEach { field ->
                field.isAccessible = true
                val value = field.get(progress) as String
                assertFalse("进度里不能保存识别文本：$value", value.contains(transcript))
            }
    }
}
