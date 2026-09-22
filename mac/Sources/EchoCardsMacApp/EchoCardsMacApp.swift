import SwiftUI
import Observation
import AppKit
import UniformTypeIdentifiers

@main
struct EchoCardsMacApp: App {
    @State private var model = AppModel()
    init() {
        DispatchQueue.main.async {
            NSApp.windows.filter { $0.identifier?.rawValue == "floating-card" || $0.title == "悬浮卡片" }.forEach { $0.orderOut(nil) }
        }
    }
    var body: some Scene {
        WindowGroup("EchoCards") { RootView().environment(model).frame(minWidth: 900, minHeight: 600) }
            .commands { CommandGroup(replacing: .newItem) { Button("新建卡组") { model.showNewDeck = true }.keyboardShortcut("n", modifiers: .command) } }
        Window("悬浮卡片", id: "floating-card") { FloatingCardView().environment(model) }
            .windowLevel(.floating)
            .windowStyle(.plain)
            .defaultSize(width: 520, height: 360)
            .windowResizability(.contentSize)
            .defaultLaunchBehavior(.suppressed)
            .restorationBehavior(.disabled)
    }
}

@MainActor @Observable final class AppModel {
    let store: SQLiteStore
    var decks: [DeckRecord] = []
    var search = ""
    var showNewDeck = false
    var errorMessage: String?
    var studyDeck: DeckRecord?
    init() { do { store = try SQLiteStore(path: Self.databasePath()); reload() } catch { store = try! SQLiteStore(); errorMessage = error.localizedDescription } }
    var filteredDecks: [DeckRecord] { search.isEmpty ? decks : decks.filter { $0.title.localizedCaseInsensitiveContains(search) } }
    func reload() { do { decks = try store.decks(); if studyDeck == nil { studyDeck = decks.first } } catch { errorMessage = error.localizedDescription } }
    func createDeck(title: String, description: String) { do { _ = try store.createDeck(title: title, description: description); reload() } catch { errorMessage = error.localizedDescription } }
    func deleteDeck(_ deck: DeckRecord) { do { try store.deleteDeck(id: deck.id); reload() } catch { errorMessage = error.localizedDescription } }
    func cards(for deck: DeckRecord) -> [CardRecord] { (try? store.cards(deckId: deck.id)) ?? [] }
    func importJSON() {
        let panel = NSOpenPanel(); panel.allowedContentTypes = [.json]; panel.allowsMultipleSelection = false
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do { let imported = try JsonDeckImporter.parse(Data(contentsOf: url)); _ = try store.importDeck(imported, displayTitle: imported.title); reload() } catch { errorMessage = error.localizedDescription }
    }
    private static func databasePath() -> String { let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("EchoCards"); try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true); return dir.appendingPathComponent("echocards.sqlite").path }
}

enum AppSection: Hashable { case home, settings }

struct RootView: View {
    @Environment(AppModel.self) private var model
    @State private var selection: AppSection? = .home
    var body: some View {
        @Bindable var model = model
        NavigationSplitView { List(selection: $selection) { Label("首页", systemImage: "rectangle.stack").tag(AppSection.home); Label("我的", systemImage: "person.crop.circle").tag(AppSection.settings) }.navigationTitle("知声卡").listStyle(.sidebar) } detail: { switch selection { case .settings: SettingsView(); case .home, .none: HomeView() } }
            .sheet(isPresented: $model.showNewDeck) { NewDeckView() }
            .alert("提示", isPresented: .constant(model.errorMessage != nil), presenting: model.errorMessage) { _ in Button("好") { model.errorMessage = nil } } message: { Text($0) }
    }
}

