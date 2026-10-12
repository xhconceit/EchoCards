import Foundation

// DeckFileImporter 负责
// 1. 检查文件大小
// 2. 读取文件数据
// 3. 解码 JSON
// 4. 执行业务校验
struct DeckFileImporter {
  // 2 MiB 的字节数
  private let maximumFileSize = 2 * 1024 * 1024
  // 复用之前创建的内容
  private let validator = DeckImportValidator()

  // 从用户选择的文件 URL 中读取卡组
  func load(
    from url: URL
  ) throws -> DeckImportDocument {
    // 先读取文件源数据
    //
    // 如果系统能提供文件大小，就在完整文件前
    // 拒绝超过限制的文件
    if let fileSize = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize {
      guard fileSize <= maximumFileSize else {
        throw DeckImportError.fileTooLarge
      }
    }

    let data: Data

    do {
      // 把文件内容读取成二进制数据。
      data = try Data(
        contentsOf: url,
        options: .mappedIfSafe
      )
    } catch {
      throw DeckImportError.cannotReadFile
    }

    return try load(from: data)
  }

  // 从内存中的 JSON 数据读取卡组。
  // 剪贴板导入和文件导入共用相同的解码与校验规则。
  func load(
    from data: Data
  ) throws -> DeckImportDocument {
    // 即使数据不是来自文件，也要限制最大导入大小。
    guard data.count <= maximumFileSize else {
      throw DeckImportError.fileTooLarge
    }

    let document: DeckImportDocument
    do {
      // JSONDecoder 默认要求输入是否有效 JSON
      //
      // 必填字段缺失或字段类型错误也会在这里失败
      document = try JSONDecoder().decode(
        DeckImportDocument.self,
        from: data
      )
    } catch {
      throw DeckImportError.invalidJSON
    }

    /// 解码成功后继续检查空标题 空正文
    // 以及卡片数量等业务规则
    return try validator.validate(document)
  }
}
