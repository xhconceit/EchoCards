import Foundation

// 卡片编辑的业务错误
// 新建和编辑卡片都可以使用它
enum CardEditingError: LocalizedError {
  case emptyTitle
  case emptyContent

  // LocalizedError 提供可显示给用户的错误说明
  var errorDescription: String? {
    switch self {
    case .emptyTitle:
      return "卡片标题不能为空"
    case .emptyContent:
      return "卡片正文不能为空"
    }
  }
}
