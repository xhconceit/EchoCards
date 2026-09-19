package com.orange.echocards.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.orange.echocards.importdata.ImportedCard
import com.orange.echocards.importdata.ImportedDeck
import com.orange.echocards.importdata.JsonDeckImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class EchoRepository(context: Context) {
    private val database = EchoDatabase.get(context)
    private val dao = database.dao()

    val decks: Flow<List<DeckEntity>> = dao.observeDecks()
    val deckSummaries: Flow<List<DeckSummary>> = dao.observeDeckSummaries()
    val settings: Flow<UserSettingsEntity?> = dao.observeSettings()

    fun deck(id: String): Flow<DeckEntity?> = dao.observeDeck(id)
    fun cards(deckId: String): Flow<List<CardEntity>> = dao.observeCards(deckId)
    fun progress(deckId: String): Flow<DeckProgressEntity?> = dao.observeProgress(deckId)

    suspend fun createDeck(title: String, description: String): String = withContext(Dispatchers.IO) {
        val name = title.trim().requireNotEmpty("请输入卡组名称")
        database.withTransaction {
            val id = UUID.randomUUID().toString()
            val now = now()
            dao.insertDeck(DeckEntity(id, name, description.trim().ifEmpty { null }, dao.allDecks().size, now, now))
            dao.saveProgress(DeckProgressEntity(id, null, updatedAt = now))
            id
        }
    }

    suspend fun updateDeck(id: String, title: String, description: String) = withContext(Dispatchers.IO) {
        val name = title.trim().requireNotEmpty("请输入卡组名称")
        val deck = dao.getDeck(id) ?: return@withContext
        dao.saveDeck(deck.copy(title = name, description = description.trim().ifEmpty { null }, updatedAt = now()))
    }

    suspend fun deleteDeck(id: String) = withContext(Dispatchers.IO) { dao.deleteDeck(id) }

    suspend fun saveCard(
        deckId: String, cardId: String?, title: String, content: String, speechText: String, memoryTip: String,
    ) = withContext(Dispatchers.IO) {
        val name = title.trim().requireNotEmpty("请输入标题")
        val body = content.trim().requireNotEmpty("请输入正文")
        val speech = speechText.trim().ifEmpty { null }
        val tip = memoryTip.trim().ifEmpty { null }
        database.withTransaction {
            val current = cardId?.let { id -> dao.getCards(deckId).find { it.id == id } }
            val timestamp = now()
            if (current == null) {
                dao.insertCard(CardEntity(UUID.randomUUID().toString(), deckId, name, body, speech, tip,
                    dao.getCards(deckId).size, 1, timestamp, timestamp))
            } else {
                val changedSpeech = current.content != body || current.speechText != speech
                dao.saveCard(current.copy(title = name, content = body, speechText = speech, memoryTip = tip,
                    revision = current.revision + if (changedSpeech) 1 else 0, updatedAt = timestamp))
            }
        }
    }

    suspend fun deleteCard(deckId: String, cardId: String) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val before = dao.getCards(deckId)
            val removedIndex = before.indexOfFirst { it.id == cardId }
            if (removedIndex < 0) return@withTransaction
            dao.deleteCard(cardId)
            val after = dao.getCards(deckId)
            after.forEachIndexed { index, card -> if (card.position != index) dao.saveCard(card.copy(position = index)) }
            val progress = dao.getProgress(deckId)
            if (progress?.currentCardId == cardId) {
                val replacement = after.getOrNull(removedIndex.coerceAtMost(after.lastIndex))?.id
                dao.saveProgress(progress.copy(currentCardId = replacement, updatedAt = now()))
            }
        }
    }

    suspend fun moveCard(deckId: String, cardId: String, delta: Int) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val list = dao.getCards(deckId).toMutableList()
            val from = list.indexOfFirst { it.id == cardId }
            if (from < 0) return@withTransaction
            val to = (from + delta).coerceIn(0, list.lastIndex)
            if (from == to) return@withTransaction
            val moved = list.removeAt(from)
            list.add(to, moved)
            list.forEachIndexed { index, card -> if (card.position != index) dao.saveCard(card.copy(position = index)) }
        }
    }

    suspend fun saveProgress(deckId: String, cardId: String?, mode: String) = withContext(Dispatchers.IO) {
        dao.saveProgress(DeckProgressEntity(deckId, cardId, mode, now()))
    }

    /** 学习页一次性读取卡片顺序。 */
    suspend fun loadCards(deckId: String): List<CardEntity> = withContext(Dispatchers.IO) { dao.getCards(deckId) }

    /**
     * 保存学习位置并返回是否成功。失败时 Learning Engine 必须保留当前卡片，
     * 不能呈现已经切换成功的假象（见 docs/development/05-manual-learning.md 的 M05）。
     */
    suspend fun saveLearningPosition(deckId: String, cardId: String, mode: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { dao.saveProgress(DeckProgressEntity(deckId, cardId, mode, now())) }.isSuccess
        }

    suspend fun saveSettings(mode: String, rate: Double, delay: Long) = withContext(Dispatchers.IO) {
        dao.saveSettings(UserSettingsEntity(defaultMode = mode, speechRate = rate, autoAdvanceDelayMs = delay, updatedAt = now()))
    }

    suspend fun findDuplicate(imported: ImportedDeck): DeckEntity? = withContext(Dispatchers.IO) {
        findDuplicateInternal(imported)
    }

    suspend fun importDeck(imported: ImportedDeck, displayTitle: String): String = withContext(Dispatchers.IO) {
        val title = displayTitle.trim().requireNotEmpty("请输入卡组名称")
        try {
            database.withTransaction {
                findDuplicateInternal(imported)?.let { return@withTransaction it.id }
                val id = UUID.randomUUID().toString()
                val timestamp = now()
                dao.insertDeck(DeckEntity(id, title, imported.description.ifEmpty { null },
                    dao.allDecks().size, timestamp, timestamp, imported.fingerprint))
                val cards = imported.cards.mapIndexed { index, card ->
                    CardEntity(UUID.randomUUID().toString(), id, card.title, card.content,
                        card.speechText.ifEmpty { null }, card.memoryTip.ifEmpty { null }, index, 1, timestamp, timestamp)
                }
                dao.insertCards(cards)
                dao.saveProgress(DeckProgressEntity(id, cards.first().id, updatedAt = timestamp))
                id
            }
        } catch (error: SQLiteConstraintException) {
            dao.findByFingerprint(imported.fingerprint)?.id ?: throw error
        }
    }

    private suspend fun findDuplicateInternal(imported: ImportedDeck): DeckEntity? {
        dao.findByFingerprint(imported.fingerprint)?.let { return it }
        return dao.allDecks().firstOrNull { deck ->
            val cards = dao.getCards(deck.id).map { card ->
                ImportedCard(card.title, card.content, card.speechText.orEmpty(), card.memoryTip.orEmpty())
            }
            cards.isNotEmpty() && JsonDeckImport.fingerprint(deck.title, deck.description.orEmpty(), cards) == imported.fingerprint
        }
    }

    private fun String.requireNotEmpty(message: String): String = also { require(it.isNotEmpty()) { message } }

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())
}
