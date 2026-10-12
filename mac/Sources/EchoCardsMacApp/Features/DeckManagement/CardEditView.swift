import SwiftUI

// CardEditView 用于编辑一张已经存在的卡片
struct CardEditView: View {
  // 从环境中取得共享状态
  @Environment(AppModel.self)
  private var appModel

  // 用于关闭当前 sheet
  @Environment(\.dismiss)
  private var dismiss

  // 保存正在编辑的原始卡片
  let card: Card

  // 保存成功后通知详情页重新读取卡片
  let onSaved: () -> Void

  // 表单的本地编辑状态
  // 用户点击保存前，这些输入不会修改卡片
  @State private var title: String
  @State private var content: String
  @State private var speechText: String
  @State private var memoryTip: String

  // 控制保存状态和错误提示
  @State private var isSaving = false
  @State private var errorMessage: String?

  init(
    card: Card,
    onSaved: @escaping () -> Void
  ) {
    self.card = card
    self.onSaved = onSaved

    // 使用原卡片内容初始化输入框
    _title = State(initialValue: card.title)
    _content = State(initialValue: card.content)

    // TextField 和 TextEditor 需要 String
    // 所以可选字段为 nil 时使用空字符串
    _speechText = State(
      initialValue: card.speechText ?? ""
    )
    _memoryTip = State(
      initialValue: card.memoryTip ?? ""
    )
  }

  var body: some View {
    VStack(alignment: .leading, spacing: 16) {
      Text("编辑卡片")
        .font(.title2)
        .fontWeight(.semibold)

      TextField(
        "标题（必填）",
        text: $title
      )
      .textFieldStyle(.roundedBorder)

      VStack(alignment: .leading, spacing: 6) {
        Text("正文（必填）")

        TextEditor(text: $content)
          .frame(height: 100)
      }

      TextField(
        "朗读文本（可选，留空使用正文）",
        text: $speechText
      )
      .textFieldStyle(.roundedBorder)

      VStack(alignment: .leading, spacing: 6) {
        Text("快速记忆点（可选）")
        TextEditor(text: $memoryTip)
          .frame(height: 70)
      }

      // 保存失败后显示错误信息
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
        .keyboardShortcut(.cancelAction)

        Button(isSaving ? "保存中..." : "保存") {
          saveCard()
        }
        .buttonStyle(.borderedProminent)
        .keyboardShortcut(.defaultAction)
      }
    }
    .padding(24)
    .frame(width: 440)

    // 保存期间禁止继续编辑或重复点击
    .disabled(isSaving)

    // 保存期间禁止直接关闭弹窗
    .interactiveDismissDisabled(isSaving)
  }

  // 保存用户对卡片所做的修改
  private func saveCard() {
    // 防止用户连续点击保存，启动多个保存任务
    guard !isSaving else {
      return
    }

    isSaving = true
    errorMessage = nil

    // AppModel 的更新方式是异步方法
    // 因此使用 TASk 启动异步保存
    Task { @MainActor in
      do {
        try await appModel.updateCard(
          card,
          title: title,
          content: content,
          speechText: speechText,
          memoryTip: memoryTip
        )
        // 通知详情页重新读取卡片
        onSaved()
        // 保存成功后关闭编辑弹窗
        dismiss()
      } catch {
        // 保存失败
        errorMessage = error.localizedDescription
      }
      isSaving = false
    }
  }
}
