package com.orange.echocards

import android.view.KeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orange.echocards.data.CardEntity
import com.orange.echocards.data.DeckEntity
import com.orange.echocards.data.EchoDatabase
import com.orange.echocards.ui.STUDY_CARD_TAG
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

/**
 * 节点 7 R02 的真机验证：屏幕阅读器与键盘必须能切卡，见 docs/design/screens.md 第 12 节。
 *
 * 手势切卡已有 `ScreenWalkthroughTest` 覆盖；这里只验证无障碍与键盘这两条替代路径，
 * 它们是纯 Compose 语义，不需要真的出声或麦克风。
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class StudyAccessibilityTest {

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

    /** 卡片上应暴露「上一张」「下一张」自定义操作，供屏幕阅读器使用。 */
    @Test
    fun cardExposesPreviousAndNextCustomActions() = runBlocking {
        startApp()
        goHome()
        openDeck()
        startManualStudy()

        val labels = rule.onNodeWithTag(STUDY_CARD_TAG).fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }
        assertTrue("卡片应提供切卡自定义操作，实际=$labels",
            labels.containsAll(listOf("上一张", "下一张")))

        val semantics = rule.onNodeWithTag(STUDY_CARD_TAG).fetchSemanticsNode().config
        assertTrue(
            "正面卡片应包含标题和正文的播报语义",
            semantics.getOrNull(SemanticsProperties.ContentDescription)
                ?.any { it.contains("惯性") && it.contains("物体保持") } == true,
        )
        assertTrue(
            "正面卡片应说明点击后的翻面动作",
            semantics.getOrNull(SemanticsProperties.StateDescription)?.contains("点击查看快速记忆点") == true,
        )
    }

    /** 键盘左右方向键切卡：→ 下一张，← 上一张。 */
    @Test
    fun arrowKeysSwitchCards() = runBlocking {
        startApp()
        goHome()
        openDeck()
        startManualStudy()
        awaitText("惯性")

        // focusable() 暴露 RequestFocus；performKeyInput 只把按键发给已聚焦的节点
        rule.onNodeWithTag(STUDY_CARD_TAG).requestFocus()
        diag("after focus 1")
        rule.onNodeWithTag(STUDY_CARD_TAG).performKeyInput { pressKey(Key.DirectionRight) }
        awaitCardAndSettle("加速度")
        diag("after right")

        rule.onNodeWithTag(STUDY_CARD_TAG).requestFocus()
        diag("after focus 2")
        rule.onNodeWithTag(STUDY_CARD_TAG).performKeyInput { pressKey(Key.DirectionLeft) }
        delay(2_500)
        diag("after left")
        awaitCardAndSettle("惯性")
    }

    private fun diag(label: String) {
        val merged = rule.onNodeWithTag(STUDY_CARD_TAG).fetchSemanticsNode().config
        val unmerged = rule.onNodeWithTag(STUDY_CARD_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().config
        val focusedCount = rule.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.Focused, true),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().size
        val texts = rule.onAllNodesWithText("", substring = true).fetchSemanticsNodes()
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
        android.util.Log.i("A11YDIAG", "[$label] mergedFocused=${merged.getOrNull(SemanticsProperties.Focused)} " +
            "unmergedFocused=${unmerged.getOrNull(SemanticsProperties.Focused)} focusedNodes=$focusedCount " +
            "texts=$texts")
    }

    /**
     * 等目标卡片出现后，再等切卡动画落定。
     *
     * 卡片内容在滑出动画结束时就已经更新，但滑入动画和 `switching` 守卫还要再持续一段，
     * 这期间按下的方向键会被守卫直接丢掉，所以下一次按键必须等这次切换完全结束。
     */
    private suspend fun awaitCardAndSettle(title: String) {
        awaitText(title)
        rule.waitForIdle()
        delay(1_500)
    }

    private fun awaitText(text: String) {
        rule.waitUntil(15_000) {
            runCatching { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
        }
    }

    /** 打开卡组详情；学习页要求 Activity 在前台。 */
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
        val stamp = "2026-09-21T06:00:00Z"
        val dao = EchoDatabase.get(context).dao()
        runBlocking {
            dao.deleteDeck(SEED_DECK_ID)
            dao.insertDeck(DeckEntity(id = SEED_DECK_ID, title = "示例卡组", description = "无障碍验收",
                position = 0, createdAt = stamp, updatedAt = stamp))
            dao.insertCards(listOf(
                CardEntity(id = "a11y-card-1", deckId = SEED_DECK_ID, title = "惯性",
                    content = "物体保持原有运动状态的性质", speechText = null,
                    memoryTip = "质量越大，惯性越大", position = 0, revision = 1,
                    createdAt = stamp, updatedAt = stamp),
                CardEntity(id = "a11y-card-2", deckId = SEED_DECK_ID, title = "加速度",
                    content = "速度变化量与时间的比值", speechText = null,
                    memoryTip = null, position = 1, revision = 1,
                    createdAt = stamp, updatedAt = stamp),
            ))
        }
    }

    private companion object {
        const val SEED_DECK_ID = "a11y-seed-deck"
    }
}
