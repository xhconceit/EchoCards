import Foundation

// DeckImportOutcome 表示一次导入最终产生的结果
//
// enum 不仅可以表示不同状态
// 每个状态还可以携带对应的数据
enum DeckImportOutcome {
  // 找到了内容完全相同的已有卡组
  case existing(Deck)

  // 创建并保存了一个新卡组
  case created(Deck)

  // 无论结果是已有还有新建
  // 都可以通过这个属性取得最终卡组
  var deck: Deck {
    switch self {
    case .existing(let deck):
      return deck
    case .created(let deck):
      return deck
    }
  }
}

// DeckImportService 负责完整的导入业务流程
//
// 它不关心仓库底层是内存还是 SQLite
// 只依赖 DeckRepository 定义的能力
struct DeckImportService: Sendable {
  // any DeckRepository 表示
  //
  // 这里可以保存任何符合 DeckRepository
  // 接口要求的具体对象
  private let repository: any DeckRepository

  // 通过初始化参数把仓库传进来
  //
  // 这种方式称为依赖注入
  init(repository: any DeckRepository) {
    self.repository = repository
  }

  // 导入一个已经解码并校验过的文档
  //
  // async 里面需要等待仓库查询和保存
  // throws 仓库操作失败继续向上抛错误
  func importDocument(
    _ document: DeckImportDocument
  ) async throws -> DeckImportOutcome {
    // 根据卡组内容计算稳定的 SHA-256 指纹
    let fingerprint = DeckImportFingerprint().make(for: document)

    // await 表示等待异步查询完成
    //
    // 如果找到相同指纹的卡组
    // 不创建重复数据，直接返回已有卡组
    if let existingDeck = try await repository.findDeck(
      importFingerprint: fingerprint
    ) {
      return .existing(existingDeck)
    }

    // 读取当前全部卡组
    // 用于确定新卡组的列表位置
    let existingDecks = try await repository.fetchDecks()

    // 找出目前最大的 position
    //
    // max() 的结果是 Int?
    // 因为空数组没有最大值
    let largestPosition = existingDecks.map { deck in
      deck.position
    }
    .max()

    // 如果还没有卡组，largestPosition
    //
    // ?? -1 表示 nil 时使用 -1
    // 然后加 1 得到第一个位置 0
    let newPosition = (largestPosition ?? -1) + 1

    // 把导入文档转换成正式的 Deck 和 Card
    let importResult = DeckImportMapper().map(
      document: document,
      deckPosition: newPosition
    )

    // 等待仓库保存卡组和全部卡片
    //
    // SQLite 实现中，这一步会放在事务里
    try await repository.saveImportedDeck(
      importResult.deck,
      cards: importResult.cards
    )

    // 告诉调用方这是刚刚创建的新卡组
    return .created(importResult.deck)
  }
}
