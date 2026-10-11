import SwiftUI
import UniformTypeIdentifiers

// DecksPage 是主窗口中的 “卡组页面”
// 它负责卡组列表，导入，导出，删除和相关设置入口
struct DecksPage: View {

    // 从 SwiftUI 环境中取得 App 级共享状态
    @Environment(AppModel.self)
    private var appModel: AppModel

    // SwiftUI 提供的窗口打开的动作
    //
    // 传入 Window 声明时使用的固定ID
    // 系统会打开或激活对应窗口
    @Environment(\.openWindow)
    private var openWindow

    //SwiftUI 提供关闭的指定窗口动作
    //
    // 删除当前学习卡组后
    // 用它关闭浮动学习窗口
    @Environment(\.dismissWindow)
    private var dismissWindow

    // 防止 DecksPage 刷新时重复恢复浮动学习窗口。
    @State private var didRestoreFloatingWindow = false

    // 保存准备编辑的卡组
    //
    // nil: 不显示编辑弹窗
    // 有 Deck 显示这个卡组的编辑弹窗
    @State private var deckPendingEditing: Deck?
    // 控制系统保存文件窗口是否显示
    @State private var isShowingFileExporter = false
    // 保存等待写入磁盘的 JSON 文件对象
    //
    // nil 表示当前没有准备导出的文件
    @State private var exportDocument: JSONExportDocumnent?
    // 保存面板中默认显示的文件名
    @State private var exportFilename = "EchoCards"
    // 保存用户准备删除的卡组
    //
    // nil 表示当前没有等待确认的删除操作
    @State private var deckPendingDeletion: Deck?

    // 控制系统文件选择窗口是否显示
    @State private var isShowingFileImporter = false

    //
    // 显示本次导入或校验的结果
    @State private var importStatusMessage: String?

