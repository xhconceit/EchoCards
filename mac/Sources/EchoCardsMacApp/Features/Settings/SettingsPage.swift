import SwiftUI

// SettingsPage 负责应用偏好设置
// 它只修改 AppModel 中的 settings 保存由 AppModel 自动处理

struct SettingsPage: View {
    // 读取全应用共享的状态
    @Environment(AppModel.self)
    private var appModel

    var body: some View {
        // 让 Toggle Slider Picker 能够双向绑定到 Settings
           @Bindable var appModel = appModel

        Form {

            Section("浮动学习窗口") {
                Toggle("在所有桌面空间显示", isOn: $appModel.settings.appearsOnAllSpaces)

                Toggle("允许拖动浮动窗口", isOn: $appModel.settings.allowsDragging)

                Toggle("显示控制按钮", isOn: $appModel.settings.showsControls)

                Toggle("启动时恢复浮动窗口", isOn: $appModel.settings.restoresFloatingWindow)
            }

            Section("窗口外观") {
                Slider(
                    value: $appModel.settings.floatingWindowWidth,
                    in: 280...800,
                    step: 10
                ) {
                    Text("窗口宽度")
                }

                Text("\(appModel.settings.floatingWindowWidth, specifier: "%.0f") pt")
                    .foregroundStyle(.secondary)

                Slider(
                    value: $appModel.settings.floatingWindowOpacity,
                    in: 0.15...1.0,
                    step: 0.05,
                ) {
                    Text("背景透明度")
                }

                Text("\(appModel.settings.floatingWindowOpacity * 100, specifier: "%.0f")%")
                    .foregroundStyle(.secondary)

                Slider(
                    value: $appModel.settings.cardFontSize,
                    in: 16...48,
                    step: 1
                ) {
                    Text("卡片文字大小")
                }

                Text(
                    "\(appModel.settings.cardFontSize, specifier: "%.0f") pt"
                )
                .foregroundStyle(.secondary)
            }

            Section("学习") {
                Picker(
                    "默认学习模式",
                    selection: $appModel.settings.defaultLearningMode
                ) {
                    ForEach(LearningMode.allCases) { mode in
                        Text(mode.displayName)
                            .tag(mode)
                    }
                }

                Slider(
                    value: $appModel.settings.speechRate,
                    in: 0.5...2.0,
                    step: 0.1
                ) {
                    Text("朗读速度")
                }

                Text(
                    "\(appModel.settings.speechRate, specifier: "%.1f") 倍"
                )
                .foregroundStyle(.secondary)

                // 数据层以以毫秒保存；界面用秒显示。
                Slider(
                    value: Binding(
                        get: {
                            Double(appModel.settings.autoAdvanceDelayMilliseconds) / 1000
                        },
                        set: { seconds in
                            appModel.settings.autoAdvanceDelayMilliseconds = Int(
                                seconds * 1000
                            )
                        }
                    ),
                    in: 0.5...10.0,
                    step: 0.5
                ) {
                    Text("自动翻页延迟")
                }

                Text("\(Double(appModel.settings.autoAdvanceDelayMilliseconds) / 1000, specifier: "%.1f") 秒")
                .foregroundStyle(.secondary)
            }

            Section {
                Button("恢复默认设置", role: .destructive) {
                    // 恢复 UserSettings.defaults 中定义的全部默认值
                    appModel.resetSettings()
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle("设置")
        .padding()
        
    
    }
}
