import Foundation

// UserSettingsStore 专门负责把用户设置
// 保存到本机，以及在下次启时读取
//
// View 不直接接触 UserDefaults
// 后面统一通过 AppModel 使用这个存储对象
struct UserSettingsStore {
  // UserDefaults 中使用的固定键名
  //
  // 键名一旦发布后尽量不要随意修改
  // 否则旧版本保存的设置将无法读取。
  private let storageKey = "echoCards.userSettings"

  // 系统提供的本地轻量键值存储
  private let userDefaults: UserDefaults

  // 默认使用当前 App 的标准 UserDefaults
  init(userDefaults: UserDefaults = .standard) {
    self.userDefaults = userDefaults
  }

  // 从本机读取用户设置
  func load() -> UserSettings {
    // 没有保存过设置中，使用默认值
    guard let data = userDefaults.data(forKey: storageKey) else {
      return .defaults
    }
    do {
      // 把保存的 JSON 数据还原成 UserSettings。
      return try JSONDecoder().decode(
        UserSettings.self,
        from: data
      )
    } catch {
      // 数据损坏或结构发生变化时，
      // 不让 App 启动失败，先回退到默认设置。
      return .defaults
    }
  }

  func save(_ settings: UserSettings) {
    do {
      // 把 UserSettings 转换成 JSON 二进制数据
      let data = try JSONEncoder().encode(settings)
      // 保存到当前 App 的 UserDefaults
      userDefaults.set(data, forKey: storageKey)
    } catch {
      print("保存用户设置失败\(error)")
    }
  }
}