    var body: some View {

        @Bindable var appModel = appModel

        // 先把 MainView 中原来的 NavigationStack
        // 以及里面的所有内容完整放在这里
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {

                    // decks.isEmpty 用来判断卡组数组是否为空
                    if appModel.decks.isEmpty {
                        // 没有卡组时显示空状态
                        ContentUnavailableView(
                            "还没有卡组",
                            systemImage: "rectangle.stack.badge.plus",
                            description: Text("导入 JSON 或创建卡组开始使用")
                        )
                    } else {
                        // 有卡组时显示卡组列表
                        //
                        // LazyVStack 和 VStack 类似
                        // 但它只在内容接近可见区域创建子视图
                        // 更适合放在 ScrollView 中显示列表
                        LazyVStack(spacing: 10) {
                            // ForEach 会为数组中每个 Deck
                            // 分别创建一行界面
                            //
                            // Deck 遵守 Identifiable
                            // 因此 SwiftUI 会使用 deck.id 区分每一行
                            ForEach(appModel.decks) { deck in

                                DeckRowView(
                                    deck: deck,
                                    onEdit: {
                                        // DecksPage 仍然持有弹窗状态。
                                        deckPendingEditing = deck
                                    },
                                    onExport: {
                                        // 导出涉及异步读取 SQLite 因此在此处创建 Task
                                        prepareExport(for: deck)
                                    },
                                    onDelete: {
                                        // 记录待删除卡组，后面的 alert 会要求用户确认。
                                        deckPendingDeletion = deck
                                    }
                                )
                            }
                        }
                    }
                    // 限制列表宽度，避免窗口变宽时内容过度拉伸
                    //.frame(maxWidth: 520)

                    Button("导入 JSON 卡组") {
                        // 修改状态后，下面的 fileImporter 会打开系统文件选择器
                        isShowingFileImporter = true
                    }
                    .buttonStyle(.borderedProminent)

                    // 有导入结果时显示提示文字
                    if let importStatusMessage {
                        Text(importStatusMessage)
                            .font(.callout)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }

                    Toggle("在所有桌面空间显示", isOn: $appModel.settings.appearsOnAllSpaces)
                        .toggleStyle(.switch)
                        .frame(maxWidth: 240)

                    // 控制浮动窗口是否允许通过鼠标拖动
                    Toggle("允许拖动浮动窗口", isOn: $appModel.settings.allowsDragging)
                        .toggleStyle(.switch)
                        .frame(maxWidth: 240)

                    // 控制浮动窗口底部是否显示
                    Toggle("显示控制按钮", isOn: $appModel.settings.showsControls)
                        .toggleStyle(.switch)
                        .frame(maxWidth: 240)

                    VStack(alignment: .leading, spacing: 6) {
                        Text("卡片文字大小：\(appModel.settings.cardFontSize, specifier: "%.0f")")

                        Slider(
                            // 滑块直接修改共享设置
                            value: $appModel.settings.cardFontSize,
                            in: 16...48,
                            step: 1
                        )
                    }.frame(maxWidth: 240)

                    VStack(alignment: .leading, spacing: 12) {
                        // 浮动窗口宽度设置
                        Text("窗口宽度：\(appModel.settings.floatingWindowWidth, specifier: "%.0f")")
                        Slider(
                            value: $appModel.settings.floatingWindowWidth,
                            in: 280...800,
                            step: 10
                        )

                        // 浮动窗口高度设置

                        Text(
                            "窗口高度：\(appModel.settings.floatingWindowHeight, specifier: "%.0f")"
                        )

                        Slider(
                            value: $appModel.settings.floatingWindowHeight,
                            in: 180...600,
                            step: 10
                        )
                    }.frame(maxWidth: 240)

                    VStack(alignment: .leading, spacing: 6) {
                        Text(
                            "背景透明度：\(appModel.settings.floatingWindowOpacity * 100, specifier: "%.0f")"
                        )
                        Slider(
                            // 只调整浮动窗口玻璃背景的透明度
                            value: $appModel.settings.floatingWindowOpacity,
                            // 最低保留 15% 避免背景完全消失
                            in: 0.15...1.0,
                            // 每次变化 5%
                            step: 0.05
                        )
                    }
                    .frame(maxWidth: 240)

                    Button("恢复默认设置") {
                        // 恢复 UserSettings.defaults 中定义的全部默认值。
                        appModel.resetSettings()
                    }
                    .buttonStyle(.bordered)

                    Toggle("启动时恢复浮动窗口", isOn: $appModel.settings.restoresFloatingWindow)
                        .toggleStyle(.switch)
                        .frame(maxWidth: 240)

                    VStack(alignment: .leading, spacing: 6) {
                        Text("默认学习模式")

                        Picker(
                            "默认学习模式",
                            selection: $appModel.settings.defaultLearningMode
                        ) {
                            // 遍历 LearningMode 中定义的所有学习模式
                            ForEach(LearningMode.allCases) { mode in
                                Text(mode.displayName)
                                    .tag(mode)
                            }
                        }
                        .pickerStyle(.segmented)
                    }
                    .frame(maxWidth: 360)

                    VStack(alignment: .leading, spacing: 6) {
                        // 显示当前朗读速度。
                        //
                        // 例如 1.0 表示正常速度，
                        // 0.5 表示较慢，2.0 表示较快。
                        Text(
                            "朗读速度：\(appModel.settings.speechRate, specifier: "%.1f") 倍"
                        )

                        Slider(
                            // 直接修改并保存用户设置。
                            value: $appModel.settings.speechRate,

                            // 第一版允许 0.5 到 2.0 倍速。
                            in: 0.5...2.0,

                            // 每次调整 0.1。
                            step: 0.1
                        )

                    }
                    .frame(maxWidth: 240)

                    Button("恢复正常语速") {
                        // 1.0 表示正常朗读速度。
                        appModel.settings.speechRate = 1.0
                    }
                    .buttonStyle(.link)

                    VStack(alignment: .leading, spacing: 6) {
                        // 存储值是毫秒，因此除以 1000 后显示为秒。
                        Text(
                            "自动翻页延迟：\(Double(appModel.settings.autoAdvanceDelayMilliseconds) / 1000, specifier: "%.1f") 秒"
                        )

                        Slider(
                            // Slider 需要 Binding<Double>
                            // 但设置中保存的时 Int 因此需要手动转换
                            value: Binding(
                                get: {
                                    // 读取时：把毫秒转换成秒
                                    Double(
                                        appModel.settings.autoAdvanceDelayMilliseconds
                                    ) / 1000
                                },
                                set: { seconds in
                                    appModel.settings.autoAdvanceDelayMilliseconds = Int(
                                        seconds * 1000)
                                }
                            ),
                            in: 0.5...10.0,
                            step: 0.5
                        )
                    }.frame(maxWidth: 240)
                }
                .padding(24)
            }
            // 视图修饰器，用来打开系统文件选择窗口
            .fileImporter(
                // $ 表示把状态的读写权限交给 fileImporter。
                //
                // 普通的 isShowingFileImporter 只能取得当前值；
                // $isShowingFileImporter 是 Binding<Bool>，
                // 文件选择器可以读取和修改这个值。
                isPresented: $isShowingFileImporter,
                // .json 是 UTType.json 的简写。
                // 它告诉系统这里只允许选择 JSON 文件。
                allowedContentTypes: [.json],
                // false 表示一次只能选择一个文件。
                allowsMultipleSelection: false
            ) { result in
                do {
                    // result.get() 成功是返回 URL 数组
                    // 失败时会抛出错误
                    let selectedURLs = try result.get()
                    // 取得用户选择的第一个文件
                    // 如果数组为空，就结束当前闭包
                    guard let url = selectedURLs.first else {
                        return
                    }

                    // 开始使用 macOS  文件沙盒提供的
                    // 临时文件访问权限
                    let didAccess = url.startAccessingSecurityScopedResource()

                    // 当前 do 代码快结束时释放文件访问权限
                    defer {
                        if didAccess {
                            url.stopAccessingSecurityScopedResource()
                        }
                    }

                    // 读取，解码和校验 JSON
                    //
                    // 这一个完成后，document 已经在 App 内存中
                    // 后面不再需要继续访问原始文件
                    let document = try DeckFileImporter().load(
                        from: url
                    )

                    // fileImporter 的闭包本身不是 async
                    // 不能直接在这里使用 await
                    //
                    // Task 会创建一个可以执行异步代码的任务
                    Task { @MainActor in
                        do {
                            // 等待 AppModel 完成查重，转换和保存
                            let outcome = try await appModel.importDeck(document)

                            // switch 根据导入结果显示不同提示
                            switch outcome {
                            case .created(let deck):
                                // let .created(deck) 会取出
                                // created 状态中携带的 Deck
                                importStatusMessage = "导入成功: \(deck.title)"
                            case .existing(let deck):
                                // 相同内容已经存在时
                                // 不会重复创建卡组
                                importStatusMessage = "卡组已存在：\(deck.title)"
                            }
                        } catch {
                            // 捕获仓库或业务访问抛出的错误
                            importStatusMessage = error.localizedDescription
                        }

                    }

                } catch {
                    // 捕获文件选择，文件读取，JSON 解码
                    // 或内容校验时出现的错误
                    importStatusMessage = error.localizedDescription
                }
            }
            .fileExporter(
                // 控制保存面板是否出现
                isPresented: $isShowingFileExporter,
                // 要保存的文件对象
                document: exportDocument,
                // 保存类型为 JSON
                contentType: .json,

                // 保存面板建议的文件名
                //
                // 系统会自动补充 .json 扩展名
                defaultFilename: exportFilename

            ) { result in
                // 保存完成或取消后，系统执行这个闭包
                switch result {
                case .success(let url):
                    // url 是最终保存文件的位置
                    importStatusMessage = "已导出: \(url.lastPathComponent)"
                case .failure(let error):
                    // 用户点击“取消” 不属于需要显示的错误
                    if let cocoaError = error as? CocoaError, cocoaError.code == .userCancelled {
                        break
                    }
                    importStatusMessage = error.localizedDescription
                }

                // 保存操作结束后释放内存中的文件对象
                exportDocument = nil

            }
            .task {

                do {
                    // 从仓库读取卡组并更新 appModel.decks
                    try await appModel.loadDecks()
                } catch {
                    // 加载失败时在主界面显示错误
                    importStatusMessage = error.localizedDescription
                }

                // 同一次界面生命周期只处理一次
                guard !didRestoreFloatingWindow else {
                    return
                }

                didRestoreFloatingWindow = true

                // 用户没有开启恢复功能时不打开窗口
                guard appModel.settings.restoresFloatingWindow else {
                    return
                }

                // 打开或激活唯一的浮动学习窗口
                openWindow(id: "floating-study-window")

                // 同步更新 AppModel 中的窗口状态
                appModel.markFloatingWindowOpen()
            }
            .alert(
                "确定删除这个卡组吗？",
                // alert 需要 Binding<Bool>
                //
                // deckPendingDeletion 不为 nil 时返回 true
                // 表示显示弹窗
                isPresented: Binding(
                    get: {
                        deckPendingDeletion != nil

                    },
                    set: { isPresented in
                        // 系统关闭弹窗时清除待删除卡组
                        if !isPresented {
                            deckPendingDeletion = nil
                        }
                    }
                ),
                // presenting 会把非 nil 的 Deck
                // 传进下面的操作闭包
                presenting: deckPendingDeletion
            ) { deck in
                Button("删除", role: .destructive) {
                    Task { @MainActor in
                        // 删除前记录它是不是当前学习卡组
                        let wasSelected = appModel.selectedDeckID == deck.id
                        do {
                            // 等待 AppModel 完成数据库删除
                            // 和卡组列表刷新
                            try await appModel.deleteDeck(id: deck.id)

                            // 如果删除的是当前学习卡组
                            // 同时关闭浮动窗口
                            if wasSelected {
                                dismissWindow(
                                    id: "floating-study-window"
                                )
                            }
                            importStatusMessage = "已删除 \(deck.title)"
                        } catch {
                            importStatusMessage = error.localizedDescription
                        }
                        // 无论成功还是失败 都清除待删除状态
                        deckPendingDeletion = nil
                    }
                }
                Button("取消", role: .cancel) {
                    //取消时不修改数据库
                    deckPendingDeletion = nil
                }

            } message: { deck in
                // 把卡组名称显示在确认信息里
                //
                // 删除卡组会同时删除它的全部卡片
                Text("“\(deck.title)”及其中的全部卡片都会被永久删除。")
            }
        }
    }

    // 准备导出文件
    // 这个方法放在 ViewBuilder 外面，避免 Swift 编译器推导过大的闭包类型。
    private func prepareExport(for deck: Deck) {
        Task { @MainActor in
            do {
                // 从数据库读取卡组和卡片，并生成 Android 兼容 JSON
                let data = try await appModel.exportDeck(deck)

                // 将 JSON 数据交给 SwiftUI 的系统保存面板
                exportDocument = JSONExportDocumnent(data: data)

                // 根据卡组名称生成默认文件名称
                exportFilename = Self.safeFilename(from: deck.title)

                // 数据准备完成后 显示 另存为 面板
                isShowingFileExporter = true
            } catch {
                importStatusMessage = error.localizedDescription
            }
        }
    }

    // 将卡组名称转换为适合 macOS 文件名的文本。
