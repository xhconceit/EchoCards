import SwiftUI

struct CardCreateView: View {
  // 读取共享业务状态
  @Environment(AppModel.self)
  private var appModel

  // 调用 dismiss() 可以关闭当前弹窗
  @Environment(\.dismiss)
  private var dismiss

  // 新卡片属于那个卡组
  let deckID: UUID

  // 保存成功后的回调
  //
  // () -> Void 表示
  // 不接收参数 也不返回结果的函数
  let onSaved: () -> Void

  // @State 保存输入框的当前内容
  // $title 等写法提供可读写的 Binding
  @State private var title = ""
  @State private var content = ""
  @State private var speechText = ""
  @State private var memoryTip = ""

  @State private var isSaving = false
  @State private var errorMessage: String?

  var body: some View {
    VStack(alignment: .leading, spacing: 16) {
      Text("新建卡片")
        .font(.title2)
        .fontWeight(.semibold)

      TextField("标题（必填）", text: $title)
        .textFieldStyle(.roundedBorder)

      VStack(alignment: .leading, spacing: 6) {
        Text("正文（必填）")
        // TextEditor 是支持多行输入的文本编辑器
        TextEditor(text: $content)
          .frame(height: 100)
      }

      TextField("朗读文本（可选，留空使用正文）", text: $speechText)
        .textFieldStyle(.roundedBorder)

      VStack(alignment: .leading, spacing: 6) {
        Text("快速记忆点（可选）")
        TextEditor(text: $memoryTip).frame(height: 70)
      }

      // 可选值有内容时才显示错误文字
      if let errorMessage {
        Text(errorMessage)
          .font(.callout)
          .foregroundStyle(.red)
      }

      HStack {
        Spacer()
        Button("取消") {
          dismiss()
        }
        // 按 Escape 相当 点击取消
        .keyboardShortcut(.cancelAction)

        Button(isSaving ? "保存中..." : "保存") {
          saveCard()
        }
        .buttonStyle(.borderedProminent)
      }
    }
    .padding(24)
    .frame(width: 440)
    // 保存期间禁用输入框和按钮
    .disabled(isSaving)
    // 保存期间禁止通过系统交互直接关闭弹窗
    .interactiveDismissDisabled(isSaving)
  }

  private func saveCard() {
    // 防止重复启动保存任务
    guard !isSaving else {
      return
    }

    isSaving = true
    errorMessage = nil

    // 创建异步任务 以便使用 await 等待保存
    //
    // @MainActor 确保界面状态在主执行器上修改
    Task { @MainActor in
      do {
        try await appModel.createCard(
          deckID: deckID, title: title, content: content, speechText: speechText,
          memoryTip: memoryTip)

        // 调用详情页传入的函数，通知它刷新卡片
        onSaved()

        // 保存成功关闭弹窗
        dismiss()
      } catch {
        // 失败时保留用户输入，显示错误以便重试。
        errorMessage = error.localizedDescription
      }

      isSaving = false

    }
  }
}
