package com.orange.echocards.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orange.echocards.data.CardEntity
import com.orange.echocards.domain.learning.CardFace
import com.orange.echocards.domain.learning.LearningEngineState
import com.orange.echocards.domain.learning.LearningMode
import com.orange.echocards.domain.learning.LearningPhase
import com.orange.echocards.data.DeckEntity
import com.orange.echocards.data.DeckProgressEntity
import com.orange.echocards.data.DeckSummary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun HomePage(deckSummaries: List<DeckSummary>, loadError: String?, onRetry: () -> Unit,
    query: String, onQuery: (String) -> Unit,
    onOpen: (DeckSummary) -> Unit, onCreate: () -> Unit, onImport: () -> Unit, onMine: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        // 创建 / 导入入口放在标题栏右上角，中间留给卡组列表和空状态
        TopBar(title = "卡组") {
            TopBarAction(EchoIcons.Add, "创建卡组", onCreate)
            TopBarAction(EchoIcons.Import, "导入卡组", onImport)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(13.dp)).background(Surface)
                .border(1.dp, Border, RoundedCornerShape(13.dp)).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(EchoIcons.Search), contentDescription = null,
                    tint = Muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(9.dp))
                BasicTextField(query, onQuery, singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp, color = Ink), modifier = Modifier.weight(1f),
                    decorationBox = { inner -> Box { if (query.isEmpty()) Text("搜索卡组", fontSize = 14.sp, color = Muted); inner() } })
            }
            if (loadError != null) {
                Spacer(Modifier.height(20.dp))
                Text(loadError, Modifier.fillMaxWidth(), color = Danger, fontSize = 13.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                ActionButton("重试", onRetry, primary = false, icon = EchoIcons.Retry)
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
                    Text(if (deckSummaries.isEmpty()) "还没有卡组，点右上角 ＋ 创建第一个卡组" else "没有找到匹配的卡组",
                        Modifier.fillMaxWidth().padding(16.dp), color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(18.dp))
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
    onChange: (String, Double, Long) -> Unit, onHome: () -> Unit, onProbe: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "我的")
        Column(Modifier.weight(1f).padding(horizontal = 20.dp)) {
            Text("学习设置", Modifier.padding(top = 22.dp, bottom = 12.dp), color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            SettingRow("默认学习模式", modeName(mode)) {
                onChange(when (mode) { "manual" -> "follow_along"; "follow_along" -> "auto_play"; else -> "manual" }, rate, delay)
            }
            RowDivider()
            SettingRow("朗读速度", "${rate}×") {
                onChange(mode, when (rate) { 0.8 -> 1.0; 1.0 -> 1.2; 1.2 -> 1.5; else -> 0.8 }, delay)
            }
            RowDivider()
            SettingRow("自动翻页延迟", if (delay == 0L) "立即" else "${delay / 1000.0} 秒") {
                onChange(mode, rate, when (delay) { 0L -> 600L; 600L -> 1000L; 1000L -> 2000L; else -> 0L })
            }
            if (onProbe != null) {
                Spacer(Modifier.height(28.dp))
                Text("调试", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                SettingRow("语音探针", "朗读 → 识别", onProbe)
            }
        }
        BottomTabs(home = false, onHome = onHome, onMine = {})
    }
}

/** 列表行只画下分割线，最后一行不画（与原型 .crow 一致，不画外框和竖线）。 */
@Composable
internal fun RowDivider() {
    HorizontalDivider(thickness = 0.5.dp, color = Border)
}

@Composable
private fun SettingRow(label: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(60.dp).clickable(onClick = onClick),
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
            TopBar(back = onBack) { TopBarAction(EchoIcons.Edit, "编辑", onEditDeck) }
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
                cards.forEachIndexed { i, card ->
                    CardListRow(card, onEditCard, onDeleteCard, onMoveCard)
                    if (i != cards.lastIndex) RowDivider()
                }
            }
        }
        if (cards.isNotEmpty()) Box(Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 28.dp)
            .size(56.dp).clip(RoundedCornerShape(18.dp)).background(TealDeep).clickable(onClick = onAddCard),
            contentAlignment = Alignment.Center) {
            Icon(painterResource(EchoIcons.Add), contentDescription = "添加卡片",
                tint = Surface, modifier = Modifier.size(24.dp))
        }
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
    var dragAxis by remember(card.id) { mutableStateOf<String?>(null) }
    val target = with(density) { if (revealed) -76.dp.toPx() else 0f }
    val animated by animateFloatAsState(if (dragging) horizontalDrag else target, tween(180), label = "card row")
    val threshold = with(density) { 38.dp.toPx() }
    val rowHeight = with(density) { 60.dp.toPx() }
    Box(Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(0.dp))) {
        Box(Modifier.align(Alignment.CenterEnd).width(76.dp).height(60.dp).background(Danger)
            .clickable { onDelete(card) }, contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(painterResource(EchoIcons.Delete), contentDescription = null,
                    tint = Surface, modifier = Modifier.size(16.dp))
                Text("删除", color = Surface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Row(Modifier.fillMaxSize().graphicsLayer { translationX = animated }.background(Bg)
            .pointerInput(card.id, revealed) {
                detectDragGestures(
                    onDragStart = {
                        dragging = true
                        dragAxis = null
                        horizontalDrag = if (revealed) -76.dp.toPx() else 0f
                        verticalDrag = 0f
                    },
                    onDrag = { change, amount ->
                        if (dragAxis == null && (kotlin.math.abs(amount.x) > 1f || kotlin.math.abs(amount.y) > 1f)) {
                            dragAxis = if (kotlin.math.abs(amount.x) > kotlin.math.abs(amount.y) * 1.2f) "horizontal" else "vertical"
                        }
                        when (dragAxis) {
                            "horizontal" -> horizontalDrag = (horizontalDrag + amount.x).coerceIn(-88.dp.toPx(), 0f)
                            "vertical" -> {
                                if (revealed) revealed = false
                                verticalDrag += amount.y
                                if (verticalDrag > rowHeight * .55f) { onMove(card.id, 1); verticalDrag = 0f }
                                else if (verticalDrag < -rowHeight * .55f) { onMove(card.id, -1); verticalDrag = 0f }
                            }
                        }
                        change.consume()
                    },
                    onDragEnd = {
                        if (dragAxis == "horizontal") revealed = horizontalDrag < -threshold
                        dragging = false
                        dragAxis = null
                        verticalDrag = 0f
                    },
                    onDragCancel = {
                        dragging = false
                        dragAxis = null
                        verticalDrag = 0f
                    },
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
internal fun StudyPage(
    state: LearningEngineState,
    onMode: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onFlip: () -> Unit,
    onToggleSpeech: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onReplay: () -> Unit,
    onRetry: () -> Unit,
    onManual: () -> Unit,
    onOpenSettings: () -> Unit,
    onSpeed: () -> Unit,
    onEnterBackground: () -> Unit,
    onReturnForeground: () -> Unit,
) {
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val slide = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val offset = remember { Animatable(0f) }
    val flip = remember { Animatable(0f) }
    val pageFocusRequester = remember { FocusRequester() }
    var dragX by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf(false) }

    val cards = state.cards
    val index = state.currentIndex
    val card = state.currentCard ?: return
    val showingBack = state.cardFace == CardFace.BACK
    val paused = state.phase == LearningPhase.PAUSED
    val speaking = state.phase == LearningPhase.SPEAKING

    LaunchedEffect(showingBack) { flip.animateTo(if (showingBack) 180f else 0f, tween(420)) }
    LaunchedEffect(Unit) { pageFocusRequester.requestFocus() }
    // 新卡片已经生效后允许下一次键盘／无障碍切卡；离场动画期间仍保留守卫避免重复推进。
    LaunchedEffect(index) { switching = false }
    // 进入后台停止朗读与识别并保存位置，回前台保持暂停（learning-engine.md 第 10 节）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> onEnterBackground()
                Lifecycle.Event.ON_START -> onReturnForeground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /** 切卡：先把当前卡片滑出屏幕，等引擎换完内容再从另一侧滑入。 */
    fun switchCard(delta: Int, from: Float) {
        // 只有“保存位置失败”才拦住切卡（M05）；朗读失败仍允许换一张继续
        if (switching || cards.size < 2 || state.error?.code == "SAVE_FAILED") return
        switching = true
        val previousIndex = index
        scope.launch {
            offset.snapTo(from)
            offset.animateTo(-slide * delta, tween(180, easing = FastOutLinearInEasing))
            if (delta > 0) onNext() else onPrevious()
            var waited = 0
            while (index == previousIndex && waited < 500) {
                delay(16)
                waited += 16
            }
            offset.snapTo(slide * delta)
            offset.animateTo(0f, tween(220, easing = LinearOutSlowInEasing))
            switching = false
        }
    }

    fun handleDirectionalKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        return when (event.key) {
            Key.DirectionRight -> { switchCard(1, 0f); true }
            Key.DirectionLeft -> { switchCard(-1, 0f); true }
            else -> false
        }
    }

    val translation = if (isDragging) dragX else offset.value
    Column(
        Modifier.fillMaxSize()
            .focusRequester(pageFocusRequester)
            .focusable()
            .testTag(STUDY_PAGE_TAG)
            .onPreviewKeyEvent(::handleDirectionalKey),
    ) {
        Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(painterResource(EchoIcons.Close), contentDescription = "退出学习",
                    tint = Ink, modifier = Modifier.size(22.dp))
            }
            Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onMode).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(modeName(state.mode.toUiMode()), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                Icon(painterResource(EchoIcons.SwitchMode), contentDescription = "切换学习方式",
                    tint = Muted, modifier = Modifier.size(16.dp))
            }
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
                    translationX = translation
                    rotationZ = (translation * .028f).coerceIn(-9f, 9f)
                    rotationY = flip.value
                    cameraDistance = 14f * density
                    alpha = (1f - kotlin.math.abs(translation) / 520f).coerceAtLeast(.4f)
                }.clip(RoundedCornerShape(22.dp)).background(Surface)
                    .border(1.dp, Border, RoundedCornerShape(22.dp))
                    .pointerInput(cards.size) {
                        var drag = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { if (!switching) { isDragging = true; drag = 0f; dragX = 0f } },
                            onHorizontalDrag = { change, amount ->
                                if (isDragging) { drag += amount; dragX = drag; change.consume() }
                            },
                            onDragEnd = {
                                if (!isDragging) return@detectHorizontalDragGestures
                                val released = dragX
                                isDragging = false
                                dragX = 0f
                                when {
                                    released < -60f -> switchCard(1, released)
                                    released > 60f -> switchCard(-1, released)
                                    else -> scope.launch {
                                        offset.snapTo(released)
                                        offset.animateTo(0f, tween(200, easing = LinearOutSlowInEasing))
                                    }
                                }
                            },
                            onDragCancel = {
                                if (isDragging) {
                                    val released = dragX
                                    isDragging = false
                                    dragX = 0f
                                    scope.launch {
                                        offset.snapTo(released)
                                        offset.animateTo(0f, tween(200, easing = LinearOutSlowInEasing))
                                    }
                                }
                            },
                        )
                    }.clickable { if (!isDragging) onFlip() }
                    .focusable()
                    .testTag(STUDY_CARD_TAG)
                    // 屏幕阅读器在卡片上提供切卡操作，键盘用左右方向键，见 screens.md 第 12 节
                    .semantics {
                        contentDescription = if (showingBack) {
                            "快速记忆点：${card.memoryTip?.ifBlank { "暂无快速记忆点" } ?: "暂无快速记忆点"}"
                        } else {
                            "卡片：${card.title}。${card.content}"
                        }
                        stateDescription = if (showingBack) "背面，点击翻回正面" else "正面，点击查看快速记忆点"
                        customActions = listOf(
                            CustomAccessibilityAction("上一张") { switchCard(-1, 0f); true },
                            CustomAccessibilityAction("下一张") { switchCard(1, 0f); true },
                        )
                    }
                    , contentAlignment = Alignment.Center) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp).padding(bottom = if (showingBack) 0.dp else 54.dp)
                        .graphicsLayer { if (showingBack) rotationY = 180f },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (showingBack) "快速记忆点" else card.title, fontSize = if (showingBack) 24.sp else 30.sp,
                            fontWeight = FontWeight.Bold, color = Ink, textAlign = TextAlign.Center, lineHeight = 36.sp)
                        Text(if (showingBack) card.memoryTip?.ifBlank { "暂无快速记忆点" } ?: "暂无快速记忆点" else card.content,
                            fontSize = 17.sp, lineHeight = 28.sp, color = Muted, textAlign = TextAlign.Center)
                    }
                    if (!showingBack && state.mode == LearningMode.MANUAL) {
                        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).size(48.dp)
                            .graphicsLayer { rotationY = 0f }
                            .clip(CircleShape).background(Surface)
                            .border(1.dp, Color(0xFFD9DEDE), CircleShape)
                            .clickable(onClick = onToggleSpeech), contentAlignment = Alignment.Center) {
                            Icon(painterResource(if (speaking) EchoIcons.Stop else EchoIcons.Speak),
                                contentDescription = if (speaking) "暂停朗读" else "朗读", tint = Ink,
                                modifier = Modifier.size(20.dp).offset(x = if (speaking) 0.dp else 1.dp))
                        }
                    }
                }
            }
        }
        // 界面只给状态文案：覆盖率与识别文本都不展示（speech-matching.md 第 16 节）
        val status = when {
            state.error != null -> state.error.message
            state.mode == LearningMode.MANUAL -> ""
            paused -> "学习已暂停"
            state.phase == LearningPhase.SHOWING_MEMORY_TIP -> "快速记忆点"
            speaking -> "正在朗读，请先听一遍"
            state.phase == LearningPhase.LISTENING || state.phase == LearningPhase.EVALUATING -> "轮到你读了"
            else -> ""
        }
        if (status.isNotEmpty()) Text(status, Modifier.fillMaxWidth().padding(bottom = if (state.error != null) 12.dp else 24.dp),
            color = if (state.error != null) Danger else Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
        val error = state.error
        // 暂停时不显示操作行：暂停状态下的重试语义不明确，先让用户决定继续还是退出
        if (error != null && !paused) {
            // 权限、语音服务和保存失败都要有明确的恢复入口（learning-engine.md 第 11 节）
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (error.recoverable) ActionButton("重试", onRetry, true, 44.dp, Modifier.weight(1f))
                if (error.code == "PERMISSION_DENIED") {
                    ActionButton("系统设置", onOpenSettings, false, 44.dp, Modifier.weight(1f))
                }
                if (state.mode != LearningMode.MANUAL) {
                    ActionButton("切换手动学习", onManual, false, 44.dp, Modifier.weight(1f))
                }
            }
        }
        if (state.mode != LearningMode.MANUAL) Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 34.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(if (paused) "继续" else "暂停", { if (paused) onResume() else onPause() }, false, 44.dp,
                Modifier.weight(1f))
            if (state.phase != LearningPhase.SHOWING_MEMORY_TIP) {
                ActionButton("重读", onReplay, false, 44.dp, Modifier.weight(1f))
            }
            ActionButton("速度 ${state.speechRate}×", onSpeed, false, 44.dp, Modifier.weight(1f))
        }
    }
}

/** 学习页卡片的测试标签：设备测试用它定位可聚焦的卡片节点，验证无障碍切卡。 */
internal const val STUDY_CARD_TAG = "study-card"
internal const val STUDY_PAGE_TAG = "study-page"
