// ShortcutAction 表示全局快捷键可以触发的业务操作
//
// String：提供稳定的存储值。
// CaseIterable：设置页面可以列出所有可配置操作。
// Identifiable：可以直接用于 SwiftUI 的 List 和 ForEach。
// Codable：可以保存用户的快捷键配置。
// Sendable：可以安全地跨并发任务传递。
enum ShortcutAction: String, CaseIterable, Identifiable, Codable, Sendable {
    // 切换到当前卡组的上一张
    case previousCard = "previous_card"

    // 切换卡片正面和背面
    case flipCard = "flip_card"

    // 切换到当前卡组的下一张
    case nextCard = "next_card"

    // 每个枚举值本身就是唯一标识
    var id: Self {
        self
    }

    // 设置页面显示的中文名称
    var displayName: String {
        switch self {
        case .previousCard:
            return "上一张"
        case .flipCard:
            return "翻面"
        case .nextCard:
            return "下一张"
        }
    }
}

// ShortcutModifiers 表示快捷键使用的使用的修饰键集合。
//
// OptionSet 允许一个值同时包含多个选项
// [.control, .option]
//
// Codable 允许把组合保存到本地
// Sendable 允许安全的跨任务传递
struct ShortcutModifiers: OptionSet, Codable, Sendable {
    // 每一个二进制代表一种修饰键
    let rawValue: Int

    // OptionSet 要求提供这个初始化值
    init(rawValue: Int) {
        self.rawValue = rawValue
    }

    /// 0001
    static let control = ShortcutModifiers(
        rawValue: 1 << 0
    )

    /// 0010
    static let option = ShortcutModifiers(
        rawValue: 1 << 1
    )

    /// 0100
    static let shift = ShortcutModifiers(rawValue: 1 << 2)

    /// 1000
    static let command = ShortcutModifiers(
        rawValue: 1 << 3
    )

    // 返回适合显示给用户的 macOS 修饰键符号。
    var displayText: String {
        var symbols: [String] = []

        if contains(.control) {
            symbols.append("⌃")
        }

        if contains(.option) {
            symbols.append("⌥")
        }

        if contains(.shift) {
            symbols.append("⇧")
        }

        if contains(.command) {
            symbols.append("⌘")
        }
        return symbols.joined()
    }
}

// ShortcutKey 表示可以用于学习控制的按键
//
// 领域模型保存语义名称
// macOS 平台键码。平台适配器以后负责转换
enum ShortcutKey: String, CaseIterable, Codable, Sendable {
    case leftArrow = "left_arrow"
    case space = "space"
    case rightArrow = "right_arrow"

    // 返回设置页面显示的按键名称
    var displayText: String {
        switch self {
        case .leftArrow:
            return "←"

        case .space:
            return "Space"

        case .rightArrow:
            return "→"

        }
    }
}

// GlobalShortcut 表示一个业务操作对应的完整快捷键
struct GlobalShortcut: Identifiable, Equatable, Codable, Sendable {
    // 业务操作同时作为唯一标识
    var id: ShortcutAction {
        action
    }
    
    // 快捷键触发的业务操作
    var action: ShortcutAction
    
    // Control, Option 等修饰键组合
    var modifiers: ShortcutModifiers
    
    // 方向键或空格等主按键。
    var key: ShortcutKey
    
    // 返回设置页面需要显示的完整文字。
    var displayText: String {
        modifiers.displayText + key.displayText
    }
    
    // 第一版使用的三组默认全局快捷键。
    
    static var defaults: [GlobalShortcut] {
        [
            GlobalShortcut(action: .previousCard, modifiers: [.control, .option], key: .leftArrow),
            GlobalShortcut(action: .flipCard, modifiers: [.control, .option], key: .space),
            GlobalShortcut(action: .nextCard, modifiers: [.control, .option], key: .rightArrow)
        ]
    }
}
