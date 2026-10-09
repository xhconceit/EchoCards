import Foundation


// Codable  等于 Decodable + Encodable
//
// Decodable 可以从 JSON 读取
// Encodable 可以转换成 JSON
struct DeckImportDocument: Codable, Sendable {
  /// JSON 中的卡组名称
  let title: String

  // 卡组说明允许缺失或者为 null
  let description: String?

  // cards 时必填数组
  //
  // 如果 JSON 中缺少 cards
  // JSONDecoder 会直接报告解码失败
  let cards: [CardImportDocument]
}

// CardImportDocument
struct CardImportDocument: Codable, Sendable {
  // 卡片标题
  let title: String
  // 正文
  let content: String
  // 朗读文本
  let speechText: String?
  // 快速记忆点
  let memoryTip: String?
}