struct HomeView: View {
    @Environment(AppModel.self) private var model
    @State private var detailDeck: DeckRecord?
    var body: some View {
        @Bindable var model = model
        if let detailDeck {
            DeckDetailView(deck: detailDeck) { self.detailDeck = nil }
        } else {
            VStack(spacing: 16) {
                HStack { Text("卡组").font(.largeTitle.bold()); Spacer(); TextField("搜索卡组", text: $model.search).textFieldStyle(.roundedBorder).frame(width: 220); Button("导入") { model.importJSON() }; Button("新建") { model.showNewDeck = true }.buttonStyle(.borderedProminent) }.padding(.horizontal)
                if model.filteredDecks.isEmpty { VStack(spacing: 16) { Image(systemName: "rectangle.stack.badge.plus").font(.system(size: 48)); Text("还没有卡组").font(.title2.bold()); Text("创建或导入一个卡组，开始你的第一次学习。").foregroundStyle(.secondary); Button("创建第一个卡组") { model.showNewDeck = true }.buttonStyle(.borderedProminent) }.padding(40).glassCard() } else { List(model.filteredDecks) { deck in HStack { VStack(alignment: .leading) { Text(deck.title).font(.headline); Text(deck.description).foregroundStyle(.secondary) }; Spacer(); Button("打开") { detailDeck = deck }; Button(role: .destructive) { model.deleteDeck(deck) } label: { Image(systemName: "trash") }.buttonStyle(.borderless) }.contentShape(Rectangle()).onTapGesture { detailDeck = deck } }.listStyle(.inset) }
            }.padding(.vertical)
        }
    }
}

struct DeckDetailView: View {
    @Environment(AppModel.self) private var model
    let deck: DeckRecord
    let onClose: () -> Void
    @State private var editorCard: CardRecord?
    @State private var showingNewCard = false
    @State private var showingStudy = false
    @Environment(\.openWindow) private var openWindow
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack { Text(deck.title).font(.largeTitle.bold()); Spacer(); Button("关闭") { onClose() } }
            Text(deck.description).foregroundStyle(.secondary)
            HStack { Text("卡片").font(.title2.bold()); Spacer(); Button("添加卡片") { showingNewCard = true }.buttonStyle(.borderedProminent) }
            List(model.cards(for: deck)) { card in
                HStack { VStack(alignment: .leading) { Text(card.title).font(.headline); Text(card.content).foregroundStyle(.secondary).lineLimit(2) }; Spacer(); Button("编辑") { editorCard = card }; Button(role: .destructive) { try? model.store.deleteCard(id: card.id) } label: { Image(systemName: "trash") }.buttonStyle(.borderless) }
            }
            Spacer(); Button("打开悬浮卡片") {
                model.studyDeck = deck
                openWindow(id: "floating-card")
            }.buttonStyle(.borderedProminent)
        }.padding(24).frame(minWidth: 650, minHeight: 500)
        .sheet(item: $editorCard) { CardEditorView(deck: deck, card: $0) }
        .sheet(isPresented: $showingNewCard) { CardEditorView(deck: deck, card: nil) }
        .sheet(isPresented: $showingStudy) { StudyView(deck: deck, cards: model.cards(for: deck)) }
    }
}

