package com.orange.echocards.domain.learning

import com.orange.echocards.domain.learning.matching.MatchProgress

/**
 * 学习引擎的状态模型，见 docs/architecture/learning-engine.md 第 2 节。
 *
 * 纯 Kotlin：不依赖 Compose、Room 或 Android 系统类型。
 */

enum class LearningMode { MANUAL, FOLLOW_ALONG, AUTO_PLAY }

/** 学习记录的结论，见 docs/reference/data-model.md 第 5 节。 */
enum class AttemptOutcome(val storageName: String) {
    /** 手动模式切到下一张：只记录浏览过。 */
    VIEWED("viewed"),

    /** 自动跟读读完一张：记录覆盖率和结尾匹配。 */
    READ_COMPLETED("read_completed"),
}

/**
 * 一条待写入的学习记录。
 *
 * 跟读完成时带上匹配结果；匹配文本本身不进入记录（见 speech-matching.md 第 16 节）。
 */
data class LearningAttemptRecord(
    val cardId: String,
    val cardRevision: Int,
    val outcome: AttemptOutcome,
    val coverage: Double? = null,
    val endingMatched: Boolean? = null,
    val algorithmVersion: String? = null,
    val startedAtMs: Long,
    val endedAtMs: Long,
)

enum class LearningPhase {
    IDLE, LOADING, READY, SPEAKING, LISTENING, EVALUATING,
    SHOWING_MEMORY_TIP, ADVANCING, PAUSED, ERROR, DISPOSED,
}

enum class CardFace { FRONT, BACK }

data class LearningError(
    val code: String,
    val message: String,
    val recoverable: Boolean,
)

/** 引擎需要的卡片数据；不直接使用 Room Entity。 */
data class LearningCard(
    val id: String,
    val title: String,
    val content: String,
    val speechText: String?,
    val memoryTip: String?,
    /** 卡片版本，随正文或跟读文本变化；学习记录里保存被读的那一版。 */
    val revision: Int = 1,
) {
    /** 朗读文本：跟读文本优先，留空时用正文。 */
    val spokenText: String get() = speechText?.takeIf { it.isNotBlank() } ?: content

    /** 匹配与朗读都用这一份文本，见 speech-matching.md 第 3 节。 */
    val targetText: String get() = spokenText.trim()
}

data class LearningEngineState(
    val deckId: String = "",
    val mode: LearningMode = LearningMode.MANUAL,
    val phase: LearningPhase = LearningPhase.IDLE,
    val cards: List<LearningCard> = emptyList(),
    val currentIndex: Int = 0,
    val cardFace: CardFace = CardFace.FRONT,
    val operationId: String? = null,
    val speechSegmentIndex: Int = 0,
    val segmentCount: Int = 0,
    val speechRate: Double = 1.0,
    /** 当前卡片的跟读匹配进度；只进状态，不在界面上展示文本。 */
    val matchProgress: MatchProgress? = null,
    val error: LearningError? = null,
) {
    val currentCard: LearningCard? get() = cards.getOrNull(currentIndex)
    val hasNext: Boolean get() = cards.size > 1
}

/** 卡片来源通过接口注入，引擎本身不依赖 Room。 */
fun interface LearningCardSource {
    suspend fun loadCards(deckId: String): List<LearningCard>
}

/**
 * 把一次学习推进落库：学习记录（可为空）与下一张位置在**同一个事务**里写入，
 * 见 docs/reference/data-model.md 第 8 节。
 */
fun interface LearningProgressSink {
    /** 返回是否保存成功；失败时引擎必须保留当前卡片，不能呈现已经切换成功的假象。 */
    suspend fun save(
        deckId: String,
        nextCardId: String,
        mode: LearningMode,
        attempt: LearningAttemptRecord?,
    ): Boolean
}
