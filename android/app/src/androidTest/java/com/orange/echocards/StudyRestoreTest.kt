package com.orange.echocards

import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orange.echocards.data.CardEntity
import com.orange.echocards.data.DeckEntity
import com.orange.echocards.data.EchoDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

/**
 * 节点 5 M03 的真机验证：切卡后退出再进入，必须从保存的那张卡恢复。
 *
 * 冷启动（进程级重启）不在 instrumentation 内验证——`am force-stop` 会连带杀掉测试进程；
 * 那一条按代码检查（`EchoApp` 的 `page` 初值为首页，学习会话不持久化）+ 手工走查确认。
 */
@RunWith(AndroidJUnit4::class)
class StudyRestoreTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before
    fun setUp() {
        seedDeck()
    }

    @After
    fun tearDown() {
        runBlocking { EchoDatabase.get(context).dao().deleteDeck(SEED_DECK_ID) }
    }

    @Test
    fun reenteringStudyRestoresTheSavedCard() = runBlocking {
        startApp()
        goHome()
        openDeck()
        startManualStudy()
        awaitText("惯性")

        // 第 1 张右滑回到最后一张（循环），位置写第 2 张
        rule.onNodeWithText("惯性", substring = true).performTouchInput { swipeRight() }
        awaitText("加速度")

        rule.onNodeWithContentDescription("退出学习").performClick()
        rule.waitForIdle()
        awaitText("开始学习")
        // 等 Room 的进度流把新位置推到界面，否则再次进入可能读到旧位置
        delay(800)

        startManualStudy()
        awaitText("加速度")
    }

    private fun awaitText(text: String) {
        rule.waitUntil(15_000) {
            runCatching { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
        }
    }

    private fun openDeck() {
        rule.onNodeWithText("示例卡组").performClick()
        rule.waitForIdle()
    }

    private fun startManualStudy() {
        rule.onNodeWithText("开始学习").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("手动学习").performClick()
        rule.waitForIdle()
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

    private fun startApp() {
        val component = "${context.packageName}/com.orange.echocards.MainActivity"
        instrumentation.uiAutomation.executeShellCommand("am start -n $component").use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).readBytes()
        }
    }

    private fun seedDeck() {
        val stamp = "2026-09-21T06:30:00Z"
        val dao = EchoDatabase.get(context).dao()
        runBlocking {
            dao.deleteDeck(SEED_DECK_ID)
            dao.insertDeck(DeckEntity(id = SEED_DECK_ID, title = "示例卡组", description = "位置恢复验收",
                position = 0, createdAt = stamp, updatedAt = stamp))
            dao.insertCards(listOf(
                CardEntity(id = "restore-card-1", deckId = SEED_DECK_ID, title = "惯性",
                    content = "物体保持原有运动状态的性质", speechText = null,
                    memoryTip = "质量越大，惯性越大", position = 0, revision = 1,
                    createdAt = stamp, updatedAt = stamp),
                CardEntity(id = "restore-card-2", deckId = SEED_DECK_ID, title = "加速度",
                    content = "速度变化量与时间的比值", speechText = null,
                    memoryTip = null, position = 1, revision = 1,
                    createdAt = stamp, updatedAt = stamp),
            ))
        }
    }

    private companion object {
        const val SEED_DECK_ID = "restore-seed-deck"
    }
}