struct FloatingCardView: View {
    @Environment(AppModel.self) private var model
    @State private var index = 0
    @State private var flipped = false
    @FocusState private var focused: Bool
    @AppStorage("previousCardKey") private var previousCardKey = "upArrow"
    @AppStorage("nextCardKey") private var nextCardKey = "downArrow"
    @AppStorage("holdToShowEnabled") private var holdToShowEnabled = false
    @AppStorage("holdToShowKey") private var holdToShowKey = "space"
    @State private var hovering = false
    @State private var holdMonitor = KeyHoldMonitor()
    @State private var windowStartOrigin: NSPoint?
    var cards: [CardRecord] { guard let deck = model.studyDeck else { return [] }; return model.cards(for: deck) }
    var card: CardRecord? { cards.indices.contains(index) ? cards[index] : nil }
    var body: some View {
        VStack(spacing: 18) {
            if let deck = model.studyDeck, let card {
                HStack { Text(deck.title).font(.headline); Spacer(); Text("\(index + 1) / \(cards.count)").foregroundStyle(.secondary) }
                ZStack(alignment: .bottom) {
                    VStack(spacing: 14) { Text(card.title).font(.title.bold()); Text(flipped ? (card.memoryTip.isEmpty ? "暂无快速记忆点" : card.memoryTip) : card.content).font(.title3).multilineTextAlignment(.center).frame(maxWidth: .infinity, minHeight: 190).padding(28).glassCard() }.contentShape(Rectangle()).onTapGesture { flipped.toggle() }.gesture(DragGesture().onChanged { value in
                        guard let window = NSApp.keyWindow else { return }
                        if windowStartOrigin == nil { windowStartOrigin = window.frame.origin }
                        guard let start = windowStartOrigin else { return }
                        window.setFrameOrigin(NSPoint(x: start.x + value.translation.width, y: start.y - value.translation.height))
                    }.onEnded { _ in windowStartOrigin = nil })
                    if hovering { HStack { Button { move(-1) } label: { Image(systemName: "chevron.up") }; Spacer(); Button { closeWindow() } label: { Image(systemName: "xmark") }; Spacer(); Button { move(1) } label: { Image(systemName: "chevron.down") } }.buttonStyle(.borderedProminent).padding(.bottom, 12) }
                }.onHover { hovering = $0 }
                Text(holdToShowEnabled ? "按住 \(holdToShowKey) 显示 · 点击翻面 · \(previousCardKey) / \(nextCardKey) 切卡" : "点击翻面 · \(previousCardKey) 上一张 · \(nextCardKey) 下一张").font(.caption).foregroundStyle(.secondary)
            } else { Text("请从卡组详情打开悬浮卡片") }
        }.padding(22).frame(minWidth: 460, minHeight: 300).background(WindowDragEnabler()).focusable(true).focused($focused).onAppear { focused = true; NSApp.keyWindow?.alphaValue = holdToShowEnabled ? 0 : 1; holdMonitor.start(enabled: holdToShowEnabled, previousKey: previousCardKey, nextKey: nextCardKey, onMove: { delta in move(delta) }, onVisibility: { visible in NSApp.keyWindow?.alphaValue = visible ? 1 : 0 }) }.onDisappear { holdMonitor.stop(); NSApp.keyWindow?.alphaValue = 1 }
    }
    private func move(_ delta: Int) { index = (index + delta + cards.count) % cards.count; flipped = false }
    private func closeWindow() { NSApp.keyWindow?.close() }
}

private struct WindowDragEnabler: NSViewRepresentable {
    func makeNSView(context: Context) -> NSView { ConfiguredView() }
    func updateNSView(_ nsView: NSView, context: Context) {}
    private final class ConfiguredView: NSView {
        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            window?.isMovableByWindowBackground = true
            window?.titlebarAppearsTransparent = true
            window?.titleVisibility = .hidden
            window?.styleMask.insert(.nonactivatingPanel)
            window?.hidesOnDeactivate = false
            window?.level = .floating
            (window as? NSPanel)?.becomesKeyOnlyIfNeeded = true
            window?.isReleasedWhenClosed = false
            window?.collectionBehavior.insert(.ignoresCycle)
            window?.animationBehavior = .none
            // Keep the document window active while the card stays above it.
            // This prevents macOS from rendering the main window in its inactive/gray state.
            DispatchQueue.main.async {
                let floatingWindow = self.window
                guard let mainWindow = NSApp.windows.first(where: {
                    $0 !== floatingWindow && $0.title == "EchoCards"
                }) else { return }
                mainWindow.makeKeyAndOrderFront(nil)
                floatingWindow?.orderFront(nil)
            }
        }
    }
}

@MainActor final class KeyHoldMonitor {
    private var monitor: Any?
    func start(enabled: Bool, previousKey: String, nextKey: String, onMove: @escaping (Int) -> Void, onVisibility: @escaping (Bool) -> Void) {
        stop()
        monitor = NSEvent.addLocalMonitorForEvents(matching: [.keyDown, .keyUp]) { event in
            if event.type == .keyDown {
                if (event.keyCode == 126 && previousKey == "upArrow") || (event.keyCode == 123 && previousKey == "leftArrow") { onMove(-1); return nil }
                if (event.keyCode == 125 && nextKey == "downArrow") || (event.keyCode == 124 && nextKey == "rightArrow") { onMove(1); return nil }
            }
            if enabled && event.keyCode == 49 { onVisibility(event.type == .keyDown) }
            return event
        }
    }
    func stop() { if let monitor { NSEvent.removeMonitor(monitor) }; monitor = nil }
}

