# 数据模型

状态：已确认
Schema 版本：1（设计目标，尚未实现）

## 1. 通用约定

- ID 使用 UUID 字符串。
- 时间使用 UTC ISO 8601 字符串。
- 排序位置从 0 开始。
- 第一版固定普通话，不在卡片表保存可编辑语言。
- 不保存原始录音和完整识别文字。
- 删除卡组时级联删除卡片、位置和学习记录；删除单张卡片时保留历史记录中的卡片 ID。

## 2. 领域模型

```kotlin
typealias ID = String
typealias ISODateTime = String

enum class LearningMode(val value: String) {
    MANUAL("manual"),
    FOLLOW_ALONG("follow_along"),
    AUTO_PLAY("auto_play"),
}

enum class AttemptOutcome(val value: String) {
    VIEWED("viewed"),
    READ_COMPLETED("read_completed"),
}

data class Deck(
    val id: ID,
    val title: String,
    val description: String?,
    val position: Int,
    val createdAt: ISODateTime,
    val updatedAt: ISODateTime,
    val importFingerprint: String?,
)

data class Card(
    val id: ID,
    val deckId: ID,
    val title: String,
    val content: String,
    val speechText: String?,
    val memoryTip: String?,
    val position: Int,
    val revision: Int,
    val createdAt: ISODateTime,
    val updatedAt: ISODateTime,
)

data class DeckProgress(
    val deckId: ID,
    val currentCardId: ID?,
    val lastMode: LearningMode,
    val updatedAt: ISODateTime,
)

data class MatchResult(
    val coverage: Double,
    val endingMatched: Boolean,
    val algorithmVersion: String,
)

data class CardAttempt(
    val id: ID,
    val deckId: ID,
    val cardId: ID,
    val cardRevision: Int,
    val mode: LearningMode,
    val outcome: AttemptOutcome,
    val matchResult: MatchResult?,
    val startedAt: ISODateTime,
    val endedAt: ISODateTime,
)

data class UserSettings(
    val defaultMode: LearningMode,
    val speechRate: Double,
    val autoAdvanceDelayMs: Long,
    val updatedAt: ISODateTime,
)
```

## 3. Deck 与 Card

卡组名称、卡片标题和正文去除首尾空格后不能为空。快速记忆点为空时，学习页面背面显示“暂无快速记忆点”，不把占位文字写入数据库。

朗读和匹配目标：

```kotlin
val targetText = card.speechText?.trim()?.takeIf { it.isNotEmpty() }
    ?: card.content.trim()
```

修改正文或跟读文本时 `revision` 加 1。修改标题、快速记忆点或排序不增加版本。

## 4. 循环位置

每个卡组只有一条 `DeckProgress`。进入学习时按 `currentCardId` 恢复；卡片已删除或为空时回到排序后的第一张。冷启动只读取位置用于首页展示，不自动打开学习页面。

循环索引规则：

```kotlin
val nextIndex = (currentIndex + 1) % cardCount
val previousIndex = (currentIndex - 1 + cardCount) % cardCount
```

切换卡片和退出学习时更新 `currentCardId`。切换模式时更新 `lastMode`，但用户从卡组详情开始学习时仍以当次选择为准。

## 5. 学习记录

| outcome | 适用情况 |
|---|---|
| `viewed` | 手动模式切换到下一张 |
| `read_completed` | 自动跟读达到完成条件 |

自动播放不产生“掌握”或完成记录，只更新当前位置。返回上一张不创建记录。`coverage` 表示文本覆盖率，不是发音分数或掌握程度。

## 6. 设置

```kotlin
fun defaultSettings(now: ISODateTime) = UserSettings(
    defaultMode = LearningMode.MANUAL,
    speechRate = 1.0,
    autoAdvanceDelayMs = 600L,
    updatedAt = now,
)
```

- `speechRate`：`0.5` 到 `2.0`
- `autoAdvanceDelayMs`：`0` 到 `3000`

## 7. SQLite 目标表结构

以下 SQL 表达约束。实现使用 Room Entity、DAO、索引和迁移。

