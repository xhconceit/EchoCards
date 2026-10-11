import SwiftUI

// SidebarItem 表示左侧栏中的一个导航项目
// 后面可以继续增加“设置”等页面
enum SidebarItem: Hashable {
    case decks
    case settings

    // 显示给用户看的标题
    var title: String {
        switch self {
        case .decks:
            return "卡组"
        case .settings:
            return "设置"
        }
    }

    // 使用 SF Symbols 作为 macOS 图标
    var icon: String {
        switch self {
        case .decks:
            return "rectangle.stack"
        case .settings:
            return "gearshape"
        }
    }
}

// SidebarView 只负责左侧导航
// 它不读取 SQLite ，也不修改 AppModel
struct SidebarView: View {

    // selection 是父页面传进来的当前选中项目
    // 使用 Binding 后，点击菜单会自动通知父页面
    @Binding var selection: SidebarItem

    var body: some View {
        List(selection: $selection) {
            // 卡组入口
            Label(SidebarItem.decks.title, systemImage: SidebarItem.decks.icon)
            .tag(SidebarItem.decks)
            // 设置入口
            Label(SidebarItem.settings.title, systemImage: SidebarItem.settings.icon)
            .tag(SidebarItem.settings)
        }
        .navigationTitle("知声卡")
        .listStyle(.sidebar)
    }
}
