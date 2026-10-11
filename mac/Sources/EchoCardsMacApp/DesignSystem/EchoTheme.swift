import SwiftUI

// EchoTheme 统一保存全 APP 的视觉规范
// 后续修改颜色或圆角时，只改这里，不要散落在每个页面里
enum EchoTheme {

    // 内容区偏淡紫的背景
     static let canvasBackground = Color(hex: "F7F4FF")

     // 卡组卡片，弹窗等白色表面
     static let surface = Color.white

     // 紫色主按钮，选中状态颜色
     static let accent = Color(hex: "5B5BD6")

     // 深色正文与浅色辅助文字
     static let ink = Color(hex: "20243A")
     static let muted = Color(hex: "737A91")

     // 卡片的圆角
     static let cardRadius: CGFloat = 20

     // 按钮的圆角，比卡片的圆角小
     static let buttonRadius: CGFloat = 14
}

// 让 SwiftUI Color 支持 CSS 中常见的十六进制颜色
// 例如 #5B5BD6 在 SwiftUI 中写成 Color(hex: "5B5BD6")。
extension Color {
    init(hex: String) {
        let value = UInt64(hex, radix: 16) ?? 0

        let red = Double((value >> 16) & 0xFF) / 255
        let green = Double((value >> 8) & 0xFF) / 255
        let blue = Double((value & 0xFF) & 0xFF) / 255
        self.init(red: red, green: green, blue: blue)
    }
}