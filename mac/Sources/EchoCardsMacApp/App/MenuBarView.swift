import SwiftUI

// macOS 菜单栏入口，与主窗口和全局快捷键共享同一个 AppModel。
struct MenuBarView: View {
  @Environment(AppModel.self) private var appModel
  @Environment(\.openWindow) private var openWindow
  @Environment(\.dismissWindow) private var dismissWindow

  var body: some View {
    Button("打开知声卡") {
      openWindow(id: "main-window")
    }

    Divider()

    if appModel.isFloatingWindowOpen {
      Button("隐藏浮动窗口") {
        dismissWindow(id: "floating-study-window")
        appModel.markFloatingWindowClosed()
      }
    } else {
      Button("显示浮动窗口") {
        openWindow(id: "floating-study-window")
        appModel.markFloatingWindowOpen()
      }
    }

    Divider()

    Text("按住 ⌥ 显示，松开隐藏")
  }
}
