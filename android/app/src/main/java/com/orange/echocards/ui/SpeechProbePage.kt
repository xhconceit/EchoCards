package com.orange.echocards.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.orange.echocards.domain.speech.MicrophonePermission

/**
 * 节点 1 的语音探针页面：固定卡片接通“朗读 → 识别 → 完成”。
 *
 * 只在 debug 构建里从「我的」页进入，用于在目标真机上跑 V01–V05，正式页面不暴露。
 * 识别文字只在这里显示，不落库、不进日志。
 */
@Composable
internal fun SpeechProbePage(onBack: () -> Unit, vm: SpeechProbeViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.onPermissionResult(granted)
    }
    LaunchedEffect(Unit) {
        vm.launchPermissionRequest = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
    }
    // V04：进入后台停麦，回到前台保持暂停
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> vm.onEnterBackground()
                // 回到前台重新读一次能力与权限：系统设置里改过权限时页面不能显示旧状态
                Lifecycle.Event.ON_START -> { vm.onReturnForeground(); vm.refreshCapabilities() }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "语音探针", back = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(8.dp))
            ProbeRow("固定卡片", "惯性是物体保持原有运动状态的性质。")
            ProbeRow("朗读服务", capabilityText(state.capabilities?.synthesisAvailable, "可用", "不可用"))
            ProbeRow("识别服务", capabilityText(state.capabilities?.recognitionAvailable, "可用", "不可用"))
            ProbeRow("端侧识别", capabilityText(state.capabilities?.onDeviceRecognitionAvailable, "支持", "不支持"))
            ProbeRow("麦克风权限", permissionText(state.permission))
            if (state.mediaVolume >= 0) {
                ProbeRow("媒体音量", volumeText(state.mediaVolume, state.mediaVolumeMax))
            }

            if (state.mediaVolume in 0 until (state.mediaVolumeMax / 3).coerceAtLeast(1)) {
                Text("媒体音量偏低（${state.mediaVolume}/${state.mediaVolumeMax}），可能听不清朗读，请按音量键调高",
                    Modifier.padding(vertical = 6.dp), color = Danger, fontSize = 12.sp, lineHeight = 17.sp)
            }

            Spacer(Modifier.height(12.dp))
            ActionButton("一键自检（朗读 → 识别 → 完成）", vm::runSelfCheck, height = 48.dp, icon = EchoIcons.Speak)
            if (state.selfCheck.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(state.selfCheck, Modifier.fillMaxWidth(), color = TealDeep, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(16.dp))
            Text("朗读", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("朗读固定卡片", vm::speakFixedCard, height = 44.dp, modifier = Modifier.weight(1f),
                    icon = EchoIcons.Speak)
                ActionButton("停止朗读", vm::stopSpeaking, primary = false, height = 44.dp, modifier = Modifier.weight(1f),
                    icon = EchoIcons.Stop)
            }

            Spacer(Modifier.height(16.dp))
            Text("识别（朗读完成后再开始）", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("开始识别", vm::startRecognition, height = 44.dp, modifier = Modifier.weight(1f),
                    icon = EchoIcons.Mic)
                ActionButton("结束识别", vm::stopRecognition, primary = false, height = 44.dp, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            ActionButton("取消识别（验证旧回调被忽略）", vm::cancelRecognition, primary = false, height = 44.dp,
                icon = EchoIcons.Close)
            if (state.permission != MicrophonePermission.GRANTED) {
                Spacer(Modifier.height(10.dp))
                ActionButton("请求麦克风权限", vm::requestMicrophonePermission, primary = false, height = 44.dp)
            }

            Spacer(Modifier.height(16.dp))
            Text("SenseVoice 云端识别", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("开始云端识别", vm::startSenseVoiceRecognition, height = 44.dp, modifier = Modifier.weight(1f),
                    icon = EchoIcons.Mic)
                ActionButton("结束", vm::stopSenseVoiceRecognition, primary = false, height = 44.dp, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            ActionButton("取消云端识别", vm::cancelSenseVoiceRecognition, primary = false, height = 44.dp,
                icon = EchoIcons.Close)

            Spacer(Modifier.height(16.dp))
            Text("Vosk 离线识别", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("开始离线识别", vm::startVoskRecognition, height = 44.dp, modifier = Modifier.weight(1f),
                    icon = EchoIcons.Mic)
                ActionButton("结束", vm::stopVoskRecognition, primary = false, height = 44.dp, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            ActionButton("取消离线识别", vm::cancelVoskRecognition, primary = false, height = 44.dp,
                icon = EchoIcons.Close)

            Spacer(Modifier.height(18.dp))
            ProbeRow("朗读状态", state.synthesis)
            ProbeRow("识别状态", state.recognition)
            if (state.transcript.isNotEmpty()) ProbeRow("识别文字（不落库）", state.transcript)
            ProbeRow("云端识别状态", state.senseVoiceRecognition)
            if (state.senseVoiceTranscript.isNotEmpty()) ProbeRow("云端识别文字（不落库）", state.senseVoiceTranscript)
            ProbeRow("离线识别状态", state.voskRecognition)
            if (state.voskTranscript.isNotEmpty()) ProbeRow("离线识别文字（不落库）", state.voskTranscript)
            ProbeRow("被忽略的迟到回调", state.ignoredEvents.toString())

            Spacer(Modifier.height(18.dp))
            Text("事件日志", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.log.forEach { line ->
                    Text(line, color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ProbeRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, Modifier.weight(1f), color = Muted, fontSize = 12.sp)
        Text(value, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

private fun capabilityText(available: Boolean?, yes: String, no: String): String = when (available) {
    null -> "检查中…"
    true -> yes
    false -> no
}

private fun volumeText(volume: Int, max: Int): String =
    if (max <= 0) "未知" else "$volume/$max" + if (volume == 0) "（静音）" else ""

private fun permissionText(permission: MicrophonePermission): String = when (permission) {
    MicrophonePermission.GRANTED -> "已允许"
    MicrophonePermission.DENIED -> "已拒绝（可在系统设置里打开）"
    MicrophonePermission.RESTRICTED -> "被设备策略限制"
    MicrophonePermission.UNDETERMINED -> "尚未请求"
}
