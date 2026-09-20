package com.orange.echocards.data

import com.orange.echocards.domain.learning.LearningMode

/**
 * 存进 Room 的模式名，与 `deck_progress.last_mode`、`card_attempts.mode` 的既有数据保持一致。
 *
 * 界面上的模式字符串（`manual` / `follow_along` / `auto_play`）与领域枚举互转放在 `ui` 层，
 * 这里只负责落库用的名字，避免数据层依赖界面层。
 */
val LearningMode.storageName: String
    get() = when (this) {
        LearningMode.FOLLOW_ALONG -> "follow_along"
        LearningMode.AUTO_PLAY -> "auto_play"
        LearningMode.MANUAL -> "manual"
    }
