package com.orange.echocards.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "decks", indices = [Index("position"), Index(value = ["importFingerprint"], unique = true)])
data class DeckEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String?,
    val position: Int,
    val createdAt: String,
    val updatedAt: String,
    val importFingerprint: String? = null,
)

data class DeckSummary(
    val deckId: String,
    val title: String,
    val cardCount: Int,
    val currentPosition: Int?,
)

@Entity(
    tableName = "cards",
    foreignKeys = [ForeignKey(entity = DeckEntity::class, parentColumns = ["id"], childColumns = ["deckId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["deckId", "position"])],
)
data class CardEntity(
    @PrimaryKey val id: String,
    val deckId: String,
    val title: String,
    val content: String,
    val speechText: String?,
    val memoryTip: String?,
    val position: Int,
    val revision: Int,
    val createdAt: String,
    val updatedAt: String,
)

@Entity(
    tableName = "deck_progress",
    foreignKeys = [ForeignKey(entity = DeckEntity::class, parentColumns = ["id"], childColumns = ["deckId"], onDelete = ForeignKey.CASCADE)],
)
data class DeckProgressEntity(
    @PrimaryKey val deckId: String,
    val currentCardId: String?,
    val lastMode: String = "manual",
    val updatedAt: String,
)

@Entity(
    tableName = "card_attempts",
    foreignKeys = [ForeignKey(entity = DeckEntity::class, parentColumns = ["id"], childColumns = ["deckId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("deckId"), Index("cardId")],
)
data class CardAttemptEntity(
    @PrimaryKey val id: String,
    val deckId: String,
    val cardId: String,
    val cardRevision: Int,
    val mode: String,
    val outcome: String,
    val coverage: Double?,
    val endingMatched: Boolean?,
    val algorithmVersion: String?,
    val startedAt: String,
    val endedAt: String,
)

@Entity(tableName = "user_settings")
data class UserSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val defaultMode: String = "manual",
    val speechRate: Double = 1.0,
    val autoAdvanceDelayMs: Long = 600L,
    val updatedAt: String,
)

@Dao
interface EchoDao {
    @Query("SELECT * FROM decks ORDER BY position, createdAt")
    fun observeDecks(): Flow<List<DeckEntity>>

    @Query("""
        SELECT d.id AS deckId, d.title AS title,
               (SELECT COUNT(*) FROM cards c WHERE c.deckId = d.id) AS cardCount,
               cur.position AS currentPosition
        FROM decks d
        LEFT JOIN deck_progress p ON p.deckId = d.id
        LEFT JOIN cards cur ON cur.id = p.currentCardId
        ORDER BY d.position, d.createdAt
    """)
    fun observeDeckSummaries(): Flow<List<DeckSummary>>

    @Query("SELECT * FROM decks ORDER BY position, createdAt")
    suspend fun allDecks(): List<DeckEntity>

    @Query("SELECT * FROM decks WHERE id = :id LIMIT 1")
    fun observeDeck(id: String): Flow<DeckEntity?>

    @Query("SELECT * FROM decks WHERE id = :id LIMIT 1")
    suspend fun getDeck(id: String): DeckEntity?

    @Query("SELECT * FROM decks WHERE importFingerprint = :fingerprint LIMIT 1")
    suspend fun findByFingerprint(fingerprint: String): DeckEntity?

    @Query("SELECT * FROM cards WHERE deckId = :deckId ORDER BY position, createdAt")
    fun observeCards(deckId: String): Flow<List<CardEntity>>

    @Query("SELECT * FROM cards WHERE deckId = :deckId ORDER BY position, createdAt")
    suspend fun getCards(deckId: String): List<CardEntity>

    @Query("SELECT * FROM deck_progress WHERE deckId = :deckId LIMIT 1")
    fun observeProgress(deckId: String): Flow<DeckProgressEntity?>

    @Query("SELECT * FROM deck_progress WHERE deckId = :deckId LIMIT 1")
    suspend fun getProgress(deckId: String): DeckProgressEntity?

    @Query("SELECT * FROM user_settings WHERE id = 1 LIMIT 1")
    fun observeSettings(): Flow<UserSettingsEntity?>

    @Insert
    suspend fun insertDeck(deck: DeckEntity)

    @Insert
    suspend fun insertCards(cards: List<CardEntity>)

    @Insert
    suspend fun insertCard(card: CardEntity)

    @Upsert
    suspend fun saveDeck(deck: DeckEntity)

    @Upsert
    suspend fun saveCard(card: CardEntity)

    @Upsert
    suspend fun saveProgress(progress: DeckProgressEntity)

    @Upsert
    suspend fun saveSettings(settings: UserSettingsEntity)

    @Insert
    suspend fun insertAttempt(attempt: CardAttemptEntity)

    @Query("DELETE FROM decks WHERE id = :id")
    suspend fun deleteDeck(id: String)

    @Query("DELETE FROM cards WHERE id = :id")
    suspend fun deleteCard(id: String)
}

@Database(
    entities = [DeckEntity::class, CardEntity::class, DeckProgressEntity::class, CardAttemptEntity::class, UserSettingsEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class EchoDatabase : RoomDatabase() {
    abstract fun dao(): EchoDao

    companion object {
        @Volatile private var instance: EchoDatabase? = null

        fun get(context: Context): EchoDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, EchoDatabase::class.java, "echocards.db")
                .build().also { instance = it }
        }
    }
}
