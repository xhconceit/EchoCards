import Foundation

// DeckRepository 定义卡组数据存储必须提供的能力
//
// protocol 类似于一份“接口合同”
// 它只规定有那些函数，不在这里编写具体存储代码
// 后面可以有不同实现
//
// 1. InMemoryDeckRepository: 先把数据保存在内存中
// 2. SQLiteDeckRepository: 正式保存到 SQLite
// 3. 测试用假的 Repository
protocol DeckRepository: Sendable {
  // 读取全部卡组
  //
  // async 表示这个操作可能需要等待
  // 例如等待 SQLite 完成查询
  //
  // throws 表示查询过程中可能抛出错误
  func fetchDecks() async throws -> [Deck]

  // 根据导入指纹查找已经存在的卡组
  //
  // Deck? 中的 ？ 表示
  // 找到时返回 Deck
  // 没找到时返回 nil
  func findDeck(
    importFingerprint: String
  ) async throws -> Deck?

  // 保存一个卡组及其全部卡片
  //
  // 正式 SQLite 实现中，这个函数必须使用事务
  // Deck 和所有 Card 要么全部保存成功
  // 要么全部不保存
  func saveImportedDeck(
    _ deck: Deck,
    cards: [Card]
  ) async throws

  // 读取指定卡组中的全部卡片
  //
  // deckID 用来确定要读取那个卡组
  func fetchCards(
    deckID: UUID
  ) async throws -> [Card]

  // 删除指定卡组
  //
  // 数据库需要同时删除这个卡组所属的全部卡片
  func deleteDeck(
    id: UUID
  ) async throws

  // 更新已有卡组的信息
  //
  // 使用 deck.id 确定修改那个卡组
  // 保留卡片和原来的导入指纹
  func updateDeck(_ deck: Deck) async throws

  // 向已有卡组添加一张卡片
  func insertCard(
    _ card: Card
  ) async throws
}
