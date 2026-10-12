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

    // 显示当前卡组的浮动学习窗口
    case showFloatingWindow = "show_floating_window"

    // 隐藏当前显示的浮动学习窗口
    case hideFloatingWindow = "hide_floating_window"

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
        case .showFloatingWindow:
            return "显示浮动窗口"
        case .hideFloatingWindow:
            return "隐藏浮动窗口"
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

// ShortcutKey 表示全局快捷键中的主按键
//
// keyCode 保存 macOS 虚拟建码，支持用户录制任意主按键
// displayText 保存设置页显示给用户文字
struct ShortcutKey: Equatable, Codable, Sendable {
    // macOs 用于识别按键的虚拟键码
    let keyCode: UInt16

    // 设置页展示的按键名称
    let displayText: String

    init(
        keyCode: UInt16,
        displayText: String
    ) {
        self.keyCode =  keyCode
        self.displayText = displayText
    }

    // 三个默认快捷键使用的主按键
    static let leftArrow = ShortcutKey(keyCode: 123, displayText: "←")
    static let space = ShortcutKey(keyCode: 49, displayText: "Space")
    static let rightArrow = ShortcutKey(keyCode: 124, displayText: "→")
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
    
    // 默认全局快捷键。
    //
    // 前三组用于控制卡片；后两组用于显示和隐藏浮动学习窗口。
    
    static var defaults: [GlobalShortcut] {
        [
            GlobalShortcut(action: .previousCard, modifiers: [.control, .option], key: .leftArrow),
            GlobalShortcut(action: .flipCard, modifiers: [.control, .option], key: .space),
            GlobalShortcut(action: .nextCard, modifiers: [.control, .option], key: .rightArrow),
            // Control + Option + O：显示浮动学习窗口
            GlobalShortcut(
                action: .showFloatingWindow,
                modifiers: [.control, .option],
                key: ShortcutKey(keyCode: 31, displayText: "O")
            ),
            // Control + Option + W：隐藏浮动学习窗口
            GlobalShortcut(
                action: .hideFloatingWindow,
                modifiers: [.control, .option],
                key: ShortcutKey(keyCode: 13, displayText: "W")
            )
        ]
    }
}
