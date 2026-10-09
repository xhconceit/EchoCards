import AppKit

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
