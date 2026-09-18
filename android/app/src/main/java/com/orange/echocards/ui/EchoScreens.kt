package com.orange.echocards.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orange.echocards.data.CardEntity
import com.orange.echocards.data.DeckEntity
import com.orange.echocards.data.DeckProgressEntity
import com.orange.echocards.data.DeckSummary

@Composable
internal fun HomePage(deckSummaries: List<DeckSummary>, loadError: String?, onRetry: () -> Unit,
    query: String, onQuery: (String) -> Unit,
    onOpen: (DeckSummary) -> Unit, onCreate: () -> Unit, onImport: () -> Unit, onMine: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "卡组")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(13.dp)).background(Surface)
                .border(1.dp, Border, RoundedCornerShape(13.dp)).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("⌕", fontSize = 25.sp, color = Muted)
                Spacer(Modifier.width(9.dp))
                BasicTextField(query, onQuery, singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp, color = Ink), modifier = Modifier.weight(1f),
                    decorationBox = { inner -> Box { if (query.isEmpty()) Text("搜索卡组", fontSize = 14.sp, color = Muted); inner() } })
            }
            if (loadError != null) {
                Spacer(Modifier.height(20.dp))
                Text(loadError, Modifier.fillMaxWidth(), color = Danger, fontSize = 13.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                ActionButton("重试", onRetry, primary = false)
            } else {
                val visible = deckSummaries.filter { it.title.contains(query.trim(), ignoreCase = true) }
                if (visible.isNotEmpty()) Spacer(Modifier.height(18.dp))
                visible.forEach { summary ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp).height(72.dp)
                        .clip(RoundedCornerShape(16.dp)).background(Surface)
                        .border(1.dp, Border, RoundedCornerShape(16.dp)).clickable { onOpen(summary) }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        MiniStack()
                        Spacer(Modifier.width(15.dp))
                        Column {
                            Text(summary.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(3.dp))
                            Text(deckMeta(summary), fontSize = 12.sp, color = Muted)
                        }
                    }
                }
                if (visible.isEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    Text(if (deckSummaries.isEmpty()) "还没有卡组，创建第一个卡组" else "没有找到匹配的卡组",
                        Modifier.fillMaxWidth().padding(16.dp), color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(18.dp))
                ActionButton("＋ 创建卡组", onCreate, primary = false)
                Spacer(Modifier.height(10.dp))
                ActionButton("↑ 导入卡组（JSON）", onImport, primary = false)
            }
        }
        BottomTabs(home = true, onHome = {}, onMine = onMine)
    }
}

private fun deckMeta(summary: DeckSummary): String =
    if (summary.cardCount == 0) "尚未开始"
    else "${summary.cardCount} 张卡片 · 上次学到第 ${(summary.currentPosition ?: 0) + 1} 张"

@Composable
internal fun MinePage(mode: String, rate: Double, delay: Long,
    onChange: (String, Double, Long) -> Unit, onHome: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "我的")
        Column(Modifier.weight(1f).padding(horizontal = 20.dp)) {
            Text("学习设置", Modifier.padding(top = 26.dp, bottom = 12.dp), color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            SettingRow("默认学习模式", modeName(mode)) {
                onChange(when (mode) { "manual" -> "follow_along"; "follow_along" -> "auto_play"; else -> "manual" }, rate, delay)
            }
            SettingRow("朗读速度", "${rate}×") {
                onChange(mode, when (rate) { 0.8 -> 1.0; 1.0 -> 1.2; 1.2 -> 1.5; else -> 0.8 }, delay)
            }
            SettingRow("自动翻页延迟", if (delay == 0L) "立即" else "${delay / 1000.0} 秒") {
                onChange(mode, rate, when (delay) { 0L -> 600L; 600L -> 1000L; 1000L -> 2000L; else -> 0L })
            }
        }
        BottomTabs(home = false, onHome = onHome, onMine = {})
    }
}

@Composable
private fun SettingRow(label: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(60.dp).border(0.5.dp, Border).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = Ink, fontSize = 16.sp)
        Text(value, color = Muted, fontSize = 13.sp)
    }
}

