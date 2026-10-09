import Foundation

// AppSessionStore 保存应用运行状态
//
// 它和 UserSettingsStore 区别是：
//
// UserSettingsStore 保存字号，透明度等用户偏好
// AppSessionStore 保存上次选择了那个卡组
struct AppSessionStore {
  // UserDefaults 中保存卡组 ID 的固定键名
  private let selectedDeckIDKey = "echoCards.selectedDeckID"

  private let userDefaults: UserDefaults

  init(
    userDefaults: UserDefaults = .standard
  ) {
    self.userDefaults = userDefaults
  }

  // 读取上次选择的卡组 ID
  func loadSelectedDeckID() -> UUID? {
    // 先读取保存的 UUID 字符串
    guard let idText = userDefaults.string(forKey: selectedDeckIDKey) else {
      return nil
    }

    // UUID(uuidString:) 会验证字符串格式
    //
    // 字符串无效返回 nil
    return UUID(uuidString: idText)
  }

  // 保存当前选择的卡组 ID
  func saveSelectedDeckID(
    _ deckID: UUID?
  ) {
    if let deckID {
      // UserDefaults 不直接保存 UUID
      // 因此转换成字符串
      userDefaults.set(
        deckID.uuidString,
        forKey: selectedDeckIDKey
      )
    } else {
      // nil 表示当前没有选择卡组
      // 删除之前保存的值
      userDefaults.removeObject(
        forKey: selectedDeckIDKey
      )
    }
  }
}
