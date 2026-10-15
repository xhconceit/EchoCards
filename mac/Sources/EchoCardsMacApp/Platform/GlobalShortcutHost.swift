import SwiftUI

// 在 SwiftUI 窗口环境 与 应用级 全局快捷键之间建立桥梁
//
// GlobalHostKeyManagere 能识别系统热键
// 但不能直接使用 SwiftUI 的 openWindow 和 dismissWindow
//
// 这个 View 不显示任何内容
// 只负责把两个窗口环境动作给 AppModel
struct GlobalShortcutHost: View {
  // 读取应用共享状态
  @Environment(AppModel.self) private var appModel

  // SwiftUI 提供的窗口打开动作
  @Environment(\.openWindow) private var openWindow

  // SwiftUI 提供的窗口关闭动作
  @Environment(\.dismissWindow) private var dismissWindow

  var body: some View {
    // 使用一个尺寸为零的透明视图承载生命周期
    //
    // 它不会显示内容，也不会阻挡主界面的鼠标操作
    Color.clear
      .frame(width: 0, height: 0)
      .allowsHitTesting(false)
      .task {
        registerShortcuts()
      }
      .onChange(of: appModel.settings.globalShortcuts) {
        // 录制新快捷键或恢复默认值后，立即替换系统中的热键。
        registerShortcuts()
      }
  }

  private func registerShortcuts() {
    appModel.startGlobalShortcuts(
      onShowFloatingWindow: {
        // 使用 Scene 中声明的窗口 ID 打开学习窗口
        openWindow(id: "floating-study-window")

        // 同步更新应用保存的窗口状态
        appModel.markFloatingWindowOpen()
      },
      onHideFloatingWindow: {
        // 关闭相同 ID 的学习窗口
        dismissWindow(id: "floating-study-window")

        // 同步更新应用保存的窗口状态
        appModel.markFloatingWindowClosed()
      }
    )
  }
}
