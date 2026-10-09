import SwiftUI

// 动学习窗口的内容。
struct FloatingStudyView: View {

  // 从环境读取 App 级共享状态和用户设置
  @Environment(AppModel.self) private var appModel

  @State private var viewModel = StudyViewModel()
  @State private var keyboardMonitor = KeyboardMonitor()

  // 保存读取卡片失败时的提示文字
  //
  // nil 表示当前没有加载错误
  @State private var loadErrorMessage: String?

  var body: some View {
    VStack(spacing: 12) {
      // 浮动窗口的拖动把手
      // 用户按住它拖动时，移动整个窗口
      if appModel.settings.allowsDragging {
        Capsule()
          .fill(.secondary.opacity(0.45))
          .frame(width: 36, height: 5)
          .contentShape(Rectangle())
          .gesture(WindowDragGesture())
          .allowsWindowActivationEvents()
          .help("拖动浮动窗口")
      }

      // 如果加载失败，优先显示错误
      if let loadErrorMessage {
        ContentUnavailableView(
          "无法读取卡片",
          systemImage: "exclamationmark.triangle",
          description: Text(loadErrorMessage)
        )
      } else if let currentCard = viewModel.currentCard {
        TitleView(
          title: viewModel.isShowingBack
            ? currentCard.memoryTipText
            : currentCard.title,
          // 正面显示正文，背面显示内容类型说明。
          subtitle: viewModel.isShowingBack
            ? "快速记忆点"
            : currentCard.content,
          fontSize: appModel.settings.cardFontSize
        )
        .id("\(viewModel.currentIndex)-\(viewModel.isShowingBack)")
        .transition(.opacity.combined(with: .scale(scale: 0.96)))
        .contentShape(.rect)
        .onTapGesture {
          animate {
            viewModel.flipCard()
          }
        }

        Text("\(viewModel.currentIndex + 1) / \(viewModel.cards.count)")
          .font(.caption2)
          .foregroundStyle(.tertiary)

        if appModel.settings.showsControls {

          HStack(spacing: 8) {
            Button("上一张") {
              animate {
                viewModel.showPreviousCard()
              }
            }
            .buttonStyle(.glass)
            .focusable(false)

            FlipCardButton(isShowingBack: viewModel.isShowingBack) {
              animate {
                viewModel.flipCard()
              }
            }

            Button("下一张") {
              animate {
                viewModel.showNextCard()
              }
            }
            .buttonStyle(.glass)
            .focusable(false)
          }
        }
      } else {
        ContentUnavailableView(
          "暂无卡片",
          systemImage: "rectangle.stack",
          description: Text("请先添加或导入卡片")
        )
      }
    }
    .padding(24)
    // 使用用户设置控制浮动窗口内容尺寸。
    //
    // 因为这个 Window 使用 plain 样式，
    // 内容尺寸变化时窗口也会跟着调整。
    .frame(
      width: appModel.settings.floatingWindowWidth,
      height: appModel.settings.floatingWindowHeight
    )
    .background {
      // 使用独立视图绘制玻璃背景
      //
      // 透明度只作用于玻璃层
      // 不会让文字和按钮一起变透明
      Color.clear
        .glassEffect(
          in: .rect(cornerRadius: 28)
        )
        .opacity(
          appModel.settings.floatingWindowOpacity
        )
    }
    .containerBackground(.clear, for: .window)
    .background {
      FloatingWindowConfigurator(
        appearsOnAllSpaces: appModel.settings.appearsOnAllSpaces,
        allowsDragging: appModel.settings.allowsDragging
      )
      .frame(width: 0, height: 0)
    }
    .task(
      // id 是这个异步任务的身份标识
      //
      // selectedDeckId 改变时，SwiftUI 会：
      // 1. 取消旧卡组的任务
      // 2. 创建一个新任务
      // 3. 重新读取新卡组的卡片
      id: appModel.selectedDeckID
    ) {
      // 每次开始加载时，先清除上一次错误
      loadErrorMessage = nil

      do {
        // 根据 AppModel 当前的 selectedDeckID
        // 从 Repository 读取卡片
        let cards = try await appModel.fetchSelectedDeckCards()

        // await 期间用户可能已经选择了另一个卡组
        //
        // Task.isCancelled 为 true 时
        // 表示当前任务已经过期
        // 旧结果不应该在修改界面
        guard !Task.isCancelled else {
          return
        }

        // 把真实卡片交给 StudyViewModel
        //
        // replaceCards 会同时回到第一张和正面
        viewModel.replaceCards(
          with: cards
        )
      } catch is CancellationError {
        // 卡组快速切换导致任务取消属于正常情况
        // 不需要展示给用户
      } catch {
        // 其他仓库才显示在浮动窗口中
        loadErrorMessage = error.localizedDescription
        // 加载失败后清空旧卡组的卡片
        // 防止界面继续显示错误的旧数据

        viewModel.replaceCards(with: [])
      }
    }
    .onAppear {

      appModel.markFloatingWindowOpen()

      keyboardMonitor.start { keyCode in
        switch keyCode {
        case 123:
          animate {
            viewModel.showPreviousCard()
          }
          return true
        case 124:
          animate {
            viewModel.showNextCard()
          }
          return true
        case 49:
          animate {
            viewModel.flipCard()
          }
          return true
        default:
          return false
        }
      }

    }
    .onDisappear {
      keyboardMonitor.stop()

      // 关闭窗口只更新显示状态
      // 不清除 selectedDeckID
      appModel.markFloatingWindowClosed()
    }
  }

  private func animate(_ action: () -> Void) {
    withAnimation(.easeInOut(duration: 0.2)) {
      action()
    }
  }

}

struct TitleView: View {
  let title: String
  let subtitle: String

  // 主标题使用的字号
  let fontSize: Double

  var body: some View {
    VStack(spacing: 8) {
      Text(title)
        .font(
          .system(size: fontSize)
        )
      Text(subtitle)
        .font(.caption)
        .foregroundStyle(.secondary)
    }
  }
}

struct FlipCardButton: View {
  let isShowingBack: Bool
  let action: () -> Void

  var body: some View {
    Button(isShowingBack ? "显示正面" : "显示背面") {
      action()
    }
    .buttonStyle(.glass)
    .focusable(false)
  }
}
