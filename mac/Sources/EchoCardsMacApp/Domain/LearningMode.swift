// LearningMode 用户选择的学习流程
//
// String：每种模式拥有稳定的字符串值，便于写入数据库
// CaseIterable: 可以通过 allCases 获取全部模式，用于设置界面。
// Identifiable: 可以直接用于 SwiftUI 的 Picker 和 ForEach。
// Codable：可以编码和解码，便于保存设置。
// Sendable：可以安全地跨并发任务传递。
enum LearningMode: String, CaseIterable, Identifiable, Codable, Sendable {
    // 用户手动翻面，切卡和控制朗读
    case manual = "manual"
    // 系统朗读后开启麦克风，等待用户朗读完成
    case followAlong = "follow_along"
    // 系统自动朗读，显示记忆点并切换下一张
    case autoPlay = "auto_play"
    // Identifiable 要求提供 id
    //
    // 每种枚举值本身就是唯一值，因起直接返回 self
    var id: Self {
        self
    }
    
    // 返回适合显示给用户的中文名称
    //
    // 数据库存储使用稳定的英文 rawValue
    // 界面显示则使用本地化文字
    var displayName: String {
        switch self {
        case .manual:
            return "手动学习"
        case .followAlong:
            return "自动跟读"
        case .autoPlay:
            return "自动播放"
        }
    }
}