```sql
PRAGMA foreign_keys = ON;

CREATE TABLE decks (
  id TEXT PRIMARY KEY NOT NULL,
  title TEXT NOT NULL CHECK (length(trim(title)) > 0),
  description TEXT,
  position INTEGER NOT NULL CHECK (position >= 0),
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  import_fingerprint TEXT
);

CREATE TABLE cards (
  id TEXT PRIMARY KEY NOT NULL,
  deck_id TEXT NOT NULL,
  title TEXT NOT NULL CHECK (length(trim(title)) > 0),
  content TEXT NOT NULL CHECK (length(trim(content)) > 0),
  speech_text TEXT,
  memory_tip TEXT,
  position INTEGER NOT NULL CHECK (position >= 0),
  revision INTEGER NOT NULL CHECK (revision >= 1),
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  FOREIGN KEY (deck_id) REFERENCES decks(id) ON DELETE CASCADE
);

CREATE TABLE deck_progress (
  deck_id TEXT PRIMARY KEY NOT NULL,
  current_card_id TEXT,
  last_mode TEXT NOT NULL CHECK (
    last_mode IN ('manual', 'follow_along', 'auto_play')
  ),
  updated_at TEXT NOT NULL,
  FOREIGN KEY (deck_id) REFERENCES decks(id) ON DELETE CASCADE,
  FOREIGN KEY (current_card_id) REFERENCES cards(id) ON DELETE SET NULL
);

CREATE TABLE card_attempts (
  id TEXT PRIMARY KEY NOT NULL,
  deck_id TEXT NOT NULL,
  card_id TEXT NOT NULL,
  card_revision INTEGER NOT NULL CHECK (card_revision >= 1),
  mode TEXT NOT NULL CHECK (
    mode IN ('manual', 'follow_along', 'auto_play')
  ),
  outcome TEXT NOT NULL CHECK (
    outcome IN ('viewed', 'read_completed')
  ),
  coverage REAL CHECK (coverage IS NULL OR coverage BETWEEN 0 AND 1),
  ending_matched INTEGER CHECK (ending_matched IS NULL OR ending_matched IN (0, 1)),
  algorithm_version TEXT,
  started_at TEXT NOT NULL,
  ended_at TEXT NOT NULL,
  FOREIGN KEY (deck_id) REFERENCES decks(id) ON DELETE CASCADE
);

CREATE TABLE user_settings (
  id INTEGER PRIMARY KEY NOT NULL CHECK (id = 1),
  default_mode TEXT NOT NULL CHECK (
    default_mode IN ('manual', 'follow_along', 'auto_play')
  ),
  speech_rate REAL NOT NULL CHECK (speech_rate BETWEEN 0.5 AND 2.0),
  auto_advance_delay_ms INTEGER NOT NULL CHECK (
    auto_advance_delay_ms BETWEEN 0 AND 3000
  ),
  updated_at TEXT NOT NULL
);

CREATE INDEX idx_decks_position ON decks(position);
CREATE UNIQUE INDEX idx_decks_import_fingerprint ON decks(import_fingerprint);
CREATE INDEX idx_cards_deck_position ON cards(deck_id, position);
CREATE INDEX idx_attempts_deck_ended ON card_attempts(deck_id, ended_at DESC);
CREATE INDEX idx_attempts_card_ended ON card_attempts(card_id, ended_at DESC);
```

## 8. 事务规则

切换到下一张时，在同一事务中写入适用的学习记录并更新 `deck_progress`。自动跟读只有事务成功后才翻面并进入下一张；自动播放和返回上一张只更新位置。

删除卡组和卡片前由 UI 二次确认。删除卡组依赖外键级联；删除当前卡片后，位置回退到新列表中的合法索引。

外部 JSON 导入先完整解析和校验，再在同一事务中创建新 `Deck`、全部 `Card` 和初始 `DeckProgress`。为导入数据生成新 UUID 和时间戳，卡片 `position` 按数组顺序从 0 开始、`revision` 为 1；文件不携带学习记录。任一写入失败时回滚整次导入。

导入指纹对规范化后的原始卡组名称、说明及有序卡片的 `title`、`content`、`speechText`、`memoryTip` 计算 SHA-256；不包含文件名、外部 ID、JSON 字段顺序和无关字段。空可选字段与缺失字段等价。导入前先与已有导入指纹及卡组当前内容比较；命中时打开已有卡组。新增卡组保存原始导入指纹，即使之后编辑标题或卡片，仍能识别相同文件的重复导入。唯一索引防止并发导入重复写入。手动创建卡组的指纹为 `NULL`；删除卡组后指纹随之删除。

## 9. 数据库版本

Room 导出 schema JSON。每次修改表结构新增 `Migration` 和迁移测试，不能改写已发布迁移。
