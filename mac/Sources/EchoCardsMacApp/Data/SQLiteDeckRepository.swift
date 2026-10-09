import Foundation

// SQLite 中的数据损坏或格式不符号
enum SQLiteDeckRepositoryError: LocalizedError {
  // 数据库中的某个字段无法转换成领域模型
  case invalidStoredData(String)

  var errorDescription: String? {
    switch self {
    case .invalidStoredData(let message):
      return "本地卡组数据无效: \(message)"
    }
  }
}

// SQLiteDeckRepository 负责在 SQLite 与
// Deck Card 领域模型之间进行转换
//
// actor 会串行保护同一个数据库连接
// 避免多个任务同时操作连接造成冲突
//
// 暂时不写： DeckRepository
// 因为我们还没有实现协议要求的全部方法
actor SQLiteDeckRepository: DeckRepository {
  // 底层 SQLite 数据库连接
  private let database: SQLiteDatabase

  // UUID 和 Date 在 SQLite 中都保存为字符串
  //
  // ISO8601DateFormatter 负责 Date 与
  // UTC ISO 8601 字符串之间的转换
  private let dateFormatter: ISO8601DateFormatter

  init(database: SQLiteDatabase) {
    self.database = database

    let formatter = ISO8601DateFormatter()

    // withInternetDateTime 产生标准 ISO 8601 时间
    //
    // withFractionalSeconds 保留毫秒等小数时间
    // 避免保存和读取后丢失精度
    formatter.formatOptions = [
      .withInternetDateTime,
      .withFractionalSeconds,
    ]

    self.dateFormatter = formatter
  }

  // 从 SQLite 读取全部卡组
  func fetchDecks() async throws -> [Deck] {
    // Select 后面的字读顺序很重要
    //
    // columnText(at:) 读取列时
    // 必须按照这里的顺序和位置读取
    let statement = try database.prepare(
      """
      SELECT
        id,
        title,
        description,
        position,
        created_at,
        updated_at,
        import_fingerprint
        FROM decks
        ORDER BY position ASC;
      """
    )

    // 用来收集查询得到的 Deck
    var decks: [Deck] = []

    // step() 返回 true 表示取得一行数据
    //
    // 每执行一次 step，SQLite 会移动到下一行

    while try statement.step() {
      let deck = try decodeDeck(from: statement)
      // append 把新元素添加到数组尾
      decks.append(deck)

    }

    return decks

  }

  // 根据导入指纹查找已有卡组
  func findDeck(
    importFingerprint: String
  ) async throws -> Deck? {
    let statement = try database.prepare(
      """
      SELECT
        id,
        title,
        description,
        position,
        created_at,
        updated_at,
        import_fingerprint
        FROM decks
        WHERE import_fingerprint = ?
        LIMIT 1;
      """)

    // SQL 中只有一个 ？
    // 使用绑定到 第一个参数位置
    try statement.bindText(importFingerprint, at: 1)

    // 找到结果是，step() 返回true
    // statement 当前就位于这条结果上
    if try statement.step() {
      return try decodeDeck(from: statement)
    }
    // 没有找到相同指纹返回 nil
    return nil
  }

  // 读取指定卡组的全部卡片
  func fetchCards(
    deckID: UUID
  ) async throws -> [Card] {
    let statement = try database.prepare(
      """
      SELECT
        id,
        deck_id,
        title,
        content,
        speech_text,
        memory_tip,
        position,
        revision,
        created_at,
        updated_at
        FROM cards
        WHERE deck_id = ?
        ORDER BY position ASC;
      """)

    // UUID 不能直接保存到 SQLite TEXT
    //
    // uuidString 会把 UUID 转换成稳定的字符串
    try statement.bindText(deckID.uuidString, at: 1)

    var cards: [Card] = []

    // 每次 step() 成功，就读取一张卡片
    while try statement.step() {
      let card = try decodeCard(
        from: statement
      )
      cards.append(card)
    }
    return cards
  }

  // 保存导入生成的卡组和全部卡片
  func saveImportedDeck(
    _ deck: Deck,
    cards: [Card]
  ) async throws {
    // 确认所有卡片都属于即将保存的卡组
    //
    // allSatisfy 会检查数组中的每个元素
    guard cards.allSatisfy({ card in card.deckID == deck.id }) else {
      throw SQLiteDeckRepositoryError.invalidStoredData("导入卡片关联了错误的卡组")
    }

    // 开始事务
    //
    // IMMEDIATE 表示立即取得写入权限
    // 避免执行到一半才发现数据库正在被其他操作写入
    try database.execute("BEGIN IMMEDIATE TRANSACTION;")

    do {
      // 先保存卡组
      try insertDeck(deck)

      // 再依次保存这个卡组的所有卡片
      for card in cards {
        try insertCardRow(card)
      }

      // 所有操作都成功后提交事务
      //
      // COMMIT 后数据才正式生效
      try database.execute("COMMIT;")

    } catch {
      // 中间任何一步失败时撤销整个事务
      //
      // try? 表示尝试回滚
      // 即使回滚本身失败，也继续抛出原始错误
      try? database.execute("ROLLBACK;")
      // 把原始错误继续交给调用方
      throw error
    }

  }

  // 从 SQLite 删除指定卡组
  func deleteDeck(id: UUID) async throws {
    let statement = try database.prepare(
      """
      DELETE FROM decks
      WHERE id = ?
      """)
    // 把目标卡组 UUID 绑定到第一个问号
    try statement.bindText(id.uuidString, at: 1)

    // 执行 DELETE
    _ = try statement.step()
  }

  func insertCard(
    _ card: Card
  ) async throws {
    // 调用已有的底层写入方法
    //
    // 这里只有一条 INSERT
    // SQLite 会自动保证这条语句的原子性
    try insertCardRow(card)
  }

  // 底层同步写入方法。
  //
  // 保存整个导入卡组和单独新建卡片
  // 都可以复用这个方法。
  private func insertDeck(
    _ deck: Deck
  ) throws {
    let statement = try database.prepare(
      """
      INSERT INTO decks (
          id,
          title,
          description,
          position,
          created_at,
          updated_at,
          import_fingerprint
      )
      VALUES (?, ?, ?, ?, ?, ?, ?)
      """)

    // SQL 参数位置从 1 开始
    // 顺序必须与 VALUES 中的问号一致
    try statement.bindText(deck.id.uuidString, at: 1)

    try statement.bindText(deck.title, at: 2)

    //   bindText 接收 String?，
    // nil 会自动保存成 SQL NULL。
    try statement.bindText(deck.description, at: 3)

    try statement.bindInt(deck.position, at: 4)

    // Date 先转换成 ISO 8601 字符串
    try statement.bindText(dateFormatter.string(from: deck.createdAt), at: 5)

    try statement.bindText(dateFormatter.string(from: deck.updatedAt), at: 6)

    try statement.bindText(deck.importFingerprint, at: 7)

    // INSERT 不会返回结果行
    //
    // 执行成功时 step() 返回 false
    // 这里不需要使用返回值
    _ = try statement.step()

  }

  // 向 cards 表插入一张卡片。
  private func insertCardRow(
    _ card: Card
  ) throws {
    let statement = try database.prepare(
      """
      INSERT INTO cards (
          id,
          deck_id,
          title,
          content,
          speech_text,
          memory_tip,
          position,
          revision,
          created_at,
          updated_at
      )
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
      """
    )

    try statement.bindText(
      card.id.uuidString,
      at: 1
    )

    try statement.bindText(
      card.deckID.uuidString,
      at: 2
    )

    try statement.bindText(
      card.title,
      at: 3
    )

    try statement.bindText(
      card.content,
      at: 4
    )

    try statement.bindText(
      card.speechText,
      at: 5
    )

    try statement.bindText(
      card.memoryTip,
      at: 6
    )

    try statement.bindInt(
      card.position,
      at: 7
    )

    try statement.bindInt(
      card.revision,
      at: 8
    )

    try statement.bindText(
      dateFormatter.string(
        from: card.createdAt
      ),
      at: 9
    )

    try statement.bindText(
      dateFormatter.string(
        from: card.updatedAt
      ),
      at: 10
    )

    _ = try statement.step()
  }

  // 把 SQLite 查询结果的当前行
  // 转换成一个正式的 Card
  private func decodeCard(
    from statement: SQLiteStatement
  ) throws -> Card {
    // 第 0 列：卡片 UUID
    guard let idText = statement.columnText(at: 0), let id = UUID(uuidString: idText) else {
      throw SQLiteDeckRepositoryError.invalidStoredData("卡片 ID 不是有效 UUID")
    }

    // 第 1 列：所属卡组 UUID
    guard let deckIDText = statement.columnText(at: 1), let deckID = UUID(uuidString: deckIDText)
    else {
      throw SQLiteDeckRepositoryError.invalidStoredData("卡片所属卡组 ID 有效")
    }

    // 第 2 列：卡片标题
    guard let title = statement.columnText(at: 2) else {
      throw SQLiteDeckRepositoryError.invalidStoredData("卡片标题为空")
    }

    // 第 3 列：卡片正文
    guard let content = statement.columnText(at: 3) else {
      throw SQLiteDeckRepositoryError.invalidStoredData("卡片正文为空")
    }

    // 第 4，5 列允许为 SQL NULL，
    // 因此结果类型是 String?
    let speechText = statement.columnText(at: 4)

    let memoryTip = statement.columnText(at: 5)

    // 第 6 列：卡片排序位置
    let position = statement.columnInt(at: 6)

    // 第 7 列：卡片内容版本
    let revision = statement.columnInt(at: 7)

    // 第8 列：创建时间
    guard let createdAtText = statement.columnText(at: 8),
      let createdAt = dateFormatter.date(from: createdAtText)
    else {
      throw SQLiteDeckRepositoryError.invalidStoredData("卡片创建时间无效")
    }

    // 第 9 列：更新时间
    guard let updatedAtText = statement.columnText(at: 9),
      let updatedAt = dateFormatter.date(from: updatedAtText)
    else {
      throw SQLiteDeckRepositoryError.invalidStoredData("卡片更新时间无效")
    }

    return Card(
      id: id,
      deckID: deckID,
      title: title,
      content: content,
      speechText: speechText,
      memoryTip: memoryTip,
      position: position,
      revision: revision,
      createdAt: createdAt,
      updatedAt: updatedAt
    )

  }

  // 把 SQLiteStatement 当前所在的结果行
  // 转换为一个正式的 Deck
  //
  // private 表示这个转换方法只供当前仓库使用
  private func decodeDeck(
    from statement: SQLiteStatement
  ) throws -> Deck {

    // SELECT 结果列从 0 开始
    //
    // id 是第一列，所以位置是 0
    guard let idText = statement.columnText(at: 0), let id = UUID(uuidString: idText) else {
      throw SQLiteDeckRepositoryError.invalidStoredData("卡组 ID 不是有效 UUID")
    }
    // title 在数据库中是 NOT NULL，
    // 正常情况下这里一定取得值
    guard let title = statement.columnText(at: 1) else {
      throw SQLiteDeckRepositoryError.invalidStoredData("卡组标题为空")
    }
    // description  允许为 SQL NULL，
    // 因此它自然转换为 String?。
    let description = statement.columnText(at: 2)

    // position 是第四列，列编号是 3
    let position = statement.columnInt(at: 3)
    // 读取创建时间字符串并转换为 Date
    guard let createdAtText = statement.columnText(at: 4),
      let createdAt = dateFormatter.date(from: createdAtText)
    else {
      throw SQLiteDeckRepositoryError.invalidStoredData("卡组创建时间无效")
    }

    // 读取更新时间
    guard let updatedAtText = statement.columnText(at: 5),
      let updatedAt = dateFormatter.date(from: updatedAtText)
    else {
      throw SQLiteDeckRepositoryError.invalidStoredData("卡组更新时间无效")
    }
    // 导入指纹允许是 NULL
    let importFingerprint = statement.columnText(at: 6)
    // 把当前 SQLite 行转换为正式 Deck
    return Deck(
      id: id,
      title: title,
      description: description,
      position: position,
      createdAt: createdAt,
      updatedAt: updatedAt,
      importFingerprint: importFingerprint
    )
  }

  func updateDeck(_ deck: Deck) async throws {
    let statement = try database.prepare(
      """
      UPDATE decks
      SET title = ?,
        description = ?,
        updated_at = ?
        WHERE id = ?
      """
    )

    // 第一个问号：新的卡组名称
    try statement.bindText(
      deck.title,
      at: 1
    )

    // 第二个问号：新的说明
    //
    // nil 会保存成 SQL NULL
    try statement.bindText(deck.description, at: 2)

    // 第三个问号：本次更新时间
    try statement.bindText(
      dateFormatter.string(
        from: deck.updatedAt
      ),
      at: 3
    )

    // 第四个问号：要修改的卡组 ID
    //
    // WHERE 条件很重要，否则会修改所有卡组
    try statement.bindText(
      deck.id.uuidString,
      at: 4
    )

    // 执行 UPDATE
    _ = try statement.step()
  }

}
