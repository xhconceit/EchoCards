package com.orange.echocards.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orange.echocards.data.CardEntity
import com.orange.echocards.data.DeckEntity
import kotlinx.coroutines.launch

@Composable
internal fun ConfirmDialog(title: String, body: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold) },
        text = { Text(body, fontSize = 14.sp, color = Muted) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("删除", color = Danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = Ink) } },
        shape = RoundedCornerShape(20.dp), containerColor = Surface)
}

@Composable
private fun DiscardDialog(onDismiss: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("放弃修改？", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
        text = { Text("当前内容还没有保存，关闭后将无法恢复。", fontSize = 14.sp, color = Muted) },
        confirmButton = { TextButton(onClick = onDiscard) { Text("放弃", color = Danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = Ink) } },
        shape = RoundedCornerShape(20.dp), containerColor = Surface)
}

@Composable
internal fun ModeDialog(selected: String, onDismiss: () -> Unit, onChoose: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择学习方式", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton("手动学习", { onChoose("manual") }, selected == "manual")
            ActionButton("自动跟读", { onChoose("follow_along") }, selected == "follow_along")
            ActionButton("自动播放", { onChoose("auto_play") }, selected == "auto_play")
        } }, confirmButton = {}, shape = RoundedCornerShape(20.dp), containerColor = Surface)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeckEditor(deck: DeckEntity?, onDismiss: () -> Unit,
    onSave: suspend (String, String) -> Boolean, onDelete: () -> Unit) {
    var title by remember(deck?.id) { mutableStateOf(deck?.title.orEmpty()) }
    var description by remember(deck?.id) { mutableStateOf(deck?.description.orEmpty()) }
    var error by remember { mutableStateOf("") }
    var confirmDiscard by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val changed = title != deck?.title.orEmpty() || description != deck?.description.orEmpty()
    fun requestClose() { if (changed) confirmDiscard = true else onDismiss() }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = ::requestClose, containerColor = Surface, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(if (deck == null) "创建卡组" else "编辑卡组", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(18.dp))
            FormField("卡组名称", title, { title = it; error = "" }, singleLine = true, hint = "例如：初中物理")
            Spacer(Modifier.height(16.dp))
            FormField("卡组说明（可选）", description, { description = it }, hint = "这个卡组学习什么？")
            if (error.isNotEmpty()) Text(error, Modifier.padding(top = 10.dp), fontSize = 12.sp, color = Danger)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("取消", ::requestClose, false, modifier = Modifier.weight(1f))
                if (deck != null) ActionButton("删除", onDelete, false, modifier = Modifier.weight(1f))
                ActionButton("保存", {
                    if (title.isBlank()) error = "请输入卡组名称"
                    else if (!saving) scope.launch { saving = true; if (!onSave(title, description)) saving = false }
                }, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(22.dp))
        }
    }
    if (confirmDiscard) DiscardDialog({ confirmDiscard = false }) { confirmDiscard = false; onDismiss() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CardEditor(card: CardEntity?, onDismiss: () -> Unit,
    onSave: suspend (String, String, String, String) -> Boolean) {
    var title by remember(card?.id) { mutableStateOf(card?.title.orEmpty()) }
    var content by remember(card?.id) { mutableStateOf(card?.content.orEmpty()) }
    var speech by remember(card?.id) { mutableStateOf(card?.speechText.orEmpty()) }
    var tip by remember(card?.id) { mutableStateOf(card?.memoryTip.orEmpty()) }
    var error by remember { mutableStateOf("") }
    var confirmDiscard by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val changed = title != card?.title.orEmpty() || content != card?.content.orEmpty() ||
        speech != card?.speechText.orEmpty() || tip != card?.memoryTip.orEmpty()
    fun requestClose() { if (changed) confirmDiscard = true else onDismiss() }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = ::requestClose, containerColor = Surface, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text(if (card == null) "添加卡片" else "编辑卡片", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(18.dp))
            FormField("标题", title, { title = it; error = "" }, singleLine = true, hint = "例如：惯性")
            Spacer(Modifier.height(16.dp))
            FormField("正文", content, { content = it; error = "" }, hint = "输入知识点正文")
            Spacer(Modifier.height(16.dp))
            FormField("跟读文本（可选）", speech, { speech = it }, hint = "留空时使用正文")
            Spacer(Modifier.height(16.dp))
            FormField("快速记忆点（可选）", tip, { tip = it }, hint = "显示在卡片背面")
            if (error.isNotEmpty()) Text(error, Modifier.padding(top = 10.dp), fontSize = 12.sp, color = Danger)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("取消", ::requestClose, false, modifier = Modifier.weight(1f))
                ActionButton("保存", {
                    error = when { title.isBlank() -> "请输入标题"; content.isBlank() -> "请输入正文"; else -> "" }
                    if (error.isEmpty() && !saving) scope.launch { saving = true; if (!onSave(title, content, speech, tip)) saving = false }
                }, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(22.dp))
        }
    }
    if (confirmDiscard) DiscardDialog({ confirmDiscard = false }) { confirmDiscard = false; onDismiss() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImportDialog(preview: ImportPreview, onDismiss: () -> Unit, onConfirm: suspend (String) -> Boolean) {
    var title by remember(preview.deck.fingerprint) { mutableStateOf(preview.deck.title) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Surface, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("导入卡组", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(16.dp))
            Text("${preview.fileName} · ${preview.deck.cards.size} 张卡片", color = Muted, fontSize = 14.sp)
            Spacer(Modifier.height(16.dp))
            FormField("卡组名称", title, { title = it; error = "" }, singleLine = true)
            if (preview.duplicateId != null) Text("已有相同内容的卡组，继续将打开已有卡组。",
                Modifier.padding(top = 12.dp), color = Color(0xFF8A6114), fontSize = 13.sp)
            else if (preview.sameName) Text("已有同名卡组；内容不同，导入后会新增一个卡组。",
                Modifier.padding(top = 12.dp), color = Color(0xFF8A6114), fontSize = 13.sp)
            if (error.isNotEmpty()) Text(error, Modifier.padding(top = 10.dp), color = Danger, fontSize = 12.sp)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("取消", onDismiss, false, modifier = Modifier.weight(1f))
                ActionButton(if (preview.duplicateId != null) "查看已有卡组" else "导入", {
                    if (title.isBlank()) error = "请输入卡组名称" else scope.launch { onConfirm(title) }
                }, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(22.dp))
        }
    }
}
