package com.orange.echocards

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orange.echocards.data.CardEntity
import com.orange.echocards.data.DeckEntity
import com.orange.echocards.data.EchoDatabase
import com.orange.echocards.data.EchoRepository
import com.orange.echocards.domain.learning.AttemptOutcome
import com.orange.echocards.domain.learning.LearningAttemptRecord
import com.orange.echocards.domain.learning.LearningMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 节点 6 的数据库验收：学习记录与下一张位置必须一起成功、一起失败，
 * 见 docs/reference/data-model.md 第 8 节与 A06。
 */
@RunWith(AndroidJUnit4::class)
class AttemptTransactionTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val database get() = EchoDatabase.get(context)
    private val repository get() = EchoRepository(context)

    @Before
    fun setUp() = runBlocking {
        cleanup()
        val stamp = "2026-09-20T06:00:00Z"
        val dao = database.dao()
        dao.insertDeck(DeckEntity(SEED_DECK_ID, "事务卡组", null, 0, stamp, stamp))
        dao.insertCards(
            listOf(
                CardEntity("tx-card-1", SEED_DECK_ID, "第一张", "第一张的正文", null, null, 0, 1, stamp, stamp),
                CardEntity("tx-card-2", SEED_DECK_ID, "第二张", "第二张的正文", null, null, 1, 1, stamp, stamp),
            )
        )
    }

    @After
    fun tearDown() = runBlocking { cleanup() }

    @Test
    fun savingWritesAttemptAndPositionTogether() = runBlocking {
        val record = LearningAttemptRecord(
            cardId = "tx-card-1",
            cardRevision = 1,
            outcome = AttemptOutcome.READ_COMPLETED,
            coverage = 0.96,
            endingMatched = true,
            algorithmVersion = "v1",
            startedAtMs = 1_700_000_000_000,
            endedAtMs = 1_700_000_005_000,
        )

        val saved = repository.saveLearningTransaction(SEED_DECK_ID, "tx-card-2", LearningMode.FOLLOW_ALONG, record)

        assertTrue("事务应成功", saved)
        assertEquals("位置应指向下一张", "tx-card-2", database.dao().getProgress(SEED_DECK_ID)?.currentCardId)
        assertEquals("应写入一条学习记录", 1, attemptCount())
        assertEquals("跟读完成", "read_completed", lastAttemptOutcome())
    }

    @Test
    fun failedSaveLeavesNoPartialData() = runBlocking {
        val before = database.dao().getProgress(SEED_DECK_ID)
        val record = LearningAttemptRecord(
            cardId = "tx-card-1",
            cardRevision = 1,
            outcome = AttemptOutcome.READ_COMPLETED,
            startedAtMs = 1_700_000_000_000,
            endedAtMs = 1_700_000_005_000,
        )

        // 卡组不存在：学习记录的外键会失败，整个事务必须回滚
        val saved = repository.saveLearningTransaction("missing-deck", "tx-card-2", LearningMode.FOLLOW_ALONG, record)

        assertFalse("外键失败应返回 false", saved)
        assertEquals("回滚后不应留下学习记录", 0, attemptCount())
        assertEquals("回滚后位置不变", before, database.dao().getProgress(SEED_DECK_ID))
    }

    private fun attemptCount(): Int =
        database.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM card_attempts")).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun lastAttemptOutcome(): String? =
        database.query(SimpleSQLiteQuery("SELECT outcome FROM card_attempts LIMIT 1")).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private suspend fun cleanup() {
        database.dao().deleteDeck(SEED_DECK_ID)
        database.openHelper.writableDatabase.execSQL("DELETE FROM card_attempts")
    }

    private companion object {
        const val SEED_DECK_ID = "tx-seed-deck"
    }
}
