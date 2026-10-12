import AppKit
@preconcurrency import ApplicationServices

// 监听浮动学习窗口使用的全局快捷键。
//
// 全局监听只在其他 App 处于前台时接收按键事件。
// 本应用内仍可通过鼠标和窗口控制按钮操作卡片。
@MainActor
final class KeyboardMonitor {

  // 保存系统返回的监听器令牌
  // stop() 时必须用它注销监听器，避免窗口关闭后仍然收到回调
  private var monitor: Any?

  // 把系统修饰键转换为领域层的修饰键集合
  // 只保留本项目支持的四种修饰键
  private func shortcutModifiers(
    for modifierFlags: NSEvent.ModifierFlags
  ) -> ShortcutModifiers {
    let flags = modifierFlags.intersection(
      .deviceIndependentFlagsMask
    )
    var modifiers: ShortcutModifiers = []

    if flags.contains(.control) {
      modifiers.insert(.control)
    }

    if flags.contains(.option) {
      modifiers.insert(.option)
    }

    if flags.contains(.shift) {
      modifiers.insert(.shift)
    }

    if flags.contains(.command) {
      modifiers.insert(.command)
    }

    return modifiers
  }

  func start(
    shortcuts: [GlobalShortcut],
    onShortcut: @escaping (ShortcutAction) -> Void
  ) {
    // 同一个浮动窗口只能注册一个全局监听器
    guard monitor == nil else {
      return
    }

    // 全局键盘事件需要辅助功能权限
    // 未授权时请求系统显示授权提示，本次不注册监听器
    guard AXIsProcessTrusted() else {
      let options =
        [
          kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true
        ] as CFDictionary

      AXIsProcessTrustedWithOptions(options)
      return
    }

    monitor = NSEvent.addGlobalMonitorForEvents(
      matching: .keyDown
    ) { [weak self] event in
      guard let self else {
        return
      }

      let modifiers = self.shortcutModifiers(
        for: event.modifierFlags
      )

      // 只有按键和修饰键都完全匹配时才执行操作
      guard
        let shortcut = shortcuts.first(
          where: {
            $0.key.keyCode == event.keyCode && $0.modifiers == modifiers && !$0.modifiers.isEmpty
          }
        )
      else {
        return
      }

      onShortcut(shortcut.action)
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
