import AppKit

// 监听学习窗口使用的键盘快捷键
//
// 同时监听
// 1. 应用处于激活状态时的本地按键
// 2. 用户正在使用其他 App 时的全局按键
@MainActor
final class KeyboardMonitor {
  private var monitor: Any?

  func start(
    onKeyDown: @escaping (_ keyCode: UInt16) -> Bool
  ) {

    NSApplication.shared.setActivationPolicy(.regular)
    NSApplication.shared.activate(ignoringOtherApps: true)
    guard monitor == nil else {
      return
    }

    monitor = NSEvent.addLocalMonitorForEvents(
      matching: .keyDown
    ) { event in
      let wasHandled = onKeyDown(event.keyCode)
      return wasHandled ? nil : event
    }
  }

  func stop() {
    guard let monitor else {
      return
    }

    NSEvent.removeMonitor(monitor)
    self.monitor = nil
  }

}
