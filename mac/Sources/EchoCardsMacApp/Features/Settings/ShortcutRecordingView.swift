import AppKit
import SwiftUI

// ShortcutRecordingView 使用自身的第一响应着能力接收录制按键
//
// 它不使用 NSEvent.addLocalMonitorForEvents
// 只有在设置页的录制状态中才会获取键盘焦点
struct ShortcutRecordingView: NSViewRepresentable {

    // 是否应该进入录制状态
    let isRecording: Bool

    // 用户录制到有效快捷键后的回调
    let onShortcutRecorded:
        (
            _ key: ShortcutKey,
            _ modifiers: ShortcutModifiers
        ) -> Void

        // 当前是否正在等待用户录制快捷键。
 

    func makeNSView(
        context: Context
    ) -> ShortcutRecordingNSView {
        let view = ShortcutRecordingNSView()
        view.onShortcutRecorded = onShortcutRecorded
        return view
    }

    func updateNSView(
  _ nsView: NSViewType,
  context: Context
) {
  nsView.onShortcutRecorded = onShortcutRecorded
  nsView.isRecording = isRecording
}

}

// 原生视图通过重写 keyDown 接收当前控件的按键
final class ShortcutRecordingNSView: NSView {
    // 录制成功时交给 SwiftUI 的回调
    // 录制成功时交给 SwiftUI 的回调。
    var onShortcutRecorded:
        (
            (_ key: ShortcutKey, _ modifiers: ShortcutModifiers) -> Void
        )?

        var isRecording = false {
  didSet {
    requestKeyboardFocusIfNeeded()
  }
}

    // 允许当前视图成为窗口的键盘第一响应者
    override var acceptsFirstResponder: Bool {
        true
    }
    // 视图加入窗口后再次尝试获取焦点。
override func viewDidMoveToWindow() {
  super.viewDidMoveToWindow()
  requestKeyboardFocusIfNeeded()
}

// 只有录制状态才把自己设为第一响应者。
private func requestKeyboardFocusIfNeeded() {
  guard isRecording else {
    return
  }

  DispatchQueue.main.async { [weak self] in
    guard let self, self.isRecording else {
      return
    }

    self.window?.makeFirstResponder(self)
  }
}

    override func keyDown(with event: NSEvent) {
        let modifiers = ShortcutModifiers(
            modifierFlags: event.modifierFlags
        )

        // 全局快捷键至少要包含一个修饰键
        guard !modifiers.isEmpty else {
            return
        }

        let key = ShortcutKey(
            keyCode: event.keyCode,
            displayText: displayText(for: event)
        )

        onShortcutRecorded?(key, modifiers)
    }

    // 将将常见特殊键转换为适合设置页显示的文字。
    private func displayText(
        for event: NSEvent
    ) -> String {
        switch event.keyCode {
        case 36:
            return "↩"
        case 48:
            return "⇥"
        case 49:
            return "Space"
        case 51:
            return "⌫"
        case 53:
            return "⎋"
        case 117:
            return "⌦"
        case 123:
            return "←"
        case 124:
            return "→"
        case 125:
            return "↓"
        case 126:
            return "↑"
        default:
            return event.charactersIgnoringModifiers?
                .uppercased() ?? "键码 \(event.keyCode)"
        }
    }
    
}

// 把 AppKit 修饰键转换为领域层修饰键
extension ShortcutModifiers {
    fileprivate init(
        modifierFlags: NSEvent.ModifierFlags
    ) {
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
        if flags.contains(.command) {
            modifiers.insert(.command)
        }
        if flags.contains(.shift) {
            modifiers.insert(.shift)
        }
        self = modifiers
    }
}
