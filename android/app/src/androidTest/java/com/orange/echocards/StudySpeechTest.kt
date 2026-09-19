package com.orange.echocards

import android.Manifest
import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orange.echocards.data.CardEntity
import com.orange.echocards.data.DeckEntity
import com.orange.echocards.data.EchoDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 节点 5 的真机验证：学习页的手动朗读必须真的出声，并且由真实完成事件驱动回到可朗读状态。
 *
 * 这与探针页不同：这里走的是产品路径（首页 → 卡组 → 开始学习 → 手动学习 → 点朗读）。
 */
@RunWith(AndroidJUnit4::class)
class StudySpeechTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before
    fun setUp() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        seedDeck()
    }

    @After
    fun tearDown() {
        runBlocking { EchoDatabase.get(context).dao().deleteDeck(SEED_DECK_ID) }
    }

    @Test
    fun manualStudyReadsAloudAndReturnsToReady() = runBlocking {
        startApp()
        goHome()
        openManualStudy()

        val baseline = recordMicPeak(1_200) { }
        val duringSpeech = recordMicPeak(20_000) {
            rule.onNodeWithContentDescription("朗读").performClick()
            // 先进入朗读中（按钮变成“暂停朗读”），读完再回到“朗读”
            rule.waitUntil(20_000) {
                runCatching {
                    rule.onAllNodesWithContentDescription("暂停朗读").fetchSemanticsNodes().isNotEmpty()
                }.getOrDefault(false)
            }
            rule.waitUntil(25_000) {
                runCatching {
                    rule.onAllNodesWithContentDescription("暂停朗读").fetchSemanticsNodes().isEmpty() &&
                        rule.onAllNodesWithContentDescription("朗读").fetchSemanticsNodes().isNotEmpty()
                }.getOrDefault(false)
            }
        }

        val dir = File(context.getExternalFilesDir(null), "shots").apply { mkdirs() }
        File(dir, "study-audibility.txt").writeText("baseline=$baseline\nduringSpeech=$duringSpeech\n")
        assertTrue("学习页朗读应真的出声：baseline=$baseline during=$duringSpeech",
            duringSpeech > maxOf(baseline * 3, 800))
    }

    /** 朗读中切卡：立即停止旧朗读，并回到可朗读状态。 */
    @Test
    fun switchingCardStopsSpeech() = runBlocking {
        startApp()
        goHome()
        openManualStudy()

        rule.onNodeWithContentDescription("朗读").performClick()
        rule.waitUntil(20_000) {
            runCatching {
                rule.onAllNodesWithContentDescription("暂停朗读").fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }

        rule.onNodeWithText("惯性", substring = true).performTouchInput { swipeLeft() }
        rule.waitUntil(20_000) {
            runCatching {
                rule.onAllNodesWithContentDescription("朗读").fetchSemanticsNodes().isNotEmpty() &&
                    rule.onAllNodesWithText("加速度", substring = true).fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        assertTrue("切卡后应回到可朗读状态",
            rule.onAllNodesWithContentDescription("朗读").fetchSemanticsNodes().isNotEmpty())
    }

    private fun openManualStudy() {
        rule.onNodeWithText("示例卡组").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("开始学习").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("手动学习").performClick()
        rule.waitForIdle()
    }

    private fun seedDeck() {
        val stamp = "2026-09-19T06:00:00Z"
        val dao = EchoDatabase.get(context).dao()
        runBlocking {
            dao.deleteDeck(SEED_DECK_ID)
            dao.insertDeck(DeckEntity(id = SEED_DECK_ID, title = "示例卡组", description = "朗读验收",
                position = 0, createdAt = stamp, updatedAt = stamp))
            dao.insertCards(listOf(
                CardEntity(id = "study-card-1", deckId = SEED_DECK_ID, title = "惯性",
                    content = "物体保持原有运动状态的性质。质量越大惯性越大", speechText = null,
                    memoryTip = "质量越大，惯性越大", position = 0, revision = 1,
                    createdAt = stamp, updatedAt = stamp),
                CardEntity(id = "study-card-2", deckId = SEED_DECK_ID, title = "加速度",
                    content = "速度变化量与时间的比值", speechText = null,
                    memoryTip = null, position = 1, revision = 1,
                    createdAt = stamp, updatedAt = stamp),
            ))
        }
    }

    private fun startApp() {
        val component = "${context.packageName}/com.orange.echocards.MainActivity"
        instrumentation.uiAutomation.executeShellCommand("am start -n $component").use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).readBytes()
        }
    }

    private fun goHome() {
        repeat(3) {
            val onHome = runCatching {
                rule.onAllNodesWithText("搜索卡组").fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
            if (onHome) return
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            rule.waitForIdle()
        }
    }

    private companion object {
        const val SEED_DECK_ID = "study-seed-deck"
    }
}
