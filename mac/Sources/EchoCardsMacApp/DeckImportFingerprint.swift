import CryptoKit
import Foundation

// DeckImportFingerprint 负责根据卡组实际内容
// 计算稳定的 SHA-256 指纹
//
// 文件名，JSON 字段顺序和未知字段
// 都不会参与指纹计算
struct DeckImportFingerprint {
  // 根据已经规范化的导入生成指纹
  func make(for document: DeckImportDocument) -> String {
    // 先把导入数据转换成专门用于计算指纹的结构
    //
    // 这样可以明确控制那些字段参与计算
    let content = FingerprintDeck(
      title: document.title,
      description: document.description,
      cards: document.cards.map { card in
        FingerprintCard(
          title: card.title,
          content: card.content,
          speechText: card.speechText,
          memoryTip: card.memoryTip
        )
      }
    )

    // JSONEncoder 把 Swift 数据编码为 JSON 二进制
    let encoder = JSONEncoder()

    // sortedKeys 表示 JSON 对象中的键按照固定顺序输出
    //
    // 如果不排序，同样的内容可能因为字段顺序不同
    // 得到不同的二进制数据和不同的 SHA-256
    encoder.outputFormatting = [.sortedKeys]

    // 前面的数据全部来自可编码的内部结构
    // 正常情况下这里不会失败
    //
    // try! 表示
    // 如果发生错误，直接终止程序
    //
    // 一般业务代码不应滥用 try!
    // 这里只编码我们自己定义的简单结构
    let data = try! encoder.encode(content)

    // SHA256.hash 会根据 Data 计算固定长度摘要
    let digest = SHA256.hash(data: data)

    // digest 中每个元素都是一个字节
    //
    // map 把每个字节转换成两位十六进制
    // joined 再把所有字符串连接起来
    return digest.map { byte in String(format: "%02x", byte) }.joined()
  }
}

// private 表示这些类型只能在当前文件中使用
//
// Encodable 表示它们可以被 JSONEncoder 编码
private struct FingerprintDeck: Encodable {
  let title: String
  let description: String?

  // 一个卡组包含的是卡片数组，
  // 所以元素类型必须是 FingerprintCard。
  let cards: [FingerprintCard]
}

private struct FingerprintCard: Encodable {
  let title: String
  let content: String
  let speechText: String?
  let memoryTip: String?
}
