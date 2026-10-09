import Foundation

// DeckImportResult 表示一次导入成功后
// 创建出来的完整领域数据
//
// 一个导入文件只产生一个Deck
// 但可以产生多张 Card
struct DeckImportResult {
  // 正式卡组模型
  let deck: Deck
  // 属于这个卡组的全部卡片
  let cards: [Card]
}

// DeckImportMapper 负责把 JSON 临时模型
// 转换成 App 内部使用的正式领域模型
//
// Mapper 可以理解为“数据转换器”
struct DeckImportMapper {
  // document: 已经解码并校验成功的 JSON 数据
  //
  // deckPosition: 新卡组在卡组列表中的位置
  //
  // -> DeckImportResult 表示这个函数会返回
  // 一个 DeckImportResult
  func map(
    document: DeckImportDocument,
    deckPosition: Int
  ) -> DeckImportResult {
    // 一个卡组和它的全部卡片
    // 必须使用同一个 deckID
    let deckID = UUID()

    // 让本次导入创建的数据使用同一时间
    //
    // 如果每创建一张卡片都调用一次 Date()
    // 每张卡片的时间会有细微差别
    let now = Date()

    // 创建正式卡组
    let deck = Deck(
      // 使用上面生成的唯一 ID
      id: deckID,
      // document 已经经过 Validator 清理
      // 所以名称不会为空
      title: document.title,
      // 说明可能是 nil
      description: document.description,
      // 新卡组在列表中的排序位置
      position: deckPosition,
      // 创建时间和更新时间暂时相同
      createdAt: now,
      updatedAt: now,
      // 导入指纹下一步再计算
      importFingerprint: DeckImportFingerprint().make(for: document)
    )

    // enumerated() 会同时提供
    //
    // 1. position: 元素在数组中的位置
    // 2. importedCard: 当前位置的卡片
    //
    // map 会把 JSON 卡片数组转换成正式 Card 数组
    let cards = document.cards.enumerated().map {
      position, importedCard in
      Card(
        // 每个卡片生成自己的唯一 ID
        id: UUID(),
        // 所有卡片都关联到刚才创建的卡组
        deckID: deckID,
        title: importedCard.title,
        content: importedCard.title,
        speechText: importedCard.speechText,
        memoryTip: importedCard.memoryTip,
        // JSON 数组顺序就是卡片顺序。
        // 第一张是 0，第二张是 1
        position: position,
        // 新创建的卡片版本从 1 开始
        revision: 1,
        createdAt: now,
        updatedAt: now
      )
    }

    // 把创建好的卡组和卡片一起返回
    return DeckImportResult(
      deck: deck,
      cards: cards
    )
  }
}
