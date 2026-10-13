import Carbon.HIToolbox
import Foundation

// 管理 EchoCards 向 macOS 注册的指定全局快捷键
//
// 这个类型只接收已经注册的组合键
// 不会监听用户在其他应用中输入的普通键盘内容
@MainActor
final class GlobalHotKeyManager {
  // 热键事件所属应用的四字符标识
  private static let signature: OSType = 0x4543_484F

  // 系统事件处理器的引用
  //
  // 安装事件处理器后保存引用
  // stop 是使用他注销
  private var eventHandler: EventHandlerRef?
  // 保存所有已成功注册的系统热键
  //
  // 每个快捷键注册成功后都会得到一个 EventHotKeyRef。
  // 修改设置或停止监听时，需要逐一注销。
  private var registeredHotKeys: [EventHotKeyRef] = []

  // 将系统返回的数字 ID 映射为 EchoCards 的业务动作。
  //
  // 例如：
  // 1 -> previousCard
  // 2 -> flipCard
  // 3 -> nextCard
  private var actionsByID: [UInt32: ShortcutAction] = [:]

  // 快捷键触发后的回调
  //
  // GlobalHotKeyManager 只负责识别快捷键
  // 具体的翻面、切卡和窗口操作交给上层执行。
  private var onShortcut: ((ShortcutAction) -> Void)?

  // 注销当前管理器的全部系统资源
  //
  // 修改快捷键配置前先调用 stop
  // 防止旧快捷键和新快捷同时存在
  // 应用结束使用管理器时也要调用，
  // 防止系统继续把热键事件发送给旧处理器。
  func stop() {
    // 每个 RegisterEventHotKey 都必须对应一次
    // UnregisterEventHotKey
    for hotKey in registeredHotKeys {
      _ = UnregisterEventHotKey(hotKey)
    }

    // 系统热键已经全部注销
    // 清空本地保存的引用和动作映射。
    registeredHotKeys.removeAll()
    actionsByID.removeAll()

    // 事件处理器只安装一次
    // 如果已经存在，就从系统事件目标中移除
    if let eventHandler {
      _ = RemoveEventHandler(eventHandler)
      self.eventHandler = nil
    }

    // 释放上层传入的回调，
    // 避免管理器继续持有页面或业务对象。

    onShortcut = nil

  }

  // 将领域层的修饰键转换成 Carbon 热键 API 使用的位掩码
  //
  // ShortcutModifiers 属于 EchoCards 自己的数据模型
  // controlKey optionKey 等是 macOS Carbon Api 的常量
  private func carbonModifiers(
    _ modifiers: ShortcutModifiers
  ) -> UInt32 {
    // 位掩码位空 表示暂时没有任何修饰键
    var result: UInt32 = 0

    // 如果用户配置包含 Control
    // 就把 Carbon 的 controlKey 位加入结果
    if modifiers.contains(.control) {
      result |= UInt32(controlKey)
    }

    // 如果用户配置包含 Option
    // 就把 Carbon 的 optionKey 位加入结果
    if modifiers.contains(.option) {
      result |= UInt32(optionKey)
    }

    // 如果用户配置包含 Shift，
    // 就把 Carbon 的 shiftKey 位加入结果。
    if modifiers.contains(.shift) {
      result |= UInt32(shiftKey)
    }

    // 如果用户配置包含 Command，
    // 就把 Carbon 的 cmdKey 位加入结果。
    if modifiers.contains(.command) {
      result |= UInt32(cmdKey)
    }

    return result
  }

