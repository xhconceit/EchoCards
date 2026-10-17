import AppKit
import Foundation

// 监听“单独长按 Option”手势。
//
// Carbon 的 RegisterEventHotKey 必须包含一个普通主按键，无法注册只有修饰键的
// 快捷键。NSEvent.modifierFlags 可以直接读取当前修饰键状态，不需要监听或保存
// 用户输入的普通按键，也不依赖辅助功能权限。
@MainActor
final class OptionLongPressMonitor {
  static let defaultHoldDuration: TimeInterval = 0.6
  private static let pollingInterval: TimeInterval = 0.02

  private let holdDuration: TimeInterval
  private var pollingTimer: Timer?
  private var localMonitor: Any?
  private var pendingWorkItem: DispatchWorkItem?
  private var isTrackingOption = false
  private var didTriggerForCurrentPress = false
  private var isSuppressedUntilOptionRelease = false
  private var onLongPress: (() -> Void)?
  private var onLongPressEnd: (() -> Void)?

  init(holdDuration: TimeInterval = defaultHoldDuration) {
    self.holdDuration = holdDuration
  }

  func start(
    onLongPress: @escaping () -> Void,
    onLongPressEnd: @escaping () -> Void
  ) {
    stop()
    self.onLongPress = onLongPress
    self.onLongPressEnd = onLongPressEnd

    let timer = Timer(
      timeInterval: Self.pollingInterval,
      repeats: true
    ) { [weak self] _ in
      MainActor.assumeIsolated {
        self?.handleModifierChange(NSEvent.modifierFlags)
      }
    }
    pollingTimer = timer
    RunLoop.main.add(timer, forMode: .common)

    localMonitor = NSEvent.addLocalMonitorForEvents(
      matching: .keyDown
    ) { [weak self] event in
      self?.handleKeyDown()
      return event
    }
  }

  func stop() {
    if didTriggerForCurrentPress {
      onLongPressEnd?()
    }

    cancelPendingLongPress()

    pollingTimer?.invalidate()
    pollingTimer = nil

    if let localMonitor {
      NSEvent.removeMonitor(localMonitor)
      self.localMonitor = nil
    }

    isTrackingOption = false
    didTriggerForCurrentPress = false
    isSuppressedUntilOptionRelease = false
    onLongPress = nil
    onLongPressEnd = nil
  }

  private func handleKeyDown() {
    // 知声卡位于前台时，Option 与普通按键组成现有快捷键，不把它当成长按手势。
    // 长按已经触发后仍保持窗口显示，直到 Option 被放开。
    if isTrackingOption && !didTriggerForCurrentPress {
      isSuppressedUntilOptionRelease = true
      cancelCurrentPress()
    }
  }

  private func handleModifierChange(_ modifierFlags: NSEvent.ModifierFlags) {
    // 忽略 Caps Lock、Fn 和数字键盘等与快捷键无关的状态。
    let flags = modifierFlags.intersection([.control, .option, .shift, .command])
    let isOptionOnly = flags == .option

    guard isOptionOnly else {
      isSuppressedUntilOptionRelease = false
      cancelCurrentPress()
      return
    }

    guard !isSuppressedUntilOptionRelease else {
      return
    }

    guard !isTrackingOption else {
      return
    }

    isTrackingOption = true
    didTriggerForCurrentPress = false

    let workItem = DispatchWorkItem { [weak self] in
      guard let self,
            self.isTrackingOption,
            !self.didTriggerForCurrentPress
      else {
        return
      }

      self.didTriggerForCurrentPress = true
      self.pendingWorkItem = nil
      self.onLongPress?()
    }

    pendingWorkItem = workItem
    DispatchQueue.main.asyncAfter(
      deadline: .now() + holdDuration,
      execute: workItem
    )
  }

  private func cancelCurrentPress() {
    if didTriggerForCurrentPress {
      onLongPressEnd?()
    }

    cancelPendingLongPress()
    isTrackingOption = false
    didTriggerForCurrentPress = false
  }

  private func cancelPendingLongPress() {
    pendingWorkItem?.cancel()
    pendingWorkItem = nil
  }
}
