package com.orange.echocards

import android.Manifest
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orange.echocards.domain.speech.MicrophonePermission
import com.orange.echocards.domain.speech.RecognitionRequest
import com.orange.echocards.domain.speech.SpeakRequest
import com.orange.echocards.domain.speech.SpeechPlayerEvent
import com.orange.echocards.domain.speech.SpeechRecognizerEvent
import com.orange.echocards.domain.speech.SpeechServices
import com.orange.echocards.speech.android.AndroidSpeechServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 节点 1 真机语音探针的自动部分，对应 docs/development/01-voice-prototype.md 的 V01–V05。
 *
 * 能自动化的：TTS 完成事件、识别服务能否被第三方 App 使用、取消后旧回调被丢弃、
 * 后台停麦、权限状态。**需要人工确认**的只有 V01 的“实际听到声音”和 V02 的“读到内容被识别出来”。
 */
@RunWith(AndroidJUnit4::class)
class SpeechProbeTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var services: SpeechServices

    @Before
    fun setUp() {
        grantMicrophone()
        services = AndroidSpeechServices.create(context)
    }

    @After
    fun tearDown() {
        runBlocking {
            services.recognizer.dispose()
            services.player.dispose()
            services.audioFocus.deactivate()
        }
        grantMicrophone()
    }

    /** V01：朗读普通话固定卡片，收到 started → completed；声音是否真的响需要人耳确认。 */
    @Test
    fun v01_speaksAndReceivesCompleted() = runBlocking {
        val events = mutableListOf<SpeechPlayerEvent>()
        val collector = collect(services.player.events, events)

        services.player.speak(SpeakRequest("v01", FIXED_TEXT, LANGUAGE, rate = 1.0))
        awaitUntil("朗读完成事件", events) {
            events.any { it is SpeechPlayerEvent.Completed || it is SpeechPlayerEvent.Error }
        }
        collector.cancel()

        val error = events.filterIsInstance<SpeechPlayerEvent.Error>().firstOrNull()
        assertNull("朗读不应报错，实际：$error", error)
        assertTrue("应先收到 started：$events", events.any { it is SpeechPlayerEvent.Started })
        assertTrue("应收到 completed：$events", events.any { it is SpeechPlayerEvent.Completed })
    }

    /** V01 反向：stop() 只产生 stopped，不能产生 completed。 */
    @Test
    fun v01b_stopNeverCompletes() = runBlocking {
        val events = mutableListOf<SpeechPlayerEvent>()
        val collector = collect(services.player.events, events)

        services.player.speak(SpeakRequest("v01b", LONG_TEXT, LANGUAGE, rate = 1.0))
        awaitUntil("朗读开始或失败", events) {
            events.any { it is SpeechPlayerEvent.Started || it is SpeechPlayerEvent.Error }
        }
        services.player.stop("v01b")
        awaitUntil("停止事件", events) {
            events.any { it is SpeechPlayerEvent.Stopped || it is SpeechPlayerEvent.Completed }
        }
        delay(1_500)
        collector.cancel()

        assertTrue("应收到 stopped：$events", events.any { it is SpeechPlayerEvent.Stopped })
        assertTrue("stop() 之后不得出现 completed：$events", events.none { it is SpeechPlayerEvent.Completed })
    }

    /** V02：朗读完成后再收音，识别服务应给出 ready；识别文字本身需要人对着手机读。 */
    @Test
    fun v02_recognitionReachesReady() = runBlocking {
        val events = mutableListOf<SpeechRecognizerEvent>()
        val collector = collect(services.recognizer.events, events)

        services.recognizer.start(RecognitionRequest("v02", LANGUAGE, partialResults = true, preferOnDevice = false))
        awaitUntil("识别就绪或失败", events) {
            events.any { it is SpeechRecognizerEvent.Ready || it is SpeechRecognizerEvent.Error }
        }
        collector.cancel()

        val error = events.filterIsInstance<SpeechRecognizerEvent.Error>().firstOrNull()
        assertNull("识别服务应可用于第三方 App，实际错误：$error", error)
        assertTrue("应收到 ready：$events", events.any { it is SpeechRecognizerEvent.Ready })
    }

    /** V03：取消后到达的旧回调必须被忽略，不能用后续结果更新学习状态。 */
    @Test
    fun v03_cancelDropsLateCallbacks() = runBlocking {
        val events = mutableListOf<SpeechRecognizerEvent>()
        val collector = collect(services.recognizer.events, events)

        services.recognizer.start(RecognitionRequest("v03", LANGUAGE, partialResults = true, preferOnDevice = false))
        awaitUntil("识别会话开始（ready 或 error）", events) {
            events.any { it is SpeechRecognizerEvent.Ready || it is SpeechRecognizerEvent.Error }
        }
        services.recognizer.cancel("v03")
        val afterCancel = events.size
        delay(3_000)
        collector.cancel()

        // 识别可能已因“没检测到说话”自然终止，这时 cancel() 本来就该是空操作；
        // 但无论哪种情况，取消之后都不允许再出现识别结果。
        val late = events.drop(afterCancel).filter {
            it is SpeechRecognizerEvent.PartialResult || it is SpeechRecognizerEvent.FinalResult
        }
        assertTrue("取消后不应再出现识别结果：$late（全部事件：$events）", late.isEmpty())
    }

    /**
     * V02 顺序要求：一键自检必须**等朗读 completed 之后**才启动识别。
     *
     * 通过探针日志的渲染顺序断言：`listen probe-asr…` 必须出现在 `→ completed probe-tts…` 下方。
     */
    @Test
    fun v02b_selfCheckListensOnlyAfterSpeechCompletes() {
        startApp()
        goHome()
        rule.onNodeWithText("我的").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("语音探针").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("一键自检（朗读 → 识别 → 完成）").performClick()

        rule.waitUntil(90_000) {
            runCatching {
                rule.onAllNodesWithText("→ completed probe-tts", substring = true).fetchSemanticsNodes().isNotEmpty() &&
                    rule.onAllNodesWithText("listen probe-asr", substring = true).fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        val spokenAt = logTimestamp("→ completed probe-tts")
        val listenAt = logTimestamp("listen probe-asr")
        assertTrue("识别必须在朗读完成之后启动：completed@$spokenAt listen@$listenAt",
            spokenAt != null && listenAt != null && listenAt > spokenAt)

        // 自检要走到终结步骤（房间里没人说话时应是“没有收到识别结果”）
        rule.waitUntil(90_000) {
            runCatching { rule.onAllNodesWithText("3/3", substring = true).fetchSemanticsNodes().isNotEmpty() }
                .getOrDefault(false)
        }
        shot("probe-self-check")
    }

    /** V04：进入后台立即停止收音，回到前台保持暂停（走探针页面的真实生命周期）。 */
    @Test
    fun v04_backgroundStopsRecognition() {
        startApp()
        goHome()
        rule.onNodeWithText("我的").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("语音探针").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("开始识别").performClick()
        rule.waitForIdle()
        shot("probe-before-background")

        // 回桌面让 App 进入后台
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_HOME)
        Thread.sleep(1_500)
        startApp()
        rule.waitForIdle()
        shot("probe-after-background")

        assertTrue("后台应取消收音并写进日志",
            rule.onAllNodesWithText("进入后台：取消收音", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue("回到前台应保持暂停",
            rule.onAllNodesWithText("回到前台：保持暂停", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue("回到前台后不应仍在收音",
            rule.onAllNodesWithText("收音中", substring = true).fetchSemanticsNodes().isEmpty())
    }

    /**
     * 取日志行自带的时间戳（形如 `14:31:18.826  → completed probe-tts-1`）。
     * 用文本而不是几何位置比较顺序：探针日志在可滚动区域里，boundsInRoot 不稳定。
     */
    private fun logTimestamp(marker: String): String? = rule
        .onAllNodesWithText(marker, substring = true)
        .fetchSemanticsNodes()
        .mapNotNull { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
        }
        .map { it.substringBefore("  ") }
        .minOrNull()

    /**
     * V01 加强证据：朗读期间系统侧应看到**活跃音频输出**，而不只是引擎报告完成。
     *
     * 用 AudioManager.isMusicActive 在朗读过程中轮询（TTS 默认走 STREAM_MUSIC），
     * 能在没有人耳的情况下证明音频确实被送到了输出设备。
     */
    @Test
    fun v01c_speechIsRoutedToAnAudioOutput() = runBlocking {
        val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val events = mutableListOf<SpeechPlayerEvent>()
        val collector = collect(services.player.events, events)

        services.player.speak(SpeakRequest("v01c", FIXED_TEXT, LANGUAGE, rate = 1.0))
        var sawActiveOutput = false
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (audioManager.isMusicActive) sawActiveOutput = true
            if (events.any { it is SpeechPlayerEvent.Completed || it is SpeechPlayerEvent.Error }) break
            delay(50)
        }
        // 播放结束后音频可能立刻停止，再查一次兜底
        val activeAfter = audioManager.isMusicActive
        collector.cancel()

        assertTrue("应先收到 completed：$events", events.any { it is SpeechPlayerEvent.Completed })
        assertTrue("朗读期间系统应观察到活跃音频输出（轮询=$sawActiveOutput, 结束=$activeAfter）",
            sawActiveOutput || activeAfter)
    }

    /**
     * V01 客观可听性：一边放 TTS 一边用麦克风采样，播放期间的峰值必须明显高于环境底噪。
     *
     * 只断言“引擎报告完成”或“音频流活跃”都不够 —— 音量过低或被系统策略压掉时，那两条照样成立。
     * 这条用例会临时把媒体音量调到 70%，测完恢复。
     */
    @Test
    fun v01d_speechIsAudibleViaMicrophone() = runBlocking {
        val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val originalVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (maxVolume * 0.7f).toInt(), 0)

        try {
            val baseline = recordPeak(1_200) { }
            val events = mutableListOf<SpeechPlayerEvent>()
            val collector = collect(services.player.events, events)
            val duringSpeech = recordPeak(8_000) {
                services.player.speak(SpeakRequest("v01d", FIXED_TEXT, LANGUAGE, rate = 1.0))
                awaitUntil("朗读完成事件", events) {
                    events.any { it is SpeechPlayerEvent.Completed || it is SpeechPlayerEvent.Error }
                }
            }
            collector.cancel()

            val dir = java.io.File(context.getExternalFilesDir(null), "shots").apply { mkdirs() }
            java.io.File(dir, "v01d-audibility.txt").writeText(
                "volume=$originalVolume->${(maxVolume * 0.7f).toInt()}/$maxVolume\nbaseline=$baseline\nduringSpeech=$duringSpeech\n")

            assertTrue("朗读期间麦克风应采到明显高于底噪的声音：baseline=$baseline during=$duringSpeech",
                duringSpeech > maxOf(baseline * 3, 800))
        } finally {
            audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, originalVolume, 0)
        }
    }

    /**
     * 开麦克风采样，期间执行 [action]，返回采样到的峰值（Short 范围）。
     * 峰值在 IO 协程里累积，action 结束后停止录音。
     */
    private suspend fun kotlinx.coroutines.CoroutineScope.recordPeak(limitMs: Long, action: suspend () -> Unit): Int {
        val peak = java.util.concurrent.atomic.AtomicInteger(0)
        val recording = launch(Dispatchers.IO) {
            val sampleRate = 16_000
            val minBuffer = android.media.AudioRecord.getMinBufferSize(
                sampleRate,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
            )
            val recorder = android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC,
                sampleRate,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2,
            )
            val buffer = ShortArray(minBuffer)
            try {
                recorder.startRecording()
                while (isActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    for (i in 0 until read) {
                        val value = kotlin.math.abs(buffer[i].toInt())
                        if (value > peak.get()) peak.set(value)
                    }
                }
            } finally {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
            }
        }
        val watchdog = launch { delay(limitMs); recording.cancel() }
        action()
        watchdog.cancel()
        recording.cancelAndJoin()
        return peak.get()
    }

    /**
     * V02 内容验证：用 TTS 合成一段 wav 当"人声"，从扬声器放出来让识别器听。
     *
     * 这是探针专用的声学回路测试（不是产品流程：产品里严禁在朗读期间收音）。
     * 如果设备把扬声器输出做了回声消除，这条用例会失败，此时只能靠人工朗读确认。
     */
    @Test
    fun v02c_recognizesPlayedBackFixture() = runBlocking {
        val fixture = synthesizeFixture()
        assertTrue("应能合成测试音频", fixture != null && fixture.length() > 0)

        val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val originalVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (maxVolume * 0.8f).toInt(), 0)

        val events = mutableListOf<SpeechRecognizerEvent>()
        val collector = collect(services.recognizer.events, events)
        val player = android.media.MediaPlayer()
        try {
            services.recognizer.start(RecognitionRequest("v02c", LANGUAGE, partialResults = true, preferOnDevice = false))
            awaitUntil("识别就绪", events) {
                events.any { it is SpeechRecognizerEvent.Ready || it is SpeechRecognizerEvent.Error }
            }
            player.setDataSource(fixture!!.absolutePath)
            player.prepare()
            player.start()
            awaitUntil("识别最终结果", events) {
                events.any { it is SpeechRecognizerEvent.FinalResult || it is SpeechRecognizerEvent.Error }
            }
            delay(500)
            collector.cancel()

            val error = events.filterIsInstance<SpeechRecognizerEvent.Error>().firstOrNull()
            val transcript = events.filterIsInstance<SpeechRecognizerEvent.FinalResult>().firstOrNull()?.transcript.orEmpty()
            // 这台设备的识别是在线服务（端侧不支持），网络不可用时跳过而不是误报失败
            org.junit.Assume.assumeFalse(
                "识别服务需要联网，当前网络不可用：$error",
                error?.error?.code == com.orange.echocards.domain.speech.SpeechErrorCode.NETWORK_ERROR,
            )
            // 音源是 App 自己合成的测试音频，不是用户录音；落盘一份作为验收证据
            val dir = java.io.File(context.getExternalFilesDir(null), "shots").apply { mkdirs() }
            java.io.File(dir, "v02c-transcript.txt").writeText(
                "fixture=$FIXED_TEXT\ntranscript=$transcript\nerror=$error\n")
            assertTrue("应识别出扬声器播放的内容：transcript='$transcript' error=$error", transcript.isNotBlank())
            assertTrue("识别内容应包含卡片开头：'$transcript'", transcript.contains("惯性") || transcript.contains("物体"))
        } finally {
            runCatching { player.release() }
            audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, originalVolume, 0)
        }
    }

    /** 用 TTS 合成固定卡片的 wav，作为声学回路的音源。 */
    private suspend fun synthesizeFixture(): java.io.File? {
        val file = java.io.File(context.cacheDir, "probe-fixture.wav")
        if (file.exists()) file.delete()
        val ready = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val spoken = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val tts = withContext(Dispatchers.Main) {
            android.speech.tts.TextToSpeech(context) { status ->
                ready.complete(status == android.speech.tts.TextToSpeech.SUCCESS)
            }
        }
        val engineReady = kotlinx.coroutines.withTimeoutOrNull(10_000) { ready.await() } ?: false
        if (!engineReady) {
            withContext(Dispatchers.Main) { tts.shutdown() }
            return null
        }
        withContext(Dispatchers.Main) {
            tts.language = java.util.Locale.SIMPLIFIED_CHINESE
            tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { spoken.complete(true) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { spoken.complete(false) }
                override fun onError(utteranceId: String?, errorCode: Int) { spoken.complete(false) }
            })
            tts.synthesizeToFile(FIXED_TEXT, android.os.Bundle(), file, "probe-fixture")
        }
        val ok = kotlinx.coroutines.withTimeoutOrNull(15_000) { spoken.await() } ?: false
        withContext(Dispatchers.Main) { tts.shutdown() }
        return if (ok) file else null
    }

    private fun grantMicrophone() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
    }

    private fun shot(name: String) {
        val dir = java.io.File(context.getExternalFilesDir(null), "shots").apply { mkdirs() }
        android.os.SystemClock.sleep(400)
        androidx.test.uiautomator.UiDevice.getInstance(instrumentation).takeScreenshot(java.io.File(dir, "$name.png"))
    }

    private fun <T> kotlinx.coroutines.CoroutineScope.collect(
        flow: kotlinx.coroutines.flow.Flow<T>,
        into: MutableList<T>,
    ): Job = launch(Dispatchers.Default) { flow.collect { into += it } }

    private suspend fun <T> awaitUntil(what: String, seen: List<T>, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(50)
        }
        throw AssertionError("等待超时（${TIMEOUT_MS}ms）：$what；已收到事件 $seen")
    }

    /** MIUI 拦截后台启动 Activity，这里借 shell 权限把主界面拉到前台。 */
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
        const val LANGUAGE = "zh-CN"
        const val FIXED_TEXT = "惯性是物体保持原有运动状态的性质。"
        const val LONG_TEXT = "惯性是物体保持原有运动状态的性质，质量越大惯性越大，直到有外力改变它为止。"
        const val TIMEOUT_MS = 15_000L
    }
}