  // 开始注册一组全局快捷键
  //
  // shortcuts 是用户当前保存的快捷键配置
  // onShortcut 会在其中某个快捷键被按下时执行
  func start(
    shortcuts: [GlobalShortcut],
    onShortcut: @escaping (ShortcutAction) -> Void
  ) {
    // 重新注册之后先清理旧资源
    // 避免修改设置后，新旧快捷键同时生效
    stop()

    // 保存业务回调
    // 真正收到热键事件时，handleHotKey 会调用他
    self.onShortcut = onShortcut

    // 先统一安装的系统处理器
    //
    // 如果安装失败，后面的热键即使注册了
    // 应用也无法处理他们，因此直接停止
    let handlerStatus = installEventHandler()

    guard handlerStatus == noErr else {
      print("安装全局快捷键事件处理器失败: \(handlerStatus)")
      self.onShortcut = nil
      return
    }

    // 为每个快捷键分配一个从 1 开始的数字 ID
    //
    // Carbon 回调只能返回数字 ID
    // actionsById 再把它转换回 ShortcutAction
    for (index, shortcut) in shortcuts.enumerated() {
      let numericID = UInt32(index + 1)

      let hotKeyID = EventHotKeyID(
        signature: Self.signature,
        id: numericID
      )

      // 注册成功后，系统会把热键引用写入这个变量
      var hotKeyRef: EventHotKeyRef?

      let registrationStatus = RegisterEventHotKey(
        // 用户录制的 macOS 虚拟键码
        UInt32(shortcut.key.keyCode),
        // 把应用自己的修饰键格式转换为 Carbon 格式
        carbonModifiers(shortcut.modifiers),
        // 用于再系统回调中识别具体快捷键
        hotKeyID,
        // 热键事件发送到当前应用的事件目标
        GetApplicationEventTarget(),
        // 当前不需要额外选项
        0,
        // 接收注册成功后产生的系统引用
        &hotKeyRef
      )

      guard registrationStatus == noErr, let hotKeyRef
      else {
        // 常见原因是这个组合键已经被其他应用占用
        print("注册全局快捷键失败: \(shortcut.displayText), 错误码：\(registrationStatus)")
        continue
      }

      // 保存系统引用 供 stop 注销
      registeredHotKeys.append(hotKeyRef)

      // 保存数字 ID 对应的业务动作
      // 供 handleHotKey 查找
      actionsByID[numericID] = shortcut.action
    }
  }

  private func handleHotKey(
    id: UInt32
  ) {
    // 如果 ID 不在映射表中
    // 说明他不是当前管理器成功注册的快捷键
    guard let action = actionsByID[id] else {
      return
    }

    // 管理器只报告发生了那个业务动作
    // 不在这里直接访问窗口或StudyViewModel
    onShortcut?(action)
  }

  // 安装一个只接收“已注册热键被按下”事件的系统处理器。
  //
  // 这个处理器不会收到普通键盘输入；
  // 只有 RegisterEventHotKey 注册成功的组合键才会触发。
  private func installEventHandler() -> OSStatus {
    // 告诉 Carbon 我们只关心键盘热键按下事件。
    //
    // eventClass 表示键盘事件类别；
    // eventKind 表示注册热键被按下。
    var eventType = EventTypeSpec(
      eventClass: OSType(kEventClassKeyboard),
      eventKind: UInt32(kEventHotKeyPressed)
    )

    return InstallEventHandler(
      // 把处理器安装到整个应用的事件目标上，
      // 所以无论哪个 EchoCards 窗口存在，都使用同一个处理器。
      GetApplicationEventTarget(),

      // Carbon 使用 C 风格回调。
      // 第一个参数是系统处理器引用，这里不需要；
      // event 是本次热键事件；
      // userData 是下面传入的 GlobalHotKeyManager 指针。
      { _, event, userData in
        guard let event, let userData else {
          // Carbon 回调必须返回 OSStatus（Int32），
          // 因此显式转换系统错误码的类型。
          return OSStatus(eventNotHandledErr)
        }

        // Carbon 会把注册时使用的 signature 和 id
        // 放进 EventHotKeyID 中返回。
        var hotKeyID = EventHotKeyID()

        let readStatus = GetEventParameter(
          event,
          EventParamName(kEventParamDirectObject),
          EventParamType(typeEventHotKeyID),
          nil,
          MemoryLayout<EventHotKeyID>.size,
          nil,
          &hotKeyID
        )

        // 如果无法读取热键 ID，就把系统错误码返回。
        guard readStatus == noErr else {
          return readStatus
        }

        // userData 原本是一个不受 Swift 自动管理的指针，
        // 这里把它还原成 GlobalHotKeyManager。
        //
        // takeUnretainedValue 表示不增加引用计数；
        // 管理器的生命周期仍由 SwiftUI 上层负责。
        let manager = Unmanaged<GlobalHotKeyManager>
          .fromOpaque(userData)
          .takeUnretainedValue()

        // 应用事件目标在主线程分发事件。
        // 明确回到 MainActor 后，才能调用受 @MainActor
        // 保护的 handleHotKey。
        MainActor.assumeIsolated {
          manager.handleHotKey(id: hotKeyID.id)
        }

        return noErr
      },

      // 本次只注册一种事件类型。
      1,
      &eventType,

      // 把当前管理器转换成 C API 可以保存的裸指针。
      // 系统回调时会通过 userData 原样返回。
      Unmanaged.passUnretained(self).toOpaque(),

      // 系统创建成功后，把处理器引用写入 eventHandler，
      // 以便 stop() 时注销。
      &eventHandler
    )
  }
}