@Composable
internal fun DetailPage(deck: DeckEntity, cards: List<CardEntity>, progress: DeckProgressEntity?,
    onBack: () -> Unit, onEditDeck: () -> Unit, onStart: () -> Unit, onAddCard: () -> Unit,
    onEditCard: (CardEntity) -> Unit, onDeleteCard: (CardEntity) -> Unit, onMoveCard: (String, Int) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column {
            TopBar(back = onBack, trailing = onEditDeck)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(12.dp))
                Text(deck.title, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.height(7.dp))
                val current = cards.indexOfFirst { it.id == progress?.currentCardId }.let { if (it < 0) 0 else it }
                Text("${cards.size} 张卡片 · ${if (cards.isEmpty()) "尚未开始" else "上次学到第 ${current + 1} 张"}",
                    fontSize = 13.sp, color = Muted)
                if (!deck.description.isNullOrBlank()) Text(deck.description, Modifier.padding(top = 7.dp), fontSize = 13.sp, color = Muted)
                Spacer(Modifier.height(20.dp))
                ActionButton(if (cards.isEmpty()) "添加第一张卡片" else "开始学习", onStart)
                Spacer(Modifier.height(22.dp))
                Text("卡片", fontSize = 13.sp, color = Ink, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                if (cards.isEmpty()) Text("这个卡组还没有知识点。先添加第一张卡片。",
                    Modifier.fillMaxWidth().padding(16.dp), fontSize = 13.sp, color = Muted, textAlign = TextAlign.Center)
                cards.forEach { card -> CardListRow(card, onEditCard, onDeleteCard, onMoveCard) }
            }
        }
        if (cards.isNotEmpty()) Box(Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 28.dp)
            .size(56.dp).clip(RoundedCornerShape(18.dp)).background(TealDeep).clickable(onClick = onAddCard),
            contentAlignment = Alignment.Center) { Text("+", fontSize = 30.sp, color = Surface) }
    }
}