struct StudyView: View {
    let deck: DeckRecord
    let cards: [CardRecord]
    @Environment(\.dismiss) private var dismiss
    @State private var index = 0
    @State private var flipped = false
    @State private var player = MacSpeechPlayer()
    @State private var recognizer = MacSpeechRecognizer()
    @State private var mode = StudyMode.manual
    @State private var recognizedText = ""
    @State private var followAlongCompleted = false
    @State private var speechError: String?
    @State private var speechStatus = ""
    private let matcher = FollowAlongMatcher()
    var card: CardRecord? { cards.indices.contains(index) ? cards[index] : nil }
    var body: some View {
        VStack(spacing: 24) {
            HStack { Text(deck.title).font(.title2.bold()); Spacer(); Text("\(cards.isEmpty ? 0 : index + 1) / \(cards.count)"); Button("退出") { dismiss() } }
            if let card {
                Picker("学习模式", selection: $mode) { ForEach(StudyMode.allCases, id: \.self) { Text($0.title).tag($0) } }.pickerStyle(.segmented).frame(maxWidth: 420)
                VStack(spacing: 18) { Text(card.title).font(.title.bold()); Text(flipped ? (card.memoryTip.isEmpty ? "暂无快速记忆点" : card.memoryTip) : card.content).font(.title3).multilineTextAlignment(.center).frame(maxWidth: 650, minHeight: 240).padding(36).glassCard() }.onTapGesture { flipped.toggle() }
                Text("点击卡片翻面").foregroundStyle(.secondary)
                if mode == .followAlong && !speechStatus.isEmpty { Text(speechStatus).foregroundStyle(.secondary) }
                if mode == .followAlong && !recognizedText.isEmpty { Text("识别中：\(recognizedText)").foregroundStyle(.secondary) }
                HStack { Button(mode == .followAlong ? "开始跟读" : "朗读") { if mode == .followAlong { speechStatus = "正在请求语音和麦克风权限…"; Task { guard await recognizer.authorize() else { speechStatus = "权限未开启，请在系统设置中允许语音识别和麦克风。"; return }; do { try recognizer.start { _, text, _ in recognizedText = text; speechStatus = "正在识别"; if matcher.isComplete(target: card.speechText.isEmpty ? card.content : card.speechText, spoken: text) && !followAlongCompleted { followAlongCompleted = true; recognizer.stop(); flipped = true; speechStatus = "跟读完成" } } } catch { speechStatus = "识别失败：\(error.localizedDescription)" } } } else { _ = player.speak(card.speechText.isEmpty ? card.content : card.speechText) } }; Button("暂停") { player.pause(); recognizer.stop(); speechStatus = "已暂停" }; Button("上一张") { player.stop(); recognizer.stop(); index = (index - 1 + cards.count) % cards.count; flipped = false }; Button("下一张") { player.stop(); recognizer.stop(); index = (index + 1) % cards.count; flipped = false }.buttonStyle(.borderedProminent) }
            } else { Text("暂无卡片").foregroundStyle(.secondary) }
            Spacer()
        }.padding(32).frame(minWidth: 760, minHeight: 560).onDisappear { player.stop(); recognizer.stop() }.onChange(of: index) { _, _ in followAlongCompleted = false; recognizedText = "" }.task(id: "\(mode.title)-\(index)") { guard mode == .autoPlay, let card else { return }; await player.speakAndWait(card.speechText.isEmpty ? card.content : card.speechText); guard !Task.isCancelled else { return }; flipped = true; try? await Task.sleep(for: .milliseconds(600)); guard !Task.isCancelled else { return }; index = (index + 1) % cards.count; flipped = false }.alert("跟读权限", isPresented: Binding(get: { speechError != nil }, set: { if !$0 { speechError = nil } })) { Button("打开系统设置") { NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Microphone")!); speechError = nil }; Button("好") { speechError = nil } } message: { Text(speechError ?? "") }
    }
}

enum StudyMode: CaseIterable { case manual, followAlong, autoPlay; var title: String { switch self { case .manual: "手动"; case .followAlong: "跟读"; case .autoPlay: "自动播放" } } }

struct CardEditorView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let deck: DeckRecord
    let card: CardRecord?
    @State private var title: String
    @State private var content: String
    @State private var speechText: String
    @State private var memoryTip: String
    init(deck: DeckRecord, card: CardRecord?) { self.deck = deck; self.card = card; _title = State(initialValue: card?.title ?? ""); _content = State(initialValue: card?.content ?? ""); _speechText = State(initialValue: card?.speechText ?? ""); _memoryTip = State(initialValue: card?.memoryTip ?? "") }
    var body: some View { VStack(alignment: .leading, spacing: 12) { Text(card == nil ? "添加卡片" : "编辑卡片").font(.title.bold()); TextField("标题", text: $title); TextEditor(text: $content).frame(minHeight: 100).overlay(RoundedRectangle(cornerRadius: 8).stroke(.separator)); TextField("跟读文本（可选）", text: $speechText); TextField("快速记忆点（可选）", text: $memoryTip); HStack { Spacer(); Button("取消") { dismiss() }; Button("保存") { try? model.store.saveCard(deckId: deck.id, cardId: card?.id, title: title, content: content, speechText: speechText, memoryTip: memoryTip); dismiss() }.buttonStyle(.borderedProminent).disabled(title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) } }.padding(24).frame(width: 520) }
}

struct NewDeckView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var description = ""
    var body: some View { VStack(alignment: .leading, spacing: 16) { Text("新建卡组").font(.title.bold()); TextField("卡组名称", text: $title); TextField("说明（可选）", text: $description); HStack { Spacer(); Button("取消") { dismiss() }; Button("创建") { model.createDeck(title: title, description: description); dismiss() }.buttonStyle(.borderedProminent).disabled(title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) } }.padding(24).frame(width: 420) }
}

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var settings = SettingsRecord(mode: "manual", rate: 1.0, delay: 600)
    var body: some View {
        Form {
            Section("学习设置") {
                Text("macOS 端采用悬浮卡片和键盘翻页，不使用朗读或跟读识别。")
                Picker("上一张按键", selection: $previousCardKey) { Text("↑ 上方向键").tag("upArrow"); Text("← 左方向键").tag("leftArrow") }
                Picker("下一张按键", selection: $nextCardKey) { Text("↓ 下方向键").tag("downArrow"); Text("→ 右方向键").tag("rightArrow") }
                Toggle("按住按键才显示卡片", isOn: $holdToShowEnabled)
                Picker("显示按键", selection: $holdToShowKey) { Text("空格").tag("space") }
                Button("保存设置") { try? model.store.saveSettings(settings) }.buttonStyle(.borderedProminent)
            }
        }.formStyle(.grouped).navigationTitle("我的").task { settings = (try? model.store.settings()) ?? settings }
    }
    @AppStorage("previousCardKey") private var previousCardKey = "upArrow"
    @AppStorage("nextCardKey") private var nextCardKey = "downArrow"
    @AppStorage("holdToShowEnabled") private var holdToShowEnabled = false
    @AppStorage("holdToShowKey") private var holdToShowKey = "space"
}
private struct GlassCardModifier: ViewModifier { func body(content: Content) -> some View { if #available(macOS 26.0, *) { content.glassEffect(.regular, in: .rect(cornerRadius: 18)) } else { content.background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18)) } } }
private extension View { func glassCard() -> some View { modifier(GlassCardModifier()) } }

extension Notification.Name { static let newDeckRequested = Notification.Name("EchoCards.newDeckRequested") }
