import Foundation

// AppDatabase 负责创建知声卡
//
// 它负责
// 1. 找到 macOs Application Support 目录
// 2. 创建知声卡自己的文件夹
// 3. 打开 SQLite 数据库文件
// 4. 创建第一版数据表
enum AppDatabase {
  // 创建并返回正式数据库连接
  //
  // static 表示调用这个函数时
  // 不需要先创建 AppDatabase 对象
  //
  // 调用方式：
  // let database = try AppDatabase.make()
  static func make() throws -> SQLiteDatabase {
    // FileManager.default 是系统提供的
    // 默认文件管理对象
    let fileManager = FileManager.default

    // 获取当前用户的 Application Support 目录
    //
    // 这里适合保存应用数据库等持久化数据
    let applicationSupportURL = try fileManager.url(
      for: .applicationSupportDirectory,
      // .userDomainMask 表示当前登陆用户的目录
      in: .userDomainMask,
      // 这里不需要参考其他目录
      appropriateFor: nil,
      // 目录不存在时允许系统创建
      create: true
    )

    // 在 Application Support 下面增加
    // 知声卡自己的子目录
    let appDirectoryURL = applicationSupportURL.appendingPathComponent(
      "EchoCards",
      isDirectory: true
    )

    // 创建目录
    //
    // withIntermediateDirectories: true 表示
    // 中间目录缺失也一起创建
    //
    // 如果目录已经存在，这个调用不会删除其中数据
    try fileManager.createDirectory(at: appDirectoryURL, withIntermediateDirectories: true)

    // 最终数据库文件路径
    let databaseURL = appDirectoryURL.appendingPathComponent(
      "EchoCards.sqlite3", isDirectory: false)

    // 打开数据库
    //
    // 文件不存在时，SQLiteDatabase 会自动创建
    let database = try SQLiteDatabase(
      url: databaseURL
    )

    // 创建第一版数据表
    try createSchema(in: database)
    return database
  }

  // 创建数据表和索引
  private static func createSchema(
    in database: SQLiteDatabase
  ) throws {
    // 三个双引号表示 Swift 多行字符串
    //
    // SQL 可以在里面正常换行
    // 不需要每一行都写引号和  \n
    let sql = """
        CREATE TABLE IF NOT EXISTS decks (
            id TEXT PRIMARY KEY NOT NULL,
            
            title TEXT NOT NULL CHECK (length(trim(title)) > 0),

            description TEXT,

            position INTEGER NOT NULL CHECK (position >= 0),

            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,

            import_fingerprint TEXT
        );

        CREATE UNIQUE INDEX IF NOT EXISTS
            idx_decks_import_fingerprint
        ON decks(import_fingerprint);

        CREATE INDEX IF NOT EXISTS
            idx_decks_import_fingerprint
        ON decks(position);

        CREATE TABLE IF NOT EXISTS cards (
            id TEXT PRIMARY KEY NOT NULL,

            deck_id TEXT NOT NULL,

            title TEXT NOT NULL
                CHECK (length(trim(title)) > 0),

            content TEXT NOT NULL
                CHECK (length(trim(content)) > 0),

            speech_text TEXT,
            memory_tip TEXT,

            position INTEGER NOT NULL
                CHECK (position >= 0),

            revision INTEGER NOT NULL
                CHECK (revision >= 1),

            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,

            FOREIGN KEY (deck_id)
                REFERENCES decks(id)
                ON DELETE CASCADE
        );

        CREATE INDEX IF NOT EXISTS
            idx_cards_deck_position
        ON cards(deck_id, position);

        PRAGMA user_version = 1;
      """

    // sqlite3_exec 支持一次执行多条
    // 用分号分隔
    try database.execute(sql)
  }
}
