package com.orange.echocards.ui

import android.content.pm.ApplicationInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.orange.echocards.data.CardEntity
import com.orange.echocards.data.DeckEntity
import kotlinx.coroutines.launch

/**
 * 页面栈。深度用来判断切换方向：进入更深的页面从右侧滑入，返回时反向。
 * 底部导航之间（首页 / 我的）不做位移动画，只淡入淡出。
 */
private enum class Page(val depth: Int, val tab: Boolean = false) {
    HOME(0, tab = true),
    MINE(0, tab = true),
    DETAIL(1),
    STUDY(2),
    PROBE(2),
}

@Composable
fun EchoApp(vm: EchoViewModel = viewModel()) {
    val deckSummaries by vm.deckSummaries.collectAsState()
    val loadError by vm.loadError.collectAsState()
    // 语音探针只在 debug 构建里露出
    val debuggable = (LocalContext.current.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    val selectedDeck by vm.selectedDeck.collectAsState()
    val cards by vm.cards.collectAsState()
    val progress by vm.progress.collectAsState()
    val settings by vm.settings.collectAsState()
    val importPreview by vm.importPreview.collectAsState()
    val message by vm.message.collectAsState()
    val studyVm: StudyViewModel = viewModel()
    val studyState by studyVm.state.collectAsState()
    var studySession by remember { mutableIntStateOf(0) }
    val microphoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        studyVm.onPermissionResult(granted)
    }
    LaunchedEffect(Unit) {
        studyVm.launchPermissionRequest = { microphoneLauncher.launch(android.Manifest.permission.RECORD_AUDIO) }
    }
    var page by rememberSaveable { mutableStateOf(Page.HOME) }
    var deckEditor by remember { mutableStateOf<DeckEntity?>(null) }
    var showDeckEditor by remember { mutableStateOf(false) }
    var cardEditor by remember { mutableStateOf<CardEntity?>(null) }
    var showCardEditor by remember { mutableStateOf(false) }
    var deckToDelete by remember { mutableStateOf<DeckEntity?>(null) }
    var cardToDelete by remember { mutableStateOf<CardEntity?>(null) }
    var showMode by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf("manual") }
    var query by rememberSaveable { mutableStateOf("") }
    // 每次进入学习页初始化一次引擎（模式、语速、停留时长与起始卡片）
    LaunchedEffect(studySession) {
        val deck = selectedDeck ?: return@LaunchedEffect
        if (studySession == 0) return@LaunchedEffect
        studyVm.open(deck.id, mode.toLearningMode(), settings?.speechRate ?: 1.0,
            settings?.autoAdvanceDelayMs ?: 600L, progress?.currentCardId)
    }

    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::readImport)
    }

    BackHandler(page == Page.STUDY || page == Page.DETAIL || page == Page.PROBE) {
        page = when (page) {
            Page.STUDY -> Page.DETAIL
            Page.PROBE -> Page.MINE
            else -> Page.HOME
        }
    }

    Box(Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    if (initialState.tab && targetState.tab) {
                        // 底部导航互切：只淡入淡出，不做位移
                        fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                    } else {
                        val forward = targetState.depth > initialState.depth
                        val enter = slideInHorizontally(tween(280)) { width -> if (forward) width else -width }
                        val exit = slideOutHorizontally(tween(280)) { width -> if (forward) -width / 3 else width / 3 }
                        (enter + fadeIn(tween(200))) togetherWith (exit + fadeOut(tween(160)))
                    }
                },
                modifier = Modifier.fillMaxSize(),
                label = "page",
            ) { current ->
                when (current) {
                    Page.HOME -> HomePage(deckSummaries, loadError, vm::retryDeckLoad, query, { query = it },
                        onOpen = { vm.openDeck(it.deckId); page = Page.DETAIL },
                        onCreate = { deckEditor = null; showDeckEditor = true },
                        onImport = { picker.launch(arrayOf("application/json", "text/plain", "*/*")) },
                        onMine = { page = Page.MINE })
                    Page.MINE -> MinePage(settings?.defaultMode ?: "manual", settings?.speechRate ?: 1.0,
                        settings?.autoAdvanceDelayMs ?: 600L,
                        onChange = { newMode, newRate, newDelay -> vm.saveSettings(newMode, newRate, newDelay) },
                        onHome = { page = Page.HOME },
                        onProbe = if (debuggable) ({ page = Page.PROBE }) else null)
                    Page.DETAIL -> if (selectedDeck != null) DetailPage(selectedDeck!!, cards, progress,
                        onBack = { page = Page.HOME },
                        onEditDeck = { deckEditor = selectedDeck; showDeckEditor = true },
                        onStart = { if (cards.isEmpty()) { cardEditor = null; showCardEditor = true } else {
                            mode = settings?.defaultMode ?: "manual"; showMode = true
                        } },
                        onAddCard = { cardEditor = null; showCardEditor = true },
                        onEditCard = { cardEditor = it; showCardEditor = true },
                        onDeleteCard = { cardToDelete = it }, onMoveCard = vm::moveCard)
                    Page.STUDY -> StudyPage(
                        state = studyState,
                        choosingMode = showMode,
                        onMode = { showMode = true },
                        onBack = { studyVm.close(); page = Page.DETAIL },
                        onNext = studyVm::next,
                        onPrevious = studyVm::previous,
                        onFlip = studyVm::flip,
                        onToggleSpeech = studyVm::toggleSpeech,
                        onPause = studyVm::pause,
                        onResume = studyVm::resume,
                        onReplay = studyVm::replay,
                        onEnterBackground = studyVm::onEnterBackground,
                        onReturnForeground = studyVm::onReturnForeground,
                    )
                    Page.PROBE -> SpeechProbePage(onBack = { page = Page.MINE })
                }
            }
        }
        if (message != null) {
            Box(Modifier.align(Alignment.BottomCenter).padding(20.dp).clip(RoundedCornerShape(12.dp))
                .background(Ink).clickable { vm.clearMessage() }.padding(14.dp)) {
                Text(message!!, color = Surface, fontSize = 13.sp)
            }
        }
    }

    if (showDeckEditor) DeckEditor(deckEditor, onDismiss = { showDeckEditor = false },
        onSave = { title, description ->
            val ok = if (deckEditor == null) vm.createDeck(title, description)
                else vm.updateDeck(deckEditor!!.id, title, description)
            if (ok) {
                showDeckEditor = false
                if (deckEditor == null) page = Page.DETAIL
            }
            ok
        }, onDelete = { deckEditor?.let { deckToDelete = it } })

    if (showCardEditor) CardEditor(cardEditor, onDismiss = { showCardEditor = false },
        onSave = { title, content, speech, tip ->
            val ok = vm.saveCard(cardEditor?.id, title, content, speech, tip)
            if (ok) showCardEditor = false
            ok
        })

    if (deckToDelete != null) ConfirmDialog("删除卡组？", "“${deckToDelete!!.title}”及其中全部卡片都会被删除，且无法恢复。",
        onDismiss = { deckToDelete = null }, onConfirm = {
            val id = deckToDelete!!.id
            scope.launch { if (vm.deleteDeck(id)) { showDeckEditor = false; page = Page.HOME }; deckToDelete = null }
        })
    if (cardToDelete != null) ConfirmDialog("删除卡片？", "“${cardToDelete!!.title}”删除后无法恢复。",
        onDismiss = { cardToDelete = null }, onConfirm = {
            val id = cardToDelete!!.id
            scope.launch { vm.deleteCard(id); cardToDelete = null }
        })
    if (showMode) ModeDialog(mode, onDismiss = { showMode = false }, onChoose = { chosen ->
        showMode = false
        if (page == Page.STUDY) {
            // 学习中切模式：交给引擎处理（失效旧操作、清分段、回正面）
            mode = chosen
            studyVm.setMode(chosen.toLearningMode())
        } else {
            mode = chosen
            studySession += 1
            page = Page.STUDY
        }
    })
    if (importPreview != null) ImportDialog(importPreview!!, onDismiss = vm::closeImport,
        onConfirm = { title ->
            val ok = vm.confirmImport(title)
            if (ok) page = Page.DETAIL
            ok
        })
}
