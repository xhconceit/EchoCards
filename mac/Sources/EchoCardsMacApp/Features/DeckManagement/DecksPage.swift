import AppKit
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
                        LazyVGrid(
                            columns: [
                                GridItem(
                                    .adaptive(minimum: 240, maximum: 360),
                                    spacing: 18
                                )
                            ], spacing: 18
                        ) {
                            // ForEach 会为数组中每个 Deck
                            // 分别创建一行界面
                            //
                            // Deck 遵守 Identifiable
                            // 因此 SwiftUI 会使用 deck.id 区分每一行
                            ForEach(appModel.decks) { deck in

                                DeckCardView(
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

                    Button("复制 AI 导入提示词", systemImage: "doc.on.clipboard") {
                        // 将符合知声卡导入规范的提示词复制到系统剪贴板。
                        NSPasteboard.general.clearContents()
                        NSPasteboard.general.setString(
                            Self.aiImportPrompt,
                            forType: .string
                        )
                        importStatusMessage = "已复制 AI 导入提示词"
                    }
                    .buttonStyle(.bordered)

                    Button("从剪贴板导入 JSON", systemImage: "clipboard") {
                        importJSONFromPasteboard()
                    }
                    .buttonStyle(.bordered)

                    // 有导入结果时显示提示文字
                    if let importStatusMessage {
                        Text(importStatusMessage)
                            .font(.callout)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }

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

    // 读取剪贴板中的 JSON 文本，并走与文件导入相同的校验流程。
    private func importJSONFromPasteboard() {
        guard let json = NSPasteboard.general.string(forType: .string) else {
            importStatusMessage = "剪贴板中没有可导入的 JSON 文本"
            return
        }

        do {
            let document = try DeckFileImporter().load(
                from: Data(json.utf8)
            )

            Task { @MainActor in
                do {
                    let outcome = try await appModel.importDeck(document)

                    switch outcome {
                    case .created(let deck):
                        importStatusMessage = "导入成功: \(deck.title)"
                    case .existing(let deck):
                        importStatusMessage = "卡组已存在：\(deck.title)"
                    }
                } catch {
                    importStatusMessage = error.localizedDescription
                }
            }
        } catch {
            importStatusMessage = error.localizedDescription
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
        let filename =
            title
            .components(separatedBy: forbiddenCharacters)
            .joined(separator: "-")
            .trimmingCharacters(in: .whitespacesAndNewlines)

        // 防止卡组名称只有空格或禁止字符时生成空文件名。
        return filename.isEmpty ? "EchoCards" : filename
    }

    // 用户可复制给 AI 的提示词。
    // 内容严格使用当前 JSON 导入器支持的单卡组格式。
    private static let aiImportPrompt = """
    请把我接下来提供的学习材料整理为知声卡可导入的 JSON 文件。

    请直接生成一个可下载的 UTF-8 .json 文件，不要使用 Markdown 代码块，不要添加解释文字。
    如果当前环境无法生成下载文件，请只输出有效 JSON，方便我复制到知声卡的“从剪贴板导入 JSON”功能。
    一个 JSON 文件只能包含一个卡组，必须使用以下结构：

    {
      "title": "卡组名称",
      "description": "卡组说明（可省略）",
      "cards": [
        {
          "title": "卡片标题",
          "content": "卡片正文",
          "speechText": "朗读文本（可省略）",
          "memoryTip": "快速记忆点（可省略）"
        }
      ]
    }

    生成规则：
    1. title 和 cards 必填；cards 数量必须为 1 到 1000。
    2. 每张卡片的 title 和 content 必填，且不能是空字符串。
    3. description、speechText、memoryTip 没有内容时请直接省略字段。
    4. 保持 cards 数组顺序，不要输出 ID、时间戳、学习进度、记录或其他字段。
    5. 输出 UTF-8 JSON，确保可以直接保存为 .json 文件后导入。

    学习材料：
    """

}

// DeckCardView 只负责“一行卡组”的显示和用户操作
// 它不持有数据库状态；点击后的业务操作通过闭包交给 DecksPage
private struct DeckCardView: View {
    let deck: Deck
    let onEdit: () -> Void
    let onExport: () -> Void
    let onDelete: () -> Void

  var body: some View {
  NavigationLink {
    // 点击卡片进入现有的卡组详情页。
    DeckDetailView(deck: deck)
  } label: {
    VStack(alignment: .leading, spacing: 16) {

      HStack(alignment: .top) {
        // 卡组图标区域。
        Image(systemName: "rectangle.stack.fill")
          .font(.title2)
          .foregroundStyle(.tint)
          .frame(width: 42, height: 42)
          .background(.tint.opacity(0.12))
          .clipShape(RoundedRectangle(cornerRadius: 12))

        Spacer()

        // 保留右键菜单外也可见的展开提示。
        Image(systemName: "arrow.up.right")
          .foregroundStyle(.tertiary)
      }

      VStack(alignment: .leading, spacing: 6) {
        Text(deck.title)
          .font(.headline)
          .foregroundStyle(.primary)
          .lineLimit(1)

        // 没有说明时保留固定高度，让卡片标题对齐。
        Text(deck.description?.isEmpty == false
             ? deck.description!
             : "点击查看和管理卡片")
          .font(.callout)
          .foregroundStyle(.secondary)
          .lineLimit(2)
          .frame(maxHeight: 42, alignment: .top)
      }

      Spacer(minLength: 0)

      // 这里暂时不显示虚假的数量或进度。
      // 后面接入卡片统计数据后，再替换为真实内容。
      Text("打开卡组")
        .font(.caption)
        .fontWeight(.medium)
        .foregroundStyle(.tint)
    }
    .padding(20)
    .frame(maxWidth: .infinity, minHeight: 180, alignment: .leading)
    .background(.background)
    .clipShape(RoundedRectangle(cornerRadius: 20))
    .overlay {
      RoundedRectangle(cornerRadius: 20)
        .stroke(.quaternary, lineWidth: 1)
    }
  }
  .buttonStyle(.plain)
  .contextMenu {
    Button("编辑卡组", action: onEdit)

    Divider()

    Button("导出 JSON", action: onExport)

    Divider()

    Button("删除卡组", role: .destructive, action: onDelete)
  }
}

}
