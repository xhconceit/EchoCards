import Foundation
import Observation

// AppModel 保存整个应用共同使用的状态
//
// 主窗口和浮动窗口会观察同一个 AppModel
// 当主窗口切换 selectedDeckID 时，浮动窗口会自动读取新卡组
@Observable
@MainActor
final class AppModel {

  // 负责生成 Android 兼容 JSON 的导出服务
  private let deckExportService: DeckExportService

  // 负责保存上次选择的卡组
  private let sessionStore: AppSessionStore
  // 当前选择的卡组 ID
  //
  // didSet 会在值发生修改后保存到本机
  private(set) var selectedDeckID: UUID? {
    didSet {
      sessionStore.saveSelectedDeckID(
        selectedDeckID
      )
    }
  }

  // 记录浮动窗口当前是否打开状态
  //
  // 这不是窗口本身，而是应用对窗口状态的记录
  private(set) var isFloatingWindowOpen: Bool

  // 当前已经保存的全部卡组
  //
  // private(set) 表示:
  //
  // 1. MainView 可以读取 decks
  // 2. 只有 AppModel 自己可以修改 decks
  //
  // 这样 View 就不能绕过业务逻辑直接修改数据
  private(set) var decks: [Deck]

  // 卡组数据仓库
  //
  // AppModel 只依赖 DeckRepository 接口
  // 不需要知道当前使用的是内存还是 SQLite
  private let deckRepository: any DeckRepository

  // 负责执行完整卡组导入流程的业务服务
  private let deckImportService: DeckImportService

  // 当前用户设置
  //
  // didSet 会在 settings 被修改后执行
  // Slider Toggle 修改其中任何字段
  // 都会自动保存整个 UserSettings
  var settings: UserSettings {
    didSet {
      settingsStore.save(settings)
    }
  }

  // 负责读取和保存用户设置
  //
  // View 不需要知道设置具体保存在哪里
  private let settingsStore: UserSettingsStore

  init(
    selectedDeckID: UUID? = nil,
    isFloatingWindowOpen: Bool = false,
    settings: UserSettings? = nil,
    settingsStore: UserSettingsStore = UserSettingsStore(),
    sessionStore: AppSessionStore = AppSessionStore(),
    // 当前阶段默认使用内存仓库。
    //
    // 后面实现 SQLiteDeckRepository 后，
    // 只需要替换这里传入的对象。
    deckRepository: any DeckRepository =
      InMemoryDeckRepository(),
  ) {
    // 必须初始化 sessionStore
    // 才能使用它读取卡组 ID
    self.sessionStore = sessionStore

    self.selectedDeckID = selectedDeckID
    self.isFloatingWindowOpen = isFloatingWindowOpen
    self.settingsStore = settingsStore
    // 如果调用者传入设置，就使用传入值；
    // 否则读取上一次保存在本机的设置。
    self.settings = settings ?? settingsStore.load()
    // App 启动时先使用空卡组列表
    //
    // 后面通过 loadDecks() 从仓库中读取
    self.decks = []

    // 保存传入的仓库对象。
    self.deckRepository = deckRepository

    // 导入服务和 AppModel 必须使用同一个仓库。
    //
    // 如果这里创建另一个仓库，
    // 导入服务保存的数据就无法被 AppModel 读取。
    self.deckImportService = DeckImportService(
      repository: deckRepository
    )

    // 负责生成 Android 兼容 JSON 的导出服务
    self.deckExportService = DeckExportService(
      repository: deckRepository
    )

  }

  // 选择一个卡组作为当前学习卡组
  //
  // 如果浮动窗口已经存在，它会切换到这个卡组
  // 如果尚未打开，窗口协调器会根据这个状态创建窗口
  func selectDeck(_ deckID: UUID) {
    selectedDeckID = deckID
  }

  // 在浮动窗口成功打开后更新状态
  func markFloatingWindowOpen() {
    isFloatingWindowOpen = true
  }

  // 在浮动窗口关闭后更新状态
  //
  // 关闭窗口不会清楚 selectedDeckID
  // 因全面快捷键仍然可以重新打开当前卡组
  func markFloatingWindowClosed() {
    isFloatingWindowOpen = false
  }

  // 当前卡组被删除时清除选择
  func clearSelectedDeck() {
    selectedDeckID = nil
    isFloatingWindowOpen = false
  }

  // // 把所有用户设置恢复为应用默认值。
  //
  // settings 被重新赋值后会触发 didSet，
  // 因此默认设置也会自动保存到本机。
  func resetSettings() {
    settings = .defaults
  }

  // 从仓库重新读取全部卡组
  // 并更新 SwiftUI 正在观察的 decks
  func loadDecks() async throws {
    let loadedDecks = try await deckRepository.fetchDecks()
    // AppModel 标记了 @MainActor
    // 所以这里修改 decks 是主线程是安全的
    decks = loadedDecks

    // 如果保存的卡组 ID 已经不存在
    // 就清除无效选择
    if let selectedDeckID {
      let stillExits = loadedDecks.contains { deck in deck.id == selectedDeckID }
      if !stillExits {
        clearSelectedDeck()
      }
    }
  }

  // 把已经读取并校验成功的 JSON 文档
  // 导入到当前卡组仓库
  func importDeck(
    _ document: DeckImportDocument
  ) async throws -> DeckImportOutcome {
    // 调用业务服务完成
    //
    // 1. 计算指纹
    // 2. 检测重复
    // 3. 创建领域
    // 4. 保存卡组和卡片
    let outcome = try await deckImportService.importDocument(document)

    // 导入结束后重新读取卡组列表
    // 让主窗口立即显示最新数据
    try await loadDecks()

    // 无论是已有卡组还是新卡组
    // 都把它设为当前选中的学习卡组
    selectDeck(outcome.deck.id)

    // 把结果返回给 MainView
    // 让界面显示 “新建成功” 或者 已经存在
    return outcome
  }

