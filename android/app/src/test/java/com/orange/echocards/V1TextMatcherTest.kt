package com.orange.echocards

import com.orange.echocards.domain.learning.matching.MatchingConfig
import com.orange.echocards.domain.learning.matching.V1TextMatcher
import com.orange.echocards.domain.learning.matching.ZhTextNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖 docs/architecture/speech-matching.md 第 8 至 10 节与第 17 节的样例表。
 *
 * 这里只断言"匹配到哪"；"能不能判定完成"由 [CompletionTrackerTest] 覆盖。
 */
class V1TextMatcherTest {

    private val config = MatchingConfig.DEFAULT
    private val matcher = V1TextMatcher(config)

    private fun normalized(text: String) = ZhTextNormalizer.normalize(text, "zh-CN")

    private fun match(target: String, transcript: String, fromIndex: Int = 0) =
        matcher.match(normalized(target), normalized(transcript), fromIndex)

    private fun coverage(target: String, transcript: String): Double {
        val result = match(target, transcript)
        return result.matchedUpTo.toDouble() / normalized(target).length
    }

    @Test
    fun matchesTheLongSampleRowByRow() {
        val target = "物体保持原来运动状态不变的性质叫作惯性"

        assertEquals("只读开头不该读到后面", 4, match(target, "物体保持").matchedUpTo)
        assertFalse("只读开头不满足结尾", match(target, "物体保持").endingMatched)

        assertTrue("读到大半仍不满足结尾", coverage(target, "物体保持原来运动状态不变的性质") < 0.9)
        assertFalse("只读到大半时结尾未匹配", match(target, "物体保持原来运动状态不变的性质").endingMatched)

        val complete = match(target, "物体保持原来运动状态不变的性质叫作惯性")
        assertEquals("完整读完应匹配整段", normalized(target).length, complete.matchedUpTo)
        assertTrue("完整读完必须匹配结尾", complete.endingMatched)
    }

    @Test
    fun toleratesASingleMisrecognizedCharacter() {
        val target = "物体保持原来运动状态不变的性质叫作惯性"
        val result = match(target, "物体保持原来运动状态不变的特性叫作惯性")

        assertEquals("错一个字应仍能读完", normalized(target).length, result.matchedUpTo)
        assertTrue("错一个字应仍匹配结尾", result.endingMatched)
        assertTrue("覆盖率应达到阈值", coverage(target, "物体保持原来运动状态不变的特性叫作惯性") >= 0.9)
    }

    @Test
    fun toleratesADroppedCharacter() {
        val target = "物体保持原来运动状态不变的性质叫作惯性"
        val result = match(target, "物体保持原来运动状不变的性质叫作惯性")

        assertEquals("漏一个字应在容错额度内读完", normalized(target).length, result.matchedUpTo)
    }

    @Test
    fun toleranceIsBounded() {
        // 12 个字的目标容错额度是 1：漏掉两个及以上的字时不许靠跳字把覆盖率抬到达标
        val target = "光合作用的过程需要叶绿体"
        assertEquals(1, config.maxAlignmentErrors(normalized(target).length))
        assertTrue("第二个漏字就该停止匹配", match(target, "光合用的过程要叶绿体").matchedUpTo < normalized(target).length)
    }

    @Test
    fun rejectsReadingOnlyTheEnding() {
        val target = "物体保持原来运动状态不变的性质叫作惯性"
        val result = match(target, "叫作惯性")

        assertEquals("只读结尾不应匹配到开头", 0, result.matchedUpTo)
        assertTrue("只读结尾的覆盖率远低于阈值", coverage(target, "叫作惯性") < 0.9)
    }

    @Test
    fun matchesTheShortSampleRows() {
        val target = "光合作用"

        assertEquals("只读前两字", 2, match(target, "光合").matchedUpTo)
        assertEquals("只读前三字", 3, match(target, "光合作").matchedUpTo)
        assertEquals("整段", 4, match(target, "光合作用").matchedUpTo)
        assertTrue("整段应匹配结尾", match(target, "光合作用").endingMatched)

        // 第 17 节：只读后半段不能完成——开头必须从头匹配，短文本又要求 100% 覆盖率
        val fromMiddle = match(target, "合作用")
        assertEquals("只读后半段不能算读到开头", 0, fromMiddle.matchedUpTo)
        assertTrue("只读后半段覆盖率不达标", coverage(target, "合作用") < 1.0)
    }

    @Test
    fun endingMustAppearAtTheEndOfTheResult() {
        // "的""性"在句首就出现过：结尾检查必须只看结果末尾，否则会退化成永远为真
        val target = "惯性的定义是物体保持原有运动状态的性质"
        val result = match(target, "惯性的定义")

        assertTrue("开头出现的字不能当作读到结尾", !result.endingMatched)
    }

    @Test
    fun shortTextDoesNotTolerateADroppedCharacter() {
        // 第 13 节要求短文本完整匹配：漏掉"合"只剩三个字，不能算读完
        val target = "光合作用"
        val result = match(target, "光作用")

        assertTrue("短文本漏字不应匹配整段", result.matchedUpTo < normalized(target).length)
        assertTrue("覆盖率不达标", result.matchedUpTo.toDouble() / normalized(target).length < 1.0)
    }

    @Test
    fun repeatedContentDoesNotAdvanceBeyondTheTarget() {
        val target = "光合作用"
        val result = match(target, "光合作用光合作用")

        assertEquals("重复朗读不会越过目标长度", 4, result.matchedUpTo)
    }

    @Test
    fun continuesMatchingFromTheConfirmedIndex() {
        val target = "物体保持原来运动状态不变的性质叫作惯性"
        val resumeAt = normalized("物体保持原来运动状态").length

        val result = match(target, "不变的性质叫作惯性", fromIndex = resumeAt)
        assertEquals("会话重启后应接着未完成部分匹配", normalized(target).length, result.matchedUpTo)
    }

    @Test
    fun endingWindowFollowsTargetLength() {
        assertEquals(2, match("光合作用", "光合作用").endingWindow)
        assertEquals(2, match("物体保持原来运动", "物体保持原来运动").endingWindow)
        assertEquals(3, match("物体保持原来运动状态不变的性质", "物体保持原来运动状态不变的性质").endingWindow)
        assertEquals(5, match("物体保持原来运动状态不变的性质叫作惯性定律", "x").endingWindow)
    }

    @Test
    fun emptyTargetMatchesNothing() {
        val result = matcher.match("", "惯性", 0)
        assertEquals(0, result.matchedUpTo)
        assertFalse(result.endingMatched)
    }
}
