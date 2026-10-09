import Foundation

// DeckExportService 负责把正式领域模型
// 转换成 Android 兼容的 JSON 数据
struct DeckExportService: Sendable {
  // 从仓库读取卡片
  private let repository: any DeckRepository

  init(
    repository: any DeckRepository
  ) {
    self.repository = repository
  }

  // 导出指定卡片
  //
  // Data 是 JSON 文件的二进制内容
  func exportDeck(
    _ deck: Deck
  ) async throws -> Data {
    // 从 Repository 读取这个卡组的全部卡片
    let cards = try await repository.fetchCards(deckID: deck.id)

    // 把正式 Card 转换成 JSON 卡片结构
    //
    // 只包含 Android 与 macOS 共同使用的字段
    // 不导出 UUID 时间 位置和版本号
    let exportCards = cards.map { card in
      CardImportDocument(
        title: card.title,
        content: card.content,
        speechText: card.speechText,
        memoryTip: card.memoryTip
      )
    }

    // 构造与导入格式完全相同的卡组文档
    let document = DeckImportDocument(
      title: deck.title,
      description: deck.description,
      cards: exportCards
    )

    let encoder = JSONEncoder()

    // prettyPrinted 输出带缩进和换行的 JSON
    // 方便用户直接查看
    //
    // sortedKeys 让字段顺序保持稳定
    //
    // withoutEscapingSlashes 斜杠不写成 \/
    encoder.outputFormatting = [
      .prettyPrinted,
      .sortedKeys,
      .withoutEscapingSlashes,
    ]

    // 把 Swift 模型编码成 UTF-8 JSON DATA
    return try encoder.encode(document)
  }
}
