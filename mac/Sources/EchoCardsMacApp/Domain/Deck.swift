// 使用 UUID 和 Date 等基础类型
import Foundation

// Deck 表示一个卡组
//
// Identifiable：让 SwiftUI 可以通过 id 识别列表中的每个卡组。
// Equatable：允许使用 == 和 != 比较两个卡组。
// Sendable：允许卡组数据安全地跨 Swift 并发任务传递。
struct Deck: Identifiable, Equatable, Sendable {
  // 卡组唯一ID
  let id: UUID

  // 卡组名称
  var title: String

  // 卡组说明
  var description: String?

  // 卡组在列表中的排序位置，从 0 开始。
  var position: Int

  // 创建时间
  let createdAt: Date

  // 更新时间
  var updatedAt: Date

  // JSON 导入内容的 SHA-256
  var importFingerprint: String?

  // 创建一个 Deck

  init(
    // uuid
    id: UUID = UUID(),
    // 卡组名称
    title: String,
    // 卡组说明
    description: String? = nil,
    // 排序位置必须有调用方提供
    position: Int,
    // 新建卡组默认使用当前时间
    createdAt: Date = Date(),
    // 更新时间
    updatedAt: Date = Date(),
    // 导入
    importFingerprint: String? = nil
  ) {
    // Deck 属性
    self.id = id
    self.title = title
    self.description = description
    self.position = position
    self.createdAt = createdAt
    self.updatedAt = updatedAt
    self.importFingerprint = importFingerprint
  }
}
