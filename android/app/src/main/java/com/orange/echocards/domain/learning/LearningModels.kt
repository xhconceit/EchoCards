package com.orange.echocards.domain.learning

/**
 * 学习引擎的状态模型，见 docs/architecture/learning-engine.md 第 2 节。
 *
 * 纯 Kotlin：不依赖 Compose、Room 或 Android 系统类型。
 */

enum class LearningMode { MANUAL, FOLLOW_ALONG, AUTO_PLAY }

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
) {
    /** 朗读文本：跟读文本优先，留空时用正文。 */
    val spokenText: String get() = speechText?.takeIf { it.isNotBlank() } ?: content
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
    val error: LearningError? = null,
) {
    val currentCard: LearningCard? get() = cards.getOrNull(currentIndex)
    val hasNext: Boolean get() = cards.size > 1
}
