import Foundation

// 导入过程中出现的业务错误
// LocalizedError 允许界面直接读取 errorDescription
// 向用户显示可以理解的中文错误
enum DeckImportError: LocalizedError {
  case emptyDeckTitle
  case invalidCardCount
  case emptyCardTitle(position: Int)
  case emptyCardContent(position: Int)
  case fileTooLarge
  case cannotReadFile
  case invalidJSON

  var errorDescription: String? {
    switch self {
    case .emptyDeckTitle:
      return "卡组名称不能为空"
    case .invalidCardCount:
      return "每个卡组必须包含 1 到 1000 张卡片"
    case .emptyCardTitle(let position):
      return "第 \(position + 1) 张卡片的标题不能为空"
    case .emptyCardContent(let position):
      return "第 \(position + 1) 张卡片的正文不能为空"
    case .fileTooLarge:
      return "导出文件不能超过 2 Mib"
    case .cannotReadFile:
      return "无法读取所选择的文件"

    case .invalidJSON:
      return "文件不是有效的知声卡 JSON 格式"

    }
  }
}

// DeckImportValidator 负责校验和规范化
// 已经通过 JSONDecoder 解码的临时数据
struct DeckImportValidator {
  // 校验导入内容，并返回清理后的数据
  func validate(
    _ document: DeckImportDocument
  ) throws -> DeckImportDocument {
    // 删除卡组名称首尾的空格和换行
    let title = trimmed(document.title)
    guard !title.isEmpty else {
      throw DeckImportError.emptyDeckTitle
    }

    // 一个卡组必须包含 1 到 1000 张卡片
    guard (1...1000).contains(document.cards.count) else {
      throw DeckImportError.invalidCardCount
    }

    // 校验并清理每一张卡片
    let cards = try document.cards.enumerated().map {
      position, card in
      let title = trimmed(card.title)
      let content = trimmed(card.content)

      guard !title.isEmpty else {
        throw DeckImportError.emptyCardTitle(position: position)
      }

      guard !content.isEmpty else {
        throw DeckImportError.emptyCardContent(position: position)
      }

      return CardImportDocument(
        title: title,
        content: content,
        speechText: normalizedOptional(card.speechText),
        memoryTip: normalizedOptional(card.memoryTip)
      )
    }

    return DeckImportDocument(
      title: title,
      // 空说明也统一转换成 nil
      description: normalizedOptional(document.description),
      cards: cards
    )

  }

  // 删除字符串首尾的空格和换行
  private func trimmed(_ value: String) -> String {
    value.trimmingCharacters(in: .whitespacesAndNewlines)
  }

  // 清理可选字符串
  //
  // nil, 空字符串和只有空格的字符串
  // 最终都返回 nil
  private func normalizedOptional(_ value: String?) -> String? {
    guard let value else {
      return nil
    }
    let result = trimmed(value)

    return result.isEmpty ? nil : result
  }
}
