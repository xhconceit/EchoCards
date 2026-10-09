// 使用 UUID， Date 和字符串清理功能
import Foundation

// Card 卡组中的一张卡片
// Identifiable: 让 SwiftUI 列表通过 Id 识别
// Equatable： 允许比较两张卡片
// Sendable: ：允许卡片安全地跨并发任务传递。
struct Card: Identifiable, Equatable, Sendable {
  // id
  let id: UUID
  // 卡组id
  let deckID: UUID
  // 标题
  var title: String
  // 卡片正文
  var content: String
  // 朗读文本
  var speechText: String?

  // 快速记忆点
  var memoryTip: String?

  // 卡片在所属组中的排序位置
  var position: Int

  // 卡片内容版本
  var revision: Int

  // 卡片创建
  let createdAt: Date

  // 卡片更新时间

    var updatedAt: Date
  init(
    // 卡片ID
    id: UUID = UUID(),
    // 卡组ID
    deckID: UUID,
    title: String,
    content: String,
    speechText: String? = nil,
    memoryTip: String? = nil,
    position: Int,
    revision: Int = 1,
    createdAt: Date = Date(),
    updatedAt: Date = Date()
  ) {
    self.id = id
    self.deckID = deckID
    self.title = title
    self.content = content
    self.speechText = speechText
    self.memoryTip = memoryTip
    self.position = position
    self.revision = revision
    self.createdAt = createdAt
    self.updatedAt = updatedAt
  }

  // 返回实际用朗读和跟读匹配的文字
  //
  // speechText 去除首尾空格后有内容是优先使用
  // 否则使用正文 content
  var speechTarget: String {
    let trimmedSpeechText = speechText?.trimmingCharacters(in: .whitespacesAndNewlines)

    if let trimmedSpeechText, !trimmedSpeechText.isEmpty {
      return trimmedSpeechText
    }

    return content.trimmingCharacters(in: .whitespacesAndNewlines)
  }

  // 返回浮动窗口背面应该显示的内容
  //
  // memoryTip 为空或者只有空格时 显示统一占位文字
  var memoryTipText: String {
    let trimmedMemoryTip = memoryTip?.trimmingCharacters(in: .whitespacesAndNewlines)

    if let trimmedMemoryTip, !trimmedMemoryTip.isEmpty {
      return trimmedMemoryTip
    }
    return "暂无快速记忆点"
  }
}
