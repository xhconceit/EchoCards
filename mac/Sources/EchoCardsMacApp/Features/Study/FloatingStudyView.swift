import SwiftUI

// 动学习窗口的内容。
struct FloatingStudyView: View {

  // 从环境读取 App 级共享状态和用户设置
  @Environment(AppModel.self) private var appModel

  // 关闭指定的浮动学习窗口。
  @Environment(\.dismissWindow) private var dismissWindow

  @State private var viewModel = StudyViewModel()
  @State private var keyboardMonitor = KeyboardMonitor()

  // 鼠标位于浮动窗口内容区域时显示关闭按钮。
  @State private var isPointerHovering = false

  // 保存读取卡片失败时的提示文字
  //
  // nil 表示当前没有加载错误
  @State private var loadErrorMessage: String?

  var body: some View {
    VStack(spacing: 12) {
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
        // 标题区域占用控制按钮之外的剩余空间，正文可以保持居中。
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .id("\(viewModel.currentIndex)-\(viewModel.isShowingBack)")
        .transition(.opacity.combined(with: .scale(scale: 0.96)))
        .contentShape(.rect)
        .onTapGesture {
          animate {
            viewModel.flipCard()
          }
        }

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
      height: appModel.settings.floatingWindowHeight,
      // 固定窗口尺寸后，内容从顶部开始排列。
      alignment: .top
    )
    .overlay(alignment: .topTrailing) {
      // 将进度固定在右上角，不占用卡片内容区域。
      if viewModel.currentCard != nil {
        Text("\(viewModel.currentIndex + 1) / \(viewModel.cards.count)")
          .font(.caption2)
          .foregroundStyle(.tertiary)
          // 进度文字不拦截点击和拖动手势。
          .allowsHitTesting(false)
          .padding(16)
      }
    }
    .overlay(alignment: .topLeading) {
      // 只在鼠标悬停时显示，保持学习窗口默认简洁。
      if isPointerHovering {
        Button {
          dismissWindow(id: "floating-study-window")
        } label: {
          Image(systemName: "xmark.circle.fill")
            .font(.title3)
            .foregroundStyle(.secondary)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("关闭浮动学习窗口")
        .padding(12)
      }
    }
    // 让窗口中的空白，文字和卡片区域都能接收拖动手势
    .contentShape(Rectangle())
    // 鼠标进入或离开时更新关闭按钮的显示状态。
    .onHover { isHovering in
      isPointerHovering = isHovering
    }
    // simultaneousGesture 不会取代卡片点击和按钮点击
    // 拖动功能由 allowsDragging 设置控制
    .simultaneousGesture(
      WindowDragGesture(),
      including: appModel.settings.allowsDragging ? .all : .none
    )
    // 窗口未激活时也允许直接开始拖动
    .allowsWindowActivationEvents()
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

      keyboardMonitor.start(
        shortcuts: appModel.settings.globalShortcuts
      ) { action in
        // 根据领域层动作控制当前学习卡片
        switch action {
        case .previousCard:
          animate {
            viewModel.showPreviousCard()
          }
        case .nextCard:
          animate {
            viewModel.showNextCard()
          }
        case .flipCard:
          animate {
            viewModel.flipCard()
          }
        case .showFloatingWindow, .hideFloatingWindow:
          // 显示和隐藏窗口属于应用级操作。
          // 后续由主窗口长期持有的全局监听器统一处理。
          break
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
    ZStack {
      // 正文始终位于可用区域中央。
      Text(subtitle)
        .font(.caption)
        .foregroundStyle(.secondary)
        .multilineTextAlignment(.center)
        .frame(maxWidth: .infinity, maxHeight: .infinity)

      // 标题覆盖在区域顶部，不随正文的居中布局移动。
      VStack {
      Text(title)
        .font(
          .system(size: fontSize)
        )
        .multilineTextAlignment(.center)

        Spacer(minLength: 0)
      }
      .frame(maxWidth: .infinity, maxHeight: .infinity)
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
