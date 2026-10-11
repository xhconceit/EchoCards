import SwiftUI

// MainView 只管理主窗口布局和页面切换。
// 具体业务分别交给 DecksPage、SettingsPage 等页面。
struct MainView: View {

  // 记录左侧导航栏当前选中的页面
  // 初始进入 卡组 页面
  @State private var sidebarSelection: SidebarItem = .decks

  var body: some View {
    // NavigationSplitView 把窗口分成左侧导航栏和右侧内容区
    NavigationSplitView {
      // 左侧显示我们我们刚刚创建的 SidebarView
      SidebarView(selection: $sidebarSelection)
    } detail: {
      // 右侧：根据当前选中的导航项目切换页面。
      switch sidebarSelection {
      case .decks:
        // 卡组浏览、导入、导出和删除都在独立页面中。
        DecksPage()
      case .settings:
       // 设置页独立管理浮动窗口和学习偏好。
SettingsPage()
      }
    }
  }
}
