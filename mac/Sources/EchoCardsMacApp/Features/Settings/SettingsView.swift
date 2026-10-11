import SwiftUI

// SettingsView 只负责显示设置页面
// 设置的真正保存仍然由 AppModel 负责
struct SettingsView: View {

    // 从 SwiftUI 环境读取共享的 AppModel
    @Environment(AppModel.self)
    private var appModel

    var body: some View {
        // @Bindable 允许控价直接绑定到 appModel.settings
        @Bindable var appModel = appModel
        Form {
            Section("浮动窗口") {
                // 是否让浮动窗口出现在所有桌面空间
                Toggle("在所有桌面空间显示", isOn: $appModel.settings.appearsOnAllSpaces)

                // 是否允许拖动浮动窗口
                Toggle("允许拖动浮动窗口", isOn: $appModel.settings.allowsDragging)

                // 是否显示上一张 翻面 下一张
                Toggle("显示控制按钮", isOn: $appModel.settings.showsControls)
            }

            Section("学习") {
                // 默认学习模式
                Picker("默认学习模式", selection: $appModel.settings.defaultLearningMode) {
                    ForEach(LearningMode.allCases) { mode in 
                        Text(mode.displayName)
                        .tag(mode)
                    }
                }

                // 朗读速度
                Slider(
                    value: $appModel.settings.speechRate,
                    in: 0.5...2.0,
                    step: 0.1
                ) {
                    Text("朗读速度")
                }

                // 显示当前朗读速度
                Text("\(appModel.settings.speechRate, specifier: "%.1f") 倍")
                .foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
        .navigationTitle("设置")
        .padding()
    }
}