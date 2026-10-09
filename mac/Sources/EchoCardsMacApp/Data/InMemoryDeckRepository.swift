import Foundation

// 仓库写入时可能出现错误
enum DeckRepositoryError: LocalizedError {
  // 相同内容的卡组存在
  case duplicateImport

  var errorDescription: String? {
    switch self {
    case .duplicateImport:
      return "这个卡组已经导入过了"
    }
  }
}

// actor 是 Swift 提供的并发安全引用类型
//
// 它和 class 类似，可以保存并修改内部状态
// 区别是 actor 会保护内部数据
// 避免多个异步任务同时修改数组造成数据冲突
actor InMemoryDeckRepository: DeckRepository {
  // 保存所有卡组
  private var decks: [Deck]

  // 字典的键是卡组 UUID，值是该卡组的卡片数组
  //
  // [UUID: [Card]] 可以理解为：
  //
  // 卡组 ID -> 这个卡组的全部卡片
  private var cardsByDeckID: [UUID: [Card]]

  // 初始化内存仓库
  //
  // 参数提供默认值，所以正式运行时可以直接写
  //
  // InMemoryDeckRepository()
  //
  // 测试时也可以传入预先准备的数据
  init(
    decks: [Deck] = [],
    cardsByDeckID: [UUID: [Card]] = [:]
  ) {
    self.decks = decks
    self.cardsByDeckID = cardsByDeckID
  }

  // 读取所有卡组
  func fetchDecks() async throws -> [Deck] {
    // sorted 会返回一个新的排序后数组
    // 不会直接修改仓库内部的 decks
    decks.sorted { first, second in
      first.position < second.position
    }
  }

  // 根据导入指纹查找已有卡组
  func findDeck(
    importFingerprint: String
  ) async throws -> Deck? {
    // first(where:) 会从数组开头开始查找
    //
    // 找到符合条件的第一个 Deck 时返回它：
    // 全部不符合时返回 nil
    decks.first { deck in
      deck.importFingerprint == importFingerprint
    }
  }

  // 保存导入得到的卡组和卡片
  func saveImportedDeck(
    _ deck: Deck,
    cards: [Card]
  ) async throws {
    // 如果新卡组有导入指纹，就检查是否已经存在
    if let fingerprint = deck.importFingerprint {
      let existingDeck = decks.first { existingDeck in
        existingDeck.importFingerprint == fingerprint
      }

      // existingDeck != nil 表示找到了重复数据
      guard existingDeck == nil else {
        throw DeckRepositoryError.duplicateImport
      }
    }

    // actor 会保证下面两次修改不会被其他任务插入
    //
    // 因此对于这个内存实现来说
    // 保存 Deck 和 Cards 是一个完整操作
    decks.append(deck)
    cardsByDeckID[deck.id] = cards
  }

  // 读取指定卡组的全部卡片
  func fetchCards(
    deckID: UUID
  ) async throws -> [Card] {
    // 如果字典中没有这个 deckID
    // 使用 ?? 返回空数组
    //
    // ?? 称为 nil 合并运算符
    let cards = cardsByDeckID[deckID] ?? []
    // 按 position 保证卡片顺序正确
    return cards.sorted { first, second in
      first.position < second.position
    }
  }

  // 从内存中删除卡组及其卡片
  func deleteDeck(
    id: UUID
  ) async throws {
    // removeAll(where:) 会删除所有满足条件的元素
    //
    // 这里删除 id 等于目标 UUID 的卡组
    decks.removeAll { deck in deck.id == id }

    // 字典赋值为 nil 表示删除这个键和值
    //
    // 因此这个卡组的全部卡片数组也会被删除
    cardsByDeckID[id] = nil
  }

  func updateDeck(_ deck: Deck) async throws {
    // firstIndex 返回第一个符合条件的元素位置
    //
    // 没有找到时返回 nil 因此使用 guard let 解
    guard
      let index = decks.firstIndex(
        where: { existingDeck in existingDeck.id == deck.id }
      )
    else {
      // 目标卡组已经不存在时，结束操作
      return
    }

    // 替换数组中这个位置的卡组
    decks[index] = deck
  }

  func insertCard(_ card: Card) async throws {
      // 先取出这个卡组已有的卡片
      // 找不到时使用空数组
      var cards = cardsByDeckID[card.deckID] ?? []

      // 把新卡片添加到数组尾部
      cards.append(card)

      // 把修改后的数组写回字典
      cardsByDeckID[card.deckID] = cards
  }

}
