import SwiftUI

// 动学习窗口的内容。
struct FloatingStudyView: View {

  // 从环境读取 App 级共享状态和用户设置
  @Environment(AppModel.self) private var appModel

  // 使用 AppModel 长期持有的共享学习状态
  //
  // 这里不在创建新的 StudyViewModel
  // 否则浮动窗口和全局快捷键会操作两个不同实例
  //
  // 使用计算属性可以保留当前代码中的viewModel 名称
  // 下面显示卡片和切换卡片的代码不需要全部重命名
  private var viewModel: StudyViewModel {
    appModel.studyViewModel
  }

  // 关闭指定的浮动学习窗口。
  @Environment(\.dismissWindow) private var dismissWindow

  // 鼠标位于浮动窗口内容区域时显示关闭按钮。
  @State private var isPointerHovering = false

  // 保存读取卡片失败时的提示文字
  //
  // nil 表示当前没有加载错误
  @State private var loadErrorMessage: String?

  // SwiftUI 实际测量到的卡片内容高度。
  // 卡片文字或控制按钮变化时，这个值会自动更新。
  @State private var measuredContentHeight: CGFloat = 0

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
          title: currentCard.title,
          // 正面显示正文，背面显示内容类型说明。
          subtitle: viewModel.isShowingBack
          ? currentCard.memoryTipText
            : currentCard.content,
          fontSize: appModel.settings.cardFontSize
        )
        // 只占满可用宽度，不强行占满无限高度。
        // 这样多行文字的真实高度才能传递给外层窗口。
        .frame(maxWidth: .infinity)
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
    // 在添加外层最小高度之前测量内容本身需要的高度。
    // 额外的 48 pt 来自下面四周各 24 pt 的 padding。
    .onGeometryChange(for: CGFloat.self) { proxy in
      proxy.size.height
    } action: { newHeight in
      measuredContentHeight = newHeight
    }
    .padding(24)
    // 使用用户设置控制浮动窗口内容尺寸。
    //
    // 因为这个 Window 使用 plain 样式，
    // 内容尺寸变化时窗口也会跟着调整。
    // 宽度保持用户设置的值。
    .frame(
      width: appModel.settings.floatingWindowWidth,
      alignment: .top
    )
    // 设置值只作为最小高度。
    // 当标题或正文换行后需要更多空间时，内容可以继续向下撑开。
    .frame(
      minHeight: appModel.settings.floatingWindowHeight,
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
        allowsDragging: appModel.settings.allowsDragging,
        // 设置高度是下限；文字需要更多空间时使用测量高度。
        contentSize: CGSize(
          width: appModel.settings.floatingWindowWidth,
          height: max(
            appModel.settings.floatingWindowHeight,
            measuredContentHeight + 48
          )
        )
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
      // 浮动窗口出现后，只更新窗口状态
      //
      // 全局快捷键不再由这个窗口启动
      // 而是由生命周期更长的 AppModel 统一管理
      appModel.markFloatingWindowOpen()
    }
    .onDisappear {
      // 关闭窗口只更新显示状态
      // 不停止应用级全局快捷键
      //
      // 因此窗口关闭后，显示浮动窗口快捷键
      // 仍然有机会继续工作
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
    // 使用普通纵向布局，让标题和正文的实际高度相加。
    // ZStack、无限高度和 Spacer 都不会再吞掉文字的固有高度。
    VStack(spacing: 12) {
        Text(title)
          .font(
            .system(size: fontSize)
          )
          .multilineTextAlignment(.center)
        // 标题变成多行时，让它的实际高度参与窗口尺寸计算。
        .fixedSize(horizontal: false, vertical: true)

      Text(subtitle)
        // 正文比标题稍小、颜色稍柔和，但仍保持足够的可读性。
        .font(
          .system(
            size: max(15, fontSize * 0.62),
            weight: .regular
          )
        )
        .foregroundStyle(Color.primary.opacity(0.72))
        .lineSpacing(2)
        .multilineTextAlignment(.center)
        // 保留换行后文字所需的完整纵向高度，避免被压缩或截断。
        .fixedSize(horizontal: false, vertical: true)
    }
    .frame(maxWidth: .infinity)
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
