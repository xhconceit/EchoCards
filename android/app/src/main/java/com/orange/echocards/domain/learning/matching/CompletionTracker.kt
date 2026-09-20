package com.orange.echocards.domain.learning.matching

/**
 * 把一串识别快照累积成一张卡片的跟读进度，并判断是否可以判定完成。
 *
 * 纯 Kotlin、无协程：时间由调用方传入（引擎负责计时与静音窗口），因此
 * 300 毫秒与 800 毫秒这类规则可以直接用普通单元测试覆盖。
 *
 * 关键规则（见 docs/architecture/speech-matching.md）：
 * - 已确认进度单调不减（第 9 节），会话重启后保留（第 14 节）；
 * - 结尾匹配在单次跟读内粘滞：识别结果被系统截短时不能把已经读到结尾的事实抹掉；
 * - 条件 A（最终结果）、B（两次间隔足够的部分结果）、C（静音确认）满足其一即稳定（第 12 节）；
 * - 短文本不能用静音确认，也不能只靠一次结果（第 13 节）。
 */
class CompletionTracker(
    private val matcher: TextMatcher = V1TextMatcher(),
    private val config: MatchingConfig = MatchingConfig.DEFAULT,
) {

    private var normalizedTarget: String = ""
    private var confirmedCount: Int = 0
    private var endingMatched: Boolean = false
    private var consecutiveQualifying: Int = 0
    private var lastQualifyingAtMs: Long? = null
    private var completed: Boolean = false
    private var restarts: Int = 0
    private var sessionStartConfirmed: Int = 0
    private var progress: MatchProgress = buildProgress(qualifying = false, stable = false)

    /** 目标文本标准化后是否为空；为空时不允许开始收音。 */
    val hasTarget: Boolean get() = normalizedTarget.isNotEmpty()

    val targetCharacterCount: Int get() = normalizedTarget.length

    /** 已确认的匹配字数，用于排查与断言。 */
    val confirmedCharacterCount: Int get() = confirmedCount

    val restartCount: Int get() = restarts

    val bestProgress: MatchProgress get() = progress

    fun beginAttempt(normalizedTarget: String) {
        this.normalizedTarget = normalizedTarget
        confirmedCount = 0
        endingMatched = false
        consecutiveQualifying = 0
        lastQualifyingAtMs = null
        completed = false
        restarts = 0
        sessionStartConfirmed = 0
        progress = buildProgress(qualifying = false, stable = false)
    }

    /** 暂停、重读、切卡、切模式：临时匹配进度全部作废。 */
    fun reset() = beginAttempt("")

    /**
     * 识别会话重启：保留已确认进度与结尾匹配，重新积累连续达标次数。
     *
     * 重启额度只计**没有进展**的会话。离线识别会在每个自然停顿处结束一次会话（第 14 节
     * 的"分段识别"），长句子读下来本来就会跨越多次会话；只要每段都读到了新内容，
     * 就不该消耗额度，额度只用来拦住"识别不出任何东西"的情况。
     */
    fun restartSession() {
        if (confirmedCount > sessionStartConfirmed) restarts = 0 else restarts += 1
        sessionStartConfirmed = confirmedCount
        consecutiveQualifying = 0
        lastQualifyingAtMs = null
    }

    fun canRestart(): Boolean = restarts < config.maxSessionRestarts

    /** 收到一次识别快照（部分或最终）。空 transcript 不改变任何进度。 */
    fun onResult(signal: RecognitionSignal, transcript: String, nowMs: Long): MatchProgress {
        if (normalizedTarget.isEmpty() || transcript.isEmpty()) return progress

        val match = matcher.match(normalizedTarget, transcript, confirmedCount)
        if (match.matchedUpTo > confirmedCount) confirmedCount = match.matchedUpTo
        if (match.endingMatched) endingMatched = true

        val qualifying = isQualifying()
        // 条件 B 只看这次快照自己覆盖了多少，所以要从头匹配一遍
        val ownMatch = if (signal == RecognitionSignal.PARTIAL) {
            matcher.match(normalizedTarget, transcript, 0)
        } else {
            match
        }
        if (!qualifying) {
            consecutiveQualifying = 0
            lastQualifyingAtMs = null
        } else if (signal == RecognitionSignal.PARTIAL && isSnapshotQualifying(ownMatch)) {
            val previous = lastQualifyingAtMs
            if (previous == null || nowMs - previous >= config.minPartialIntervalMs) {
                consecutiveQualifying += 1
                lastQualifyingAtMs = nowMs
            }
        }

        val stable = when (signal) {
            RecognitionSignal.FINAL -> true
            RecognitionSignal.PARTIAL -> consecutiveQualifying >= 2
            RecognitionSignal.SILENCE -> !config.isShortText(normalizedTarget.length)
        }
        progress = buildProgress(qualifying = qualifying, stable = qualifying && stable)
        if (progress.completed) completed = true
        return progress
    }

    /**
     * 静音确认（第 12 节条件 C）：只能确认一个已经满足文本条件的结果，不能单独表示完成。
     * 计时由引擎负责，这里只做文本条件判断。
     */
    fun onSilence(): MatchProgress {
        if (normalizedTarget.isEmpty()) return progress
        val qualifying = isQualifying()
        val stable = qualifying && !config.isShortText(normalizedTarget.length)
        progress = buildProgress(qualifying = qualifying, stable = stable)
        if (progress.completed) completed = true
        return progress
    }

    private fun isQualifying(): Boolean {
        if (normalizedTarget.isEmpty() || !endingMatched) return false
        val coverage = confirmedCount.toDouble() / normalizedTarget.length
        return coverage >= config.coverageThreshold(normalizedTarget.length)
    }

    /**
     * 条件 B 要求"连续两次 partial_result 达到阈值"，因此计数只看这一次快照**从头**匹配了多少，
     * 不能借用已确认进度：识别偶发地把一句错话凑成整段时，后面随便一个词都不能把它补成完成。
     */
    private fun isSnapshotQualifying(match: TextMatch): Boolean {
        val length = normalizedTarget.length
        if (length == 0) return false
        val coverage = match.matchedUpTo.toDouble() / length
        return coverage >= config.coverageThreshold(length) && match.endingMatched
    }

    private fun buildProgress(qualifying: Boolean, stable: Boolean): MatchProgress {
        val targetLength = normalizedTarget.length
        return MatchProgress(
            normalizedTarget = normalizedTarget,
            matchedCharacterCount = confirmedCount,
            targetCharacterCount = targetLength,
            coverage = if (targetLength > 0) confirmedCount.toDouble() / targetLength else 0.0,
            endingMatched = endingMatched,
            stable = stable,
            completed = completed || (qualifying && stable),
            consecutiveCompletedResults = consecutiveQualifying,
            algorithmVersion = MATCHING_ALGORITHM_VERSION,
        )
    }
}
