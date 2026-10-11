import SwiftUI
import UniformTypeIdentifiers

// MainView 只管理主窗口布局和页面切换。
// 具体业务分别交给 DecksPage、SettingsPage 等页面。
struct MainView: View {

  //SwiftUI 提供关闭的指定窗口动作
  //
  // 删除当前学习卡组后
  // 用它关闭浮动学习窗口
  @Environment(\.dismissWindow)
  private var dismissWindow

  // 从 SwiftUI 环境中取得 App 级共享状态
  @Environment(AppModel.self) private var appModel

  // SwiftUI 提供的窗口打开的动作
  //
  // 传入 Window 声明时使用的固定ID
  // 系统会打开或激活对应窗口
  @Environment(\.openWindow) private var openWindow

  // 记录左侧导航栏当前选中的页面
  // 初始进入 卡组 页面
  @State private var sidebarSelection: SidebarItem = .decks

  // 保存准备编辑的卡组
  //
  // nil: 不显示编辑弹窗
  // 有 Deck 显示这个卡组的编辑弹窗
  @State private var deckPendingEditing: Deck?

  // 控制系统保存文件窗口是否显示
  @State private var isShowingFileExporter = false

  // 保存等待写入磁盘的 JSON 文件对象
  //
  // nil 表示当前没有准备导出的文件
  @State private var exportDocument: JSONExportDocumnent?

  // 保存面板中默认显示的文件名
  @State private var exportFilename = "EchoCards"

  // 保存用户准备删除的卡组
  //
  // nil 表示当前没有等待确认的删除操作
  @State private var deckPendingDeletion: Deck?

  // 记录本次 MainView 生命周期中
  // 是否已经处理过启动恢复逻辑
  // 防止 SwiftUI 刷新界面时重复打开窗口
  @State private var didRestoreFloatingWindow = false

  // 控制系统文件选择窗口是否显示
  @State private var isShowingFileImporter = false

  //
  // 显示本次导入或校验的结果
  @State private var importStatusMessage: String?

  var body: some View {
    // 把环境中的 AppModel 转换成可以生成 Binding 的形式
    //
    // 有了 @Binding Toggle 才能通过 $appModel
    // 直接修改 settings 中的值
    @Bindable var appModel = appModel

    // NavigationSplitView 把窗口分成左侧导航栏和右侧内容区
    NavigationSplitView {
      // 左侧显示我们我们刚刚创建的 SidebarView
      SidebarView(selection: $sidebarSelection)
    } detail: {

      // 当前先只显示卡组页面。
      // 设置页面下一步再加入。
      DecksPage()
    }
  }
  // static 表示这个函数不依赖 DecksPage 的界面状态。
  // 把卡组名称转换成适合保存的文件名
  private static func safeFilename(from title: String) -> String {
    // 这些字符可能被文件路径解释为分隔符
    let forbiddenCharacters = CharacterSet(
      charactersIn: "/:\\"
    )

    // components 会根据禁止字符拆分字符串
    //
    // joined 再使用短横线连接各部分
    let filename = title.components(
      separatedBy: forbiddenCharacters
    )
    .joined(separator: "-")

    // 如果处理后名称为空，使用默认名称
    return filename.isEmpty ? "EchoCards" : filename
  }
}