@Composable
private fun CardListRow(card: CardEntity, onEdit: (CardEntity) -> Unit,
    onDelete: (CardEntity) -> Unit, onMove: (String, Int) -> Unit) {
    val density = LocalDensity.current
    var revealed by remember(card.id) { mutableStateOf(false) }
    var dragging by remember(card.id) { mutableStateOf(false) }
    var horizontalDrag by remember(card.id) { mutableStateOf(0f) }
    var verticalDrag by remember(card.id) { mutableStateOf(0f) }
    val target = with(density) { if (revealed) -76.dp.toPx() else 0f }
    val animated by animateFloatAsState(if (dragging) horizontalDrag else target, tween(180), label = "card row")
    val threshold = with(density) { 38.dp.toPx() }
    val rowHeight = with(density) { 60.dp.toPx() }
    Box(Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(0.dp))) {
        Box(Modifier.align(Alignment.CenterEnd).width(76.dp).height(60.dp).background(Danger)
            .clickable { onDelete(card) }, contentAlignment = Alignment.Center) {
            Text("删除", color = Surface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(Modifier.fillMaxSize().graphicsLayer { translationX = animated }.background(Bg)
            .border(width = 0.5.dp, color = Border)
            .pointerInput(card.id, revealed) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true; horizontalDrag = if (revealed) -76.dp.toPx() else 0f },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        horizontalDrag = (horizontalDrag + amount).coerceIn(-88.dp.toPx(), 0f)
                    },
                    onDragEnd = { revealed = horizontalDrag < -threshold; dragging = false },
                    onDragCancel = { dragging = false },
                )
            }
            .pointerInput(card.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { verticalDrag = 0f },
                    onDrag = { change, amount ->
                        change.consume()
                        verticalDrag += amount.y
                        if (verticalDrag > rowHeight * .55f) { onMove(card.id, 1); verticalDrag = 0f }
                        else if (verticalDrag < -rowHeight * .55f) { onMove(card.id, -1); verticalDrag = 0f }
                    }, onDragEnd = { verticalDrag = 0f }, onDragCancel = { verticalDrag = 0f },
                )
            }
            .clickable { if (revealed) revealed = false else onEdit(card) }
            .padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${card.title} · ${card.content}", Modifier.weight(1f), fontSize = 15.sp, color = Ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun StudyPage(cards: List<CardEntity>, progress: DeckProgressEntity?, mode: String, choosingMode: Boolean,
    onMode: () -> Unit, onBack: () -> Unit, onPosition: (CardEntity) -> Unit) {
    val density = LocalDensity.current.density
    var index by remember { mutableIntStateOf(0) }
    var initialized by remember { mutableStateOf(false) }
    var back by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var speaking by remember { mutableStateOf(false) }
    var dragX by remember { mutableStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    LaunchedEffect(cards, progress?.currentCardId) {
        if (!initialized && cards.isNotEmpty()) {
            index = cards.indexOfFirst { it.id == progress?.currentCardId }.let { if (it < 0) 0 else it }
            initialized = true
        }
    }
    if (cards.isEmpty()) return
    index = index.coerceIn(0, cards.lastIndex)
    val card = cards[index]
    fun step(delta: Int) {
        index = (index + delta + cards.size) % cards.size
        back = false
        onPosition(cards[index])
    }
    LaunchedEffect(mode, paused, index, choosingMode) {
        if (mode == "manual" || paused || choosingMode) return@LaunchedEffect
        back = false
        speaking = true
        kotlinx.coroutines.delay(1450)
        if (paused) return@LaunchedEffect
        speaking = false
        if (mode == "follow_along") kotlinx.coroutines.delay(900)
        back = true
        kotlinx.coroutines.delay(600)
        if (!paused) step(1)
    }
    val motionX by animateFloatAsState(if (isDragging) dragX else 0f, tween(300), label = "study swipe")
    val flip by animateFloatAsState(if (back) 180f else 0f, tween(420), label = "card flip")
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("×", Modifier.size(48.dp).clickable(onClick = onBack).padding(top = 5.dp),
                fontSize = 30.sp, color = Ink, textAlign = TextAlign.Center)
            Text(modeName(mode) + "  ⇄", Modifier.clickable(onClick = onMode).padding(6.dp),
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Spacer(Modifier.weight(1f))
            Text("${(index + 1).toString().padStart(2, '0')} / ${cards.size}",
                Modifier.padding(end = 12.dp), fontSize = 12.sp, color = Muted, letterSpacing = 2.sp)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 26.dp), contentAlignment = Alignment.Center) {
            val cardHeight = 400.dp.coerceAtMost(maxHeight - 100.dp)
            Box(Modifier.fillMaxWidth().height(cardHeight)) {
                Box(Modifier.align(Alignment.BottomEnd).offset(x = (-6).dp, y = (-6).dp)
                    .size(110.dp).rotate(-45f).clip(RoundedCornerShape(14.dp)).background(FieldBg)
                    .border(1.dp, Border, RoundedCornerShape(14.dp)))
                Box(Modifier.fillMaxSize().graphicsLayer {
                    translationX = motionX
                    rotationZ = (motionX * .028f).coerceIn(-9f, 9f)
                    alpha = (1f - kotlin.math.abs(motionX) / 520f).coerceAtLeast(.4f)
                }.clip(RoundedCornerShape(22.dp)).background(Surface)
                    .border(1.dp, Border, RoundedCornerShape(22.dp))
                    .pointerInput(cards.size) {
                        var drag = 0f
                        detectHorizontalDragGestures(onDragStart = { isDragging = true; dragX = 0f }, onHorizontalDrag = { change, amount ->
                            drag += amount; dragX = drag
                            change.consume()
                        }, onDragEnd = {
                            if (drag < -60f) step(1) else if (drag > 60f) step(-1)
                            drag = 0f; dragX = 0f; isDragging = false
                        }, onDragCancel = { drag = 0f; dragX = 0f; isDragging = false })
                    }.clickable { if (!isDragging) back = !back }, contentAlignment = Alignment.Center) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp).padding(bottom = 54.dp)
                        .graphicsLayer { rotationY = if (flip <= 90f) flip else flip - 180f; cameraDistance = 14f * density },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val showingBack = flip > 90f
                        Text(if (showingBack) "快速记忆点" else card.title, fontSize = if (showingBack) 24.sp else 30.sp,
                            fontWeight = FontWeight.Bold, color = Ink, textAlign = TextAlign.Center, lineHeight = 36.sp)
                        Text(if (showingBack) card.memoryTip?.ifBlank { "暂无快速记忆点" } ?: "暂无快速记忆点" else card.content,
                            fontSize = 17.sp, lineHeight = 28.sp, color = Muted, textAlign = TextAlign.Center)
                    }
                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).size(48.dp)
                        .clip(CircleShape).background(if (mode == "manual") Surface else Color(0xFFEFF6F6))
                        .border(1.dp, Color(0xFFD9DEDE), CircleShape), contentAlignment = Alignment.Center) {
                        Text(if (speaking) "▥" else "◖))", color = if (mode == "manual") Ink else TealDeep, fontSize = 15.sp)
                    }
                }
            }
        }
        if (mode != "manual") Text(if (paused) "已暂停" else if (speaking) "正在朗读" else if (mode == "follow_along") "轮到你读了" else "",
            Modifier.fillMaxWidth().padding(bottom = 32.dp), color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 34.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(if (paused) "继续" else "暂停", { paused = !paused }, false, 44.dp, Modifier.weight(1f))
            ActionButton("重读", { back = false }, false, 44.dp, Modifier.weight(1f))
            ActionButton("速度 1.0×", {}, false, 44.dp, Modifier.weight(1f))
        }
    }
}
