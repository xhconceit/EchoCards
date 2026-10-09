import Foundation
import SQLite3

// SQLITE_TRANSIENT 告诉 SQLite:
//
// 绑定字符串时，SQLite 应该复制字符串内容
// 不要继续依赖 SWift 临时字符串的内存地址
private let sqliteTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

// SQLiteStatement 表示一条已经预编译的 SQL
//
// 例如：
//
// INSERT INTO decks (id, title)
// VALUES (?, ?)
//
// SQL 中的 ? 会通过 bindText 等方法赋值
final class SQLiteStatement {
  // SQLite 底层语句指针
  private var statement: OpaquePointer?

  // 创建并编译
  init(
    connection: OpaquePointer?,
    sql: String
  ) throws {
    var preparedStatement: OpaquePointer?

    // sqlite3_prepare_v2 把 SQL 字符串
    // 编译成 SQLite 可以执行的语句
    let result = sqlite3_prepare_v2(
      connection,
      sql,

      // -1 表示 SQL 字符串以结束符为准
      -1,

      // SQLite 把生成的语句写入这个变量
      &preparedStatement,
      nil
    )

    guard result == SQLITE_OK else {
      let message = String(
        cString: sqlite3_errmsg(connection)
      )

      throw SQLiteDatabaseError.cannotPrepare(message)
    }

    statement = preparedStatement
  }

  // 把可选字符串绑定到指定参数位置
  //
  // SQLite 参数位置从 1 开始，不是从 0 开始
  func bindText(
    _ value: String?,
    at index: Int32
  ) throws {
    let result: Int32

    if let value {
      // value.withCString 临时把 Swift String
      // 转换为 SQLite 使用的 C 字符串

      result = value.withCString { pointer in
        sqlite3_bind_text(
          statement,
          index,
          pointer,
          -1,
          sqliteTransient
        )
      }
    } else {
      // Swift 的 nil 在 SQLite 中对应 NULL
      result = sqlite3_bind_null(statement, index)
    }

    try checkBindingResult(result)
  }

  // 绑定参数
  func bindInt(
    _ value: Int,
    at index: Int32
  ) throws {
    let result = sqlite3_bind_int64(
      statement,
      index,
      sqlite3_int64(value)
    )

    try checkBindingResult(result)
  }

  // 执行一次语句
  //
  // 返回 true：查询得到一行数据
  // 返回 false: 执行结束，没有更多数据
  func step() throws -> Bool {
    let result = sqlite3_step(statement)

    switch result {
    case SQLITE_ROW:
      // SELECT 查询取得了一行结果
      return true
    case SQLITE_DONE:
      // INSERT UPDATE 已经完成
      // 或 SELECT 已经没有下一行
      return false
    default:
      let message = String(
        cString: sqlite3_errmsg(sqlite3_db_handle(statement))
      )

      throw SQLiteDatabaseError.cannotStep(message)
    }
  }

  // 读取当前结果行中的可选文字
  //
  // 查询结果的列位置从 0 开始
  func columnText(
    at index: Int32
  ) -> String? {
    guard let textPointer = sqlite3_column_text(statement, index) else {
      // SQL NUll 转换成 Swift nil
      return nil
    }

    // sqlite3_column_text 返回无符号字节指针
    // 这里转换成 Swift String 需要的 CChar 指针
    let characterPointer = UnsafeRawPointer(textPointer)
      .assumingMemoryBound(to: CChar.self)

    return String(cString: characterPointer)
  }

  // 读取当前结果行中的整数
  func columnInt(
    at index: Int32
  ) -> Int {
    Int(
      sqlite3_column_int64(statement, index)
    )
  }

  // 统一检查参数绑定是否成功
  private func checkBindingResult(
    _ result: Int32
  ) throws {
    guard result == SQLITE_OK else {
      let message = String(
        cString: sqlite3_errmsg(sqlite3_db_handle(statement))
      )
      throw SQLiteDatabaseError.cannotBind(message)
    }
  }

  // SQLiteStatement 销毁时释放预编译语句
  deinit {
    if let statement {
      sqlite3_finalize(statement)
    }
  }
}
