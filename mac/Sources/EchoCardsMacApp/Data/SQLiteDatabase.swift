import Foundation
// SQLite3 是 macOS 系统自带的 SQLite C API
//
// 它提供 sqlite3_open_v2 , sqlite3_exec
// sqlite3_close 等底层函数
import SQLite3

// SQLite 操作可能产生的错误
enum SQLiteDatabaseError: LocalizedError {

  // 打开数据库失败
  case cannotOpen(String)

  // 执行 SQL 失败
  case executionFailed(String)

  // 创建预编译 SQL 失败
  case cannotPrepare(String)

  // 给 SQL 参数绑定值失败
  case cannotBind(String)

  // 执行或读取预编译 SQL 失败
  case cannotStep(String)

  var errorDescription: String? {
    switch self {
    case .cannotOpen(let message):
      return "无法打开本地数据库: \(message)"
    case .executionFailed(let message):
      return "执行数据库操作失败：\(message)"
    case .cannotPrepare(let message):
      return "无法准备数据库操作：\(message)"
    case .cannotBind(let message):
      return "无法绑定数据库参数：\(message)"
    case .cannotStep(let message):
      return "无法执行数据库语句：\(message)"
    }
  }
}

// SQLiteDatabase 表示一个 SQLite 数据库连接
//
// 它只封装底层 SQLite 操作
// 不包含 Deck Card 等业务逻辑
final class SQLiteDatabase {

  // OpaquePointer 是 Swift 表示 C 语言不透明指针的类型
  //
  // Swift 不需要知道 sqlite3 内部结构
  // 只要保存并传回这个指针
  private var connection: OpaquePointer?

  // 打开指定位置的数据库文件
  init(url: URL) throws {
    // 临时接收 SQLite 创建的数据库连接
    var openedConnection: OpaquePointer?

    // sqlite3_open_v2 是 SQLite 提供的 C 函数
    //
    // url.path: 数据库文件在磁盘上的路径
    //
    // SQLITE_OPEN_READWRITE: 允许读写
    // SQLITE_OPEN_CREATE: 不存在时自动创建
    // SQLITE_OPEN_FULLMUTEX: 启用完整线程保护
    let result = sqlite3_open_v2(
      url.path,
      &openedConnection,
      SQLITE_OPEN_READWRITE
        | SQLITE_OPEN_CREATE
        | SQLITE_OPEN_FULLMUTEX,
      nil
    )

    // SQLITE_OK 表示操作成功
    guard result == SQLITE_OK else {
      // 如果 SQLite 创建了部分连接
      // 先读取其中的错误信息
      let message: String

      if let openedConnection {
        message = String(
          cString: sqlite3_errmsg(openedConnection)
        )

        // 打开失败时也要关闭临时连接
        // 防止数据库资源泄漏
        sqlite3_close(openedConnection)
      } else {
        message = "SQLite 没有返回数据库连接"
      }
      throw SQLiteDatabaseError.cannotOpen(message)
    }

    // 打开成功后保存连接
    connection = openedConnection

    // SQLite 默认不会自动启用外键约束
    //
    // 必须主动开启，删除 Deck 时
    // ON DELETE CASCADE 才会删除关联 Card
    try execute(
      "PRAGMA foreign_keys = ON;"
    )
  }

  // 执行一段不需要返回查询结果的 SQL
  //
  // 例如 CREATE TABLE，INSERT，UPDATE
  func execute(_ sql: String) throws {
    // 用来接收 SQLITE 返回的错误文字
    var errorMessagePointer: UnsafeMutablePointer<CChar>?

    let result = sqlite3_exec(connection, sql, nil, nil, &errorMessagePointer)

    guard result == SQLITE_OK else {
      // 如果 SQLite 提供了具体错误文字
      // 就把 C 字符串转换成 Swift String
      let message: String

      if let errorMessagePointer {
        message = String(cString: errorMessagePointer)

        // sqlite3_exec 返回错误的字符串
        // 由 SQLite 分配，使用后必须释放
        sqlite3_free(errorMessagePointer)
      } else {
        message = String(
          cString: sqlite3_errmsg(connection)
        )
      }
      throw SQLiteDatabaseError.executionFailed(message)
    }
  }

  // 把 SQL 编译成可以绑定参数和读取结果的语句
  func prepare(
    _ sql: String
  ) throws -> SQLiteStatement {
    try SQLiteStatement(
      connection: connection,
      sql: sql
    )
  }

  // deinit 会在SQLiteDatabase 对象销毁时执行
  //
  // 它类似于对象生命周期结束时的清理函数
  deinit {
    if let connection {
      // 关闭 SQLite 数据库连接
      sqlite3_close(connection)
    }
  }
}
