import SwiftUI

struct DeckEditView: View {
  @Environment(AppModel.self)
  private var appModel

  // dismiss 是 SwiftUI 提供的关闭动作
  // 在 sheet 弹窗中调用它，会关闭当前弹窗
  @Environment(\.dismiss)
  private var dismiss

  // 正在编辑的原始卡组
  let deck: Deck

  // 使用独立的编辑状态
  // 用户点击“保存”前，不修改正式卡组
  @State private var title: String
  @State private var description: String

  // 保存期间禁用按钮，避免重复提交
  @State private var isSaving = false

  // 保存失败时显示错误
  @State private var errorMessage: String?

  init(deck: Deck) {
    self.deck = deck

    // 初始化 @State 时使用带下划线的包装器
    _title = State(initialValue: deck.title)

    // TextField 需要 String， 不能直接绑定 String？
    // 因此没有说明时是使用空字符串
    _description = State(
      initialValue: deck.description ?? ""
    )
  }

  var body: some View {
    VStack(alignment: .leading, spacing: 16) {
      Text("编辑卡组")
        .font(.title2)
        .fontWeight(.semibold)

      // $title 时双向绑定
      // 输入框显示 title 输入后也会修改 title
      TextField("卡组名称", text: $title)

      TextField("卡组说明(可选)", text: $description)

      if let errorMessage {
        Text(errorMessage)
          .foregroundStyle(.red)
          .font(.callout)
      }

      HStack {
        Spacer()

        Button("取消") {
          // 没有调用 updateDeck
          // 因此取消不会保存输入内容
          dismiss()
        }
        .keyboardShortcut(.cancelAction)
        Button(isSaving ? "保存中..." : "保存") {
          isSaving = true
          errorMessage = nil
          // Task 允许在按钮的同步闭包中调用异步函数
          Task { @MainActor in
            do {
              try await appModel.updateDeck(deck, title: title, description: description)
              // 只有保存成功才关闭弹窗
              dismiss()
            } catch {
              // 保存失败时保留输入 方便修改后重试
              errorMessage = error.localizedDescription
            }
            isSaving = false
          }
        }
        // 按 Return 相当点击保存
        .keyboardShortcut(.defaultAction)

      }
    }
    .padding(24)
    .frame(width: 380)

    // 保存期间禁用整个表单 防止输入和关闭操作
    .disabled(isSaving)
  }

}
