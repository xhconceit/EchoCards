import SwiftUI
// 提供 UTType.json 文件类型
import UniformTypeIdentifiers

// JSONExportDocumnent 表示一个等待保存的 JSON 文件
//
// FileDocument 是 Swift UI 的文件协议
// 遵守它以后，就能把对象传给 .fileExporter
struct JSONExportDocumnent: FileDocument {
  // 告诉系统这个文档使用 JSON 文件类型
  //
  // static 表示这个属性属于类型本身
  // 不属于某一个具体对象
  static var readableContentTypes: [UTType] {
    [.json]
  }

  // 真正要写入文件的 UTF-8 JSON 数据
  var data: Data

  // 使用已经生成的 JSON Data 创建文档
  init(data: Data) {
    self.data = data
  }

  // FileDocument 协议要求提供读取初始化方法
  //
  // 当前主要用于导出
  // 但协议仍然要求实现这个方法
  init(
    configuration: ReadConfiguration
  ) throws {
    // regularFileContents 是 Data?
    //
    // guard let 会确认选择的内容
    // 是一个可以读取的普通文件
    guard let data = configuration.file.regularFileContents
    else {
      // 文件中没有普通二进制内容时
      // 抛出 Cocoa 提供的文件损坏错误
      throw CocoaError(.fileReadCorruptFile)
    }
    self.data = data
  }

  // fileWrapper 会在用户选择保存位置后调用
  //
  // 返回值告诉系统应该把什么内容
  // 写进最终文件
  func fileWrapper(
    configuration: WriteConfiguration
  ) throws -> FileWrapper {
    // regularFileWithContents 表示普通文件
    // 文件内容就是上面保存的 JSON Data
    FileWrapper(
      regularFileWithContents: data
    )
  }
}
