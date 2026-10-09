import SwiftUI

@main
struct EchoCardsMacApp: App {

  // 这里先声明属性，不立即创建 AppModel
  //
  // 因为创建 SQLite 数据库可能抛出错误
  // 需要在下面的 init 中使用 do / try / catch
  @State private var appModel: AppModel

  init() {
    do {
      // 创建 Application Support 目录
      // 打开数据库并初始化数据表
      let database = try AppDatabase.make()

      // 创建真正使用 SQLite 的卡组仓库
      //
      // repository 的具体类型时 SQLiteDeckRepository
      // 但他遵守 DeckRepository 协议
      let repository = SQLiteDeckRepository(database: database)

      // _appModel 表示访问 @State 属性包装器本身
      //
      // 不能在 init 中直接写：
      // appModel = AppModel(...)
      //
      // 需要使用 State(initialValue:) 初始化
      _appModel = State(
        initialValue: AppModel(
          deckRepository: repository
        )
      )
    } catch {
      // 当前仍是开发阶段
      //
      // 数据库无法创建时直接停止启动
      // 防止悄悄退回内存仓库，导致用户误以为
      // 数据已经保存，重启后全部消失
      fatalError("初始化知声卡数据库失败：\(error.localizedDescription)")
    }
  }

  var body: some Scene {
    WindowGroup("知声卡") {
      MainView()
        // 把共享状态放入 SwiftUI 环境
        // 当前窗口下的所有子 View 都可以读取同一个 AppModel
        .environment(appModel)
      // .frame(minWidth: 360, minHeight: 240)  //  内容允许的最小尺寸
    }
    .defaultSize(width: 420, height: 300)  // 窗口第一次出现时的默认尺寸
    // .windowStyle(.plain)

    Window("学习", id: "floating-study-window") {
      FloatingStudyView()
        .environment(appModel)
        .frame(
          minWidth: appModel.settings.floatingWindowWidth,
          minHeight: appModel.settings.floatingWindowHeight
        )
    }
    .defaultSize(
      width: appModel.settings.floatingWindowWidth,
      height: appModel.settings.floatingWindowHeight
    )
    // 使用无标题栏窗口，由我们自己控制浮动卡片外观。
    .windowStyle(.plain)
    // App 启动时先不自动显示。
    // 是否恢复窗口以后根据用户设置决定。
    .defaultLaunchBehavior(.suppressed)
  }
}
