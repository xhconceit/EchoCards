package com.orange.echocards.domain.learning.matching

/**
 * 跟读匹配的全部可调参数，默认值取自 docs/architecture/speech-matching.md 第 10 至 13 节。
 *
 * 阈值依赖真机实测（文档第 11 节），这里是唯一的调参入口；测试可以
 * `MatchingConfig.DEFAULT.copy(silenceConfirmMs = 40)` 缩小时间窗口，无需虚拟时间。
 */
data class MatchingConfig(
    /** 用户停止说话后确认完成的等待时间（第 12 节条件 C）。 */
    val silenceConfirmMs: Long = 800,
    /** 连续两次部分结果之间的最小间隔（第 12 节条件 B）。 */
    val minPartialIntervalMs: Long = 300,
    /** 单张卡片允许自动重启识别会话的次数（第 14 节）。 */
    val maxSessionRestarts: Int = 3,
    /** 短文本长度上限（第 13 节）：短文本不能用静音确认。 */
    val shortTextMaxLength: Int = 5,
    val endingWindowShort: Int = 2,
    val endingWindowMedium: Int = 3,
    val endingWindowLong: Int = 5,
    val thresholdTiny: Double = 1.0,
    val thresholdShort: Double = 0.95,
    val thresholdMedium: Double = 0.90,
    val thresholdLong: Double = 0.88,
    private val shortBoundary: Int = 5,
    private val mediumBoundary: Int = 15,
    private val longBoundary: Int = 40,
) {
    /** 第 11 节：长度分档的最低覆盖率。 */
    fun coverageThreshold(targetLength: Int): Double = when {
        targetLength <= shortBoundary -> thresholdTiny
        targetLength <= mediumBoundary -> thresholdShort
        targetLength <= longBoundary -> thresholdMedium
        else -> thresholdLong
    }

    /** 第 10 节：结尾窗口按目标长度分档。 */
    fun endingWindow(targetLength: Int): Int = when {
        targetLength <= 8 -> endingWindowShort
        targetLength <= 20 -> endingWindowMedium
        else -> endingWindowLong
    }

    /**
     * 顺序匹配允许跳过的目标字符数上限。识别错误有限，容错也必须有限，
     * 否则覆盖率会被"跳字"抬起来（第 8 节要求实际实现允许有限错误）。
     *
     * 短文本不给容错额度：第 13 节要求它们必须完整匹配，漏一个字就只读到 3/4，
     * 那是最容易被误判成读完的一类卡片。
     */
    fun maxAlignmentErrors(targetLength: Int): Int =
        if (isShortText(targetLength)) 0 else maxOf(1, targetLength / 10)

    /** 第 13 节的短文本：必须完整匹配，且不能只靠一次结果或静音完成。 */
    fun isShortText(targetLength: Int): Boolean = targetLength <= shortTextMaxLength

    companion object {
        val DEFAULT = MatchingConfig()
    }
}
