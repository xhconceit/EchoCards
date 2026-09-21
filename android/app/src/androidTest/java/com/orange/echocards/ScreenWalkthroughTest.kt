package com.orange.echocards

import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.orange.echocards.data.CardEntity
import com.orange.echocards.data.DeckEntity
import com.orange.echocards.data.EchoDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertTrue
import java.io.File

/**
 * 页面走查：在真机上走一遍「首页 → 新建卡组 → 我的 → 卡组详情 → 学习」，并逐屏截图。
 *
 * 用途是图标/视觉走查与主流程冒烟。截图写到应用外部目录 shots/ 下，可这样取回：
 * ```
 * ./gradlew -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true :app:connectedDebugAndroidTest
 * adb pull /sdcard/Android/data/com.orange.echocards.debug/files/shots
 * ```
 * 数据直接写 Room，避免依赖输入法（ADB 无法输入中文）；用 `am start` 拉起界面，
 * 因为 MIUI/HyperOS 会拦截应用自己从后台启动 Activity。
 *
 * 注意：Compose 测试要求被测 Activity 一直在前台，跑的时候手机上别切到其他应用，否则会一直等。
 */
@RunWith(AndroidJUnit4::class)
class ScreenWalkthroughTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private val device by lazy { UiDevice.getInstance(instrumentation) }

    private val shots: File by lazy {
        File(instrumentation.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
    }

    @Test
    fun walkThroughScreens() {
        runCatching { device.wakeUp() }
        seedDeck()
        goHome()

        // 首页：搜索、创建/导入、底部导航图标
        awaitText("示例卡组")
        shot("01-home")

        // 新建卡组弹层
        rule.onNodeWithContentDescription("创建卡组").performClick()
        rule.waitForIdle()
        shot("02-deck-sheet")
        rule.onNodeWithText("取消").performClick()

        // 我的：底部导航选中态
        rule.onNodeWithText("我的").performClick()
        rule.waitForIdle()
        shot("03-mine")
        rule.onNodeWithText("首页").performClick()

        // 卡组详情：顶栏返回/编辑、右下角新建卡片
        rule.onNodeWithText("示例卡组").performClick()
        rule.onNodeWithContentDescription("返回").assertIsDisplayed()
        rule.onNodeWithContentDescription("编辑").assertIsDisplayed()
        rule.waitForIdle()
        shot("04-deck-detail")

        // 卡片左滑露出删除
        rule.onNodeWithText("惯性", substring = true).performTouchInput { swipeLeft() }
        rule.waitForIdle()
        shot("05-card-swipe-delete")
        rule.onNodeWithText("惯性", substring = true).performClick()

        // 学习页：点「开始学习」先弹学习方式选择，选完才进学习页
        rule.onNodeWithText("开始学习").performClick()
        rule.waitForIdle()
        shot("06-study-mode-sheet")
        rule.onNodeWithText("手动学习").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("退出学习").assertIsDisplayed()
        rule.onNodeWithContentDescription("切换学习方式").assertIsDisplayed()
        shot("07-study-manual")

        rule.onNodeWithContentDescription("朗读").performClick()
        rule.waitForIdle()
        shot("08-study-speaking")

        // 切卡动画中间帧：滑动松手后约 130ms 抓一张，正常应看到卡片位移
        rule.onNodeWithText("惯性", substring = true).performTouchInput { swipeLeft() }
        Thread.sleep(130)
        shotNow("09-card-switch-mid")
        rule.waitForIdle()

        // 学习页顶栏也显示「手动学习」，这里只截图，用返回键关掉弹层避免文本重名
        rule.onNodeWithContentDescription("切换学习方式").performClick()
        rule.waitForIdle()
        shot("10-mode-sheet")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)

        rule.onNodeWithContentDescription("退出学习").performClick()
        rule.waitForIdle()
        shot("11-back-to-detail")

        // 页面切换动画中间帧：返回首页后重新进入卡组
        rule.onNodeWithContentDescription("返回").performClick()
        rule.waitForIdle()
        shot("12-back-home")
    }

    /**
     * 用测试时钟验证切换动画确实存在（截图会滞后，不能作为依据）：
     * 冻结时钟推进到过渡中段，页面切换应新旧页面并存，切卡应产生位移。
     */
    @Test
    fun transitionsAreAnimated() {
        runCatching { device.wakeUp() }
        seedDeck()
        goHome()
        awaitText("示例卡组")

        // 先预热：首次进入详情页要异步查库，冻结时钟会连重组一起停住，采样会落空
        rule.onNodeWithText("示例卡组").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("返回").performClick()
        rule.waitForIdle()

        // 页面切换：冻结动画时钟重放一次，过渡中段新旧页面应同时在组合里
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("示例卡组").performClick()
        rule.mainClock.advanceTimeBy(140)
        assertTrue("过渡中应同时存在首页与详情页",
            rule.onAllNodesWithText("搜索卡组").fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodesWithText("开始学习").fetchSemanticsNodes().isNotEmpty())
        shotNow("13-page-transition-frozen")
        rule.mainClock.advanceTimeBy(400)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertTrue("过渡结束后应只剩详情页",
            rule.onAllNodesWithText("开始学习").fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodesWithText("搜索卡组").fetchSemanticsNodes().isEmpty())

        // 切卡：动画中段卡片应有位移
        rule.onNodeWithText("开始学习").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("手动学习").performClick()
        rule.waitForIdle()
        val before = rule.onNodeWithText("惯性", substring = true).fetchSemanticsNode().boundsInRoot.left
        rule.onNodeWithText("惯性", substring = true).performTouchInput { swipeLeft() }
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(90)
        val during = rule.onNodeWithText("惯性", substring = true).fetchSemanticsNode().boundsInRoot.left
        rule.mainClock.autoAdvance = true
        assertTrue("切卡动画中卡片应左移：before=$before during=$during", during < before - 80f)
    }

    /** 用例结束清掉走查数据，避免污染手机上已有的卡组。 */
    @After
    fun removeSeededDeck() {
        runBlocking { EchoDatabase.get(instrumentation.targetContext).dao().deleteDeck(SEED_DECK_ID) }
    }

    /** 等某个文本出现在界面上；界面还没 compose 时查询会抛异常，这里当作“还没出现”。 */
    private fun awaitText(text: String) {
        rule.waitUntil(15_000) {
            runCatching { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
        }
    }

    /**
     * 同一个 instrumentation 进程里多个用例共用 app 进程，界面会停在上个用例的页面，
     * 这里按返回键回到首页（首页的返回键会退出应用，所以只在非首页时按）。
     */
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

    /** 直接写库造数据：一个卡组 + 两张卡片。 */
    private fun seedDeck() {
        val stamp = "2026-09-19T05:00:00Z"
        val dao = EchoDatabase.get(instrumentation.targetContext).dao()
        runBlocking {
            dao.deleteDeck(SEED_DECK_ID)
            dao.insertDeck(DeckEntity(id = SEED_DECK_ID, title = "示例卡组", description = "图标走查用",
                position = 0, createdAt = stamp, updatedAt = stamp))
            dao.insertCards(listOf(
                CardEntity(id = "seed-card-1", deckId = SEED_DECK_ID, title = "惯性",
                    content = "物体保持原有运动状态的性质", speechText = null,
                    memoryTip = "质量越大，惯性越大", position = 0, revision = 1,
                    createdAt = stamp, updatedAt = stamp),
                CardEntity(id = "seed-card-2", deckId = SEED_DECK_ID, title = "加速度",
                    content = "速度变化量与时间的比值", speechText = null,
                    memoryTip = null, position = 1, revision = 1,
                    createdAt = stamp, updatedAt = stamp),
            ))
        }
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        // UiDevice 截的是屏幕上的最后一帧，等一帧真正上屏，避免拍到旧画面
        device.waitForIdle()
        Thread.sleep(500)
        // 弹层是独立 window，Compose 的 onRoot().captureToImage() 无法跨窗口合成，这里整屏截图。
        device.takeScreenshot(File(shots, "$name.png"))
    }

    /** 抓动画中间帧：不能等 idle，UiDevice.waitForIdle 会等 500ms 静默期，动画早结束了。 */
    private fun shotNow(name: String) {
        device.takeScreenshot(File(shots, "$name.png"))
    }

    private companion object {
        const val SEED_DECK_ID = "seed-deck"
    }
}
