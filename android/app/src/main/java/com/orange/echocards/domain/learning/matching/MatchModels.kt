package com.orange.echocards.domain.learning.matching

/**
 * 跟读匹配的领域模型，见 docs/architecture/speech-matching.md。
 *
 * 纯 Kotlin：不依赖 Compose、Room、Android 类型，也不做时间判断（时间由调用方传入，
 * 见 [CompletionTracker]）。
 */

/** 匹配算法版本，写入 card_attempts.algorithmVersion。 */
const val MATCHING_ALGORITHM_VERSION = "v1"

/** 一次识别结果的来源，见设计文档第 2 节。 */
enum class RecognitionSignal { PARTIAL, FINAL, SILENCE }

/**
 * 一次跟读的匹配进度。**不包含识别文本**：完整 transcript 只存在于内存里，
 * 不进入 SQLite、日志或 UI 状态（设计文档第 16 节）。
 */
data class MatchProgress(
    val normalizedTarget: String,
    val matchedCharacterCount: Int,
    val targetCharacterCount: Int,
    val coverage: Double,
    val endingMatched: Boolean,
    val stable: Boolean,
    val completed: Boolean,
    val consecutiveCompletedResults: Int,
    val algorithmVersion: String,
)

/** 设计文档第 2 节的输入类型；保留下来与文档对齐，实际匹配走 [TextMatcher]。 */
data class MatchInput(
    val targetText: String,
    val transcript: String,
    val previousProgress: MatchProgress?,
    val signal: RecognitionSignal,
)
