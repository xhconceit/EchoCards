import Foundation

// UserSettings 保存用户可以修改的 macOS 偏好
//
// Equatable: 用于判断设置是否发生变化
// Codable: 允许编码后保存到本地
// Sendable: 允许设置安全地跨并发任务传递

struct UserSettings: Equatable, Codable, Sendable {
    // 浮动窗口宽度
    var floatingWindowWidth: Double
    // 浮动窗口高度
    var floatingWindowHeight: Double
    // 浮动窗口背景透明度
    var floatingWindowOpacity: Double
    // 浮动卡片主要文字大小
    var cardFontSize: Double
    // 是否显示上一张，翻面，下一张
    var showsControls: Bool
    // 是否让浮动窗口出现在所有桌面空间
    var appearsOnAllSpaces: Bool
    // 是否允许用户通过鼠标拖动浮动窗口。
      var allowsDragging: Bool
    // 应用启动时是否恢复上次的浮动窗口
    var restoresFloatingWindow: Bool
    // 新打开卡组时默认使用的学习模式
    var defaultLearningMode: LearningMode
    
    // TTS 朗读速度，
    var speechRate: Double
    
    // 显示记忆点后等待多久自动切到下一张
    var autoAdvanceDelayMilliseconds: Int
    
    // 用户当前配置的全局快捷键
    var globalShortcuts: [GlobalShortcut]
    
    // 设置最后更新时间
    var updatedAt: Date
    
    // 应用第一次运行时使用的默设置
    static var defaults: UserSettings {
        UserSettings(
            floatingWindowWidth: 420,
            floatingWindowHeight: 300,
            floatingWindowOpacity: 1.0,
            cardFontSize: 24,
            showsControls: true,
            appearsOnAllSpaces: false,
            allowsDragging: true,
            restoresFloatingWindow: false,
            defaultLearningMode: .manual,
            speechRate: 1.0,
            autoAdvanceDelayMilliseconds: 600,
            globalShortcuts: GlobalShortcut.defaults,
            updatedAt: Date()
        )
    }
}
