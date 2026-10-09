import AppKit
import SwiftUI

//  FloatingWindowConfigurator 把 SwiftUI 窗口设置
// 应用到底层 macOS NSWindow
//
// NSViewRepresentable 是 SwiftUI 与 AppKit 之间的桥梁
// 这个 View 本身不显示内容，只用来取得所属的 NSWindow
struct FloatingWindowConfigurator: NSViewRepresentable {
  // 是否让窗口出现在所有桌面空间
  let appearsOnAllSpaces: Bool
  // 是否允许拖动窗口背景来移动窗口
  let allowsDragging: Bool
  // SwiftUI 第一次创建适配器时调用
  func makeNSView(context: Context) -> some NSView {
    let view = NSView()

    // 此时 view 可能还没有连接到窗口
    // 因此等到下一次主线程循环再读取 view.window
    DispatchQueue.main.async {
      configureWindow(containing: view)
    }
    return view
  }

  // SwiftUI 输入发生变化时调用
  //
  // 用户修改设置后 通过这里更新已经存在的窗口
  func updateNSView(_ nsView: NSViewType, context: Context) {
    DispatchQueue.main.async {
      configureWindow(containing: nsView)
    }
  }

  // 配置适配器所属的底层 NSWindow
  private func configureWindow(containing view: NSView) {
    guard let window = view.window else {
      return
    }

    // .floating 让窗口位于普通应用窗口上
    window.level = .floating
    // 无标题栏窗口可以通过拖动背景移动
    window.isMovableByWindowBackground = allowsDragging

    if appearsOnAllSpaces {
      // 让窗口出现在所有普通桌面空间。
      window.collectionBehavior.insert(.canJoinAllSpaces)

      // macOS 26：
      // 允许浮动窗口进入其他 App 的全屏空间，
      // 也适用于系统级浮动窗口和覆盖层。
      window.collectionBehavior.insert(.canJoinAllApplications)

      // 防止与新的行为产生冲突。
      window.collectionBehavior.remove(.auxiliary)
      window.collectionBehavior.remove(.fullScreenAuxiliary)
    } else {
      // 恢复为普通窗口行为。
      window.collectionBehavior.remove(.canJoinAllSpaces)
      window.collectionBehavior.remove(.canJoinAllApplications)
    }
  }
}