  // 读取当前选中卡组的全部卡片
  func fetchSelectedDeckCards() async throws -> [Card] {
    // selectedDeckID 时 UUID？，可能还没有选择卡组
    //
    // guard let 会把 UUID? 解包为真正的 UUID
    guard let selectedDeckID else {
      // 没有选中卡组时返回空数组
      return []
    }

    // 复用通用读取方法，避免重复访问 Repository。
    return try await fetchCards(deckID: selectedDeckID)
  }

  // 删除指定卡组
  //
  // MainView 不直接调用 Repository
  // 而是统一通过 AppModel 执行业务操作
  func deleteDeck(
    id deckID: UUID
  ) async throws {
    // 先记录被删除的是否是当前卡组
    let isDeletingSelectedDeck = selectedDeckID == deckID

    // 等待 Repository 删除卡组
    //
    // SQLite 会通过 ON DELETE CASCADE
    // 自动删除这个卡组所属的卡片
    try await deckRepository.deleteDeck(id: deckID)

    //如果删除的是当前学习卡组
    // 清除当前选择
    //
    // selectedDeckID 变成 nil 后
    // FloatingStudyView 的 task(id:) 会重新执行
    // 并把学习卡片清空
    if isDeletingSelectedDeck {
      clearSelectedDeck()
    }
    // 删除成功后重新读取卡片列表
    // 让主界面立即刷新
    try await loadDecks()
  }

  // 生成指定卡组的 JSON 文件内容
  func exportDeck(
    _ deck: Deck
  ) async throws -> Data {
    try await deckExportService.exportDeck(deck)
  }

  // 读取指定卡组中的全部卡片
  //
  // 详情页可以读取任意卡组
  // 不要求它必须是当前浮动学习卡组
  func fetchCards(deckID: UUID) async throws -> [Card] {
    try await deckRepository.fetchCards(deckID: deckID)
  }

  // 修改卡组名称和说明
  func updateDeck(
    _ deck: Deck,
    title: String,
    description: String
  ) async throws {
    // 清理用户输入的首尾空格和换行
    let cleanedTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)

    // 复用已有的空标题错误
    guard !cleanedTitle.isEmpty else {
      throw DeckImportError.emptyDeckTitle
    }

    let cleanedDescription = description.trimmingCharacters(in: .whitespacesAndNewlines)

    // Desk 是 struct 赋值后得到一个独立的值
    //
    // 使用 var 才能修改这个副本中的属性
    var updatedDeck = deck
    updatedDeck.title = cleanedTitle

    // 三元表达式
    // 条件成立使用 nil 否则使用清理后的说明
    updatedDeck.description =
      cleanedDescription.isEmpty
      ? nil
      : cleanedDescription

    updatedDeck.updatedAt = Date()

    // 等待仓库保存修改
    try await deckRepository.updateDeck(updatedDeck)

    // 重新读取列表 让主窗口显示最新名称和说明
    try await loadDecks()
  }

  // 向指定卡组添加一张新卡片
  //
  // 界面只传入用户填写的内容
  // UUID 排序位置  版本号 和时间这里生成
  func createCard(
    deckID: UUID,
    title: String,
    content: String,
    speechText: String,
    memoryTip: String
  ) async throws {
    // trimmingCharacters 删除首尾空格和换行
    // 不会删除正文中间的空格
    let cleanedTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)

    let cleanedContent = content.trimmingCharacters(in: .whitespacesAndNewlines)

    // 标题和正文必须有实际内容
    guard !cleanedTitle.isEmpty else {
      throw CardEditingError.emptyTitle
    }

    guard !cleanedContent.isEmpty else {
      throw CardEditingError.emptyContent
    }

    // 读取已有卡片，计算新卡片的排序位置
    let existingCards = try await deckRepository.fetchCards(deckID: deckID)

    // 找到最大的 position 新卡片排在它后面
    //
    // 空数组的 max() 返回 nil
    // (nil ?? -1)
    // (nil ?? -1) + 1 得到第一张的位置 0。

    let nextPosition = (existingCards.map { $0.position }.max() ?? -1) + 1

    // $0 表示闭包的第一个参数
    //
    // 上面的 map { $0.position } 等价于
    // map { card in card.position }

    let cleanedSpeechText = speechText.trimmingCharacters(
      in: .whitespacesAndNewlines
    )

    let cleanedMemoryTip = memoryTip.trimmingCharacters(in: .whitespacesAndNewlines)

    // 本次创建使用同一时间
    let now = Date()

    let card = Card(
      id: UUID(),
      deckID: deckID,
      title: cleanedTitle,
      content: cleanedContent,
      // 三元表达式 条件 ？ 成立时的值 ： 不成立的字
      // 可选字段为空保存 nil 避免保存无意义的空字符串
      speechText: cleanedSpeechText.isEmpty ? nil : cleanedSpeechText,
      memoryTip: cleanedMemoryTip.isEmpty ? nil : cleanedMemoryTip,
      position: nextPosition,
      revision: 1,
      createdAt: now,
      updatedAt: now
    )

    // 等待窗口把卡片保存到SQLite
    try await deckRepository.insertCard(card)
  }

}
