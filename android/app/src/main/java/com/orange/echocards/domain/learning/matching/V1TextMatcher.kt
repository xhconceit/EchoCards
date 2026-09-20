package com.orange.echocards.domain.learning.matching

/**
 * 一次识别快照与目标文本的顺序对齐结果。
 *
 * 只描述"匹配到哪"，不含时间与稳定性判断——那部分在 [CompletionTracker]，
 * 见设计文档第 15 节结尾的说明。
 *
 * @param matchedUpTo 已按顺序匹配到的目标下标，等于目标长度表示整段匹配完。
 */
data class TextMatch(
    val matchedUpTo: Int,
    val endingMatched: Boolean,
    val endingWindow: Int,
)

interface TextMatcher {
    /**
     * @param normalizedTarget 已标准化的目标文本
     * @param transcript 已标准化的识别结果
     * @param fromIndex 只匹配目标从该下标开始的部分（第 14 节：会话重启后接着未完成的部分匹配）
     */
    fun match(normalizedTarget: String, transcript: String, fromIndex: Int): TextMatch
}

/**
 * `v1` 顺序字符匹配，见设计文档第 8 至 10 节。
 *
 * 在文档伪代码的基础上允许有限误差：识别多出一个字或漏掉一个字时，
 * 只要还在 [MatchingConfig.maxAlignmentErrors] 之内就继续，否则会把
 * "不变的**特性**叫作惯性" 这类正常识别、却带一个错字的整句判为未读完。
 * 容错有上界，所以覆盖率不会被跳字抬高。
 */
class V1TextMatcher(private val config: MatchingConfig = MatchingConfig.DEFAULT) : TextMatcher {

    override fun match(normalizedTarget: String, transcript: String, fromIndex: Int): TextMatch {
        val targetLength = normalizedTarget.length
        val window = config.endingWindow(targetLength)
        if (targetLength == 0) return TextMatch(0, endingMatched = false, endingWindow = window)

        val maxErrors = config.maxAlignmentErrors(targetLength)
        val startIndex = fromIndex.coerceIn(0, targetLength)
        var targetIndex = startIndex
        var errors = 0

        for (character in transcript) {
            if (targetIndex >= targetLength) break
            if (character == normalizedTarget[targetIndex]) {
                targetIndex += 1
                continue
            }
            // 目标里多一个字而识别里没有：在容错额度内跳过该字。
            // 开头不允许跳字，否则"合作用"会被当成"光合作用"读完（第 8 节要求单独检查开头）。
            if (targetIndex > startIndex && errors < maxErrors &&
                targetIndex + 1 < targetLength && normalizedTarget[targetIndex + 1] == character
            ) {
                targetIndex += 2
                errors += 1
            }
            // 其余情况视为识别多出的字，忽略后继续（与文档伪代码一致）
        }

        val reachedEnd = targetIndex >= targetLength
        return TextMatch(
            matchedUpTo = targetIndex,
            endingMatched = reachedEnd || matchesEnding(normalizedTarget, transcript, window),
            endingWindow = window,
        )
    }

    /**
     * 第 10 节：必须确认用户读到了结尾，结尾窗口内允许一次小误差，但不能完全缺失。
     * 只读开头时覆盖率本来就不达标，这里的检查是为了拦住"读了大半、结尾没读"的情况。
     *
     * 结尾只在识别结果的**末尾**找，不能在整个结果里搜：目标里常见的字（"的""性"）
     * 往往在句子开头就出现过，全局搜索会让"结尾匹配"变成一个永远为真的条件。
     * 留一点尾部余量，容忍识别器在句尾多吐几个字。
     */
    private fun matchesEnding(normalizedTarget: String, transcript: String, window: Int): Boolean {
        val ending = normalizedTarget.takeLast(window.coerceAtMost(normalizedTarget.length))
        val tail = transcript.takeLast(window + ENDING_TAIL_SLACK)
        var matched = 0
        for (character in tail) {
            if (matched >= ending.length) break
            if (character == ending[matched]) matched += 1
        }
        return matched >= ending.length - 1
    }

    private companion object {
        /** 结尾窗口之外还允许出现在末尾的字符数。 */
        const val ENDING_TAIL_SLACK = 2
    }
}