// 例如“英语 / Unit 1”会变成“英语 - Unit 1”。
private static func safeFilename(from title: String) -> String {

  // 这些字符可能被系统理解成文件路径分隔符。
  let forbiddenCharacters = CharacterSet(
    charactersIn: "/:\\"
  )

  // 将不允许的字符替换为短横线。
  let filename = title
    .components(separatedBy: forbiddenCharacters)
    .joined(separator: "-")
    .trimmingCharacters(in: .whitespacesAndNewlines)

  // 防止卡组名称只有空格或禁止字符时生成空文件名。
  return filename.isEmpty ? "EchoCards" : filename
}

}

// DeckRowView 只负责“一行卡组”的显示和用户操作
// 它不持有数据库状态；点击后的业务操作通过闭包交给 DecksPage
private struct DeckRowView: View {
    let deck: Deck
    let onEdit: () -> Void
    let onExport: () -> Void
    let onDelete: () -> Void

    var body: some View {
        NavigationLink {
            // 点击卡组后进入已有的详情页
            DeckDetailView(deck: deck)
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "rectangle.stack")
                    .font(.title2)

                VStack(alignment: .leading, spacing: 4) {
                    Text(deck.title)
                        .font(.headline)

                    // 没有说明时不占有额外空间
                    if let description = deck.description, !description.isEmpty {
                        Text(description)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                    }
                }

                Spacer()

                Image(systemName: "chevron.right")
                    .foregroundStyle(.tertiary)
            }
        }
        .padding(14)
        .contentShape(RoundedRectangle(cornerRadius: 16))
        .contextMenu {
            Button("编辑卡组", action: onEdit)

            Divider()

            Button("导出 JSON", action: onExport)

            Divider()

            Button("删除卡组", action: onDelete)
        }
        .buttonStyle(.plain)
        .glassEffect(in: .rect(cornerRadius: 16))
    }

}
