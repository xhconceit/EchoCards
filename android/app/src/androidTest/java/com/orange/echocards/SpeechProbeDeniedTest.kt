package com.orange.echocards

import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orange.echocards.domain.speech.MicrophonePermission
import com.orange.echocards.speech.android.AndroidSpeechServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * V05：麦克风权限被拒绝时的表现。
 *
 * 单独一个类，且**不主动授权**：撤销运行时权限会杀掉 App 进程，所以必须在跑这个用例前
 * 用 adb 撤销（见 docs/development/01-voice-prototype.md 的执行命令），不能在本进程里撤销。
 *
 * ```bash
 * adb shell pm revoke com.orange.echocards.debug android.permission.RECORD_AUDIO
 * ./gradlew -Pandroid.testInstrumentationRunnerArguments.class=com.orange.echocards.SpeechProbeDeniedTest \
 *   -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true :app:connectedDebugAndroidTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class SpeechProbeDeniedTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun v05_permissionDeniedKeepsManualMode() = runBlocking {
        // 撤销权限会杀掉进程，本进程内撤不了；如果同一次运行里别的用例已经授权，
        // 这条用例就跳过，避免随机失败（单独跑之前记得先 adb shell pm revoke）。
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        org.junit.Assume.assumeFalse("麦克风权限已被授予，请先 adb shell pm revoke 再单独运行本用例", granted)

        val services = AndroidSpeechServices.create(context)
        try {
            assertEquals("未授权且没有 UI 承接请求时应为已拒绝", MicrophonePermission.DENIED,
                services.capabilities.requestMicrophonePermission())
            val capabilities = services.capabilities.getCapabilities("zh-CN")
            assertEquals("能力检查应报告已拒绝", MicrophonePermission.DENIED, capabilities.microphonePermission)
            assertEquals("识别能力应与系统一致",
                android.speech.SpeechRecognizer.isRecognitionAvailable(context), capabilities.recognitionAvailable)
        } finally {
            services.recognizer.dispose()
            services.player.dispose()
            services.audioFocus.deactivate()
        }

        // 页面要说明原因，并且不依赖语音的功能仍然可用
        startApp()
        rule.waitForIdle()
        rule.onNodeWithText("我的").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("语音探针").performClick()
        rule.waitForIdle()
        assertTrue("探针页应显示权限被拒绝",
            rule.onAllNodesWithText("已拒绝", substring = true).fetchSemanticsNodes().isNotEmpty())

        // 返回「我的」再切到首页：手动模式不依赖麦克风权限
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitForIdle()
        rule.onNodeWithText("首页").performClick()
        rule.waitForIdle()
        assertTrue("首页仍可用（手动模式不受影响）",
            rule.onAllNodesWithText("搜索卡组").fetchSemanticsNodes().isNotEmpty())
    }

    private fun startApp() {
        val component = "${context.packageName}/com.orange.echocards.MainActivity"
        instrumentation.uiAutomation.executeShellCommand("am start -n $component").use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).readBytes()
        }
    }
}
