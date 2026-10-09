import SwiftUI

// DeckDetailView 显示一个卡组及其全部卡片
struct DeckDetailView: View {
  // 共享的 AppModel
  @Environment(AppModel.self)
  private var appModel

  // 打开浮动学习窗口
  @Environment(\.openWindow)
  private var openWindow

  // 当前详情页显示的卡组
  let deck: Deck

  // 从 SQLite 读取出来的卡片
  @State private var cards: [Card] = []

  // 是否正在读取卡片
  @State private var isLoading = true

  // 读取失败时的提示文字
  @State private var errorMessage: String?

  // 计算属性：每次读取时都会执行这里的代码
  //
  // 根据卡组ID 从共享状态中查找最新的卡组信息
  private var currentDeck: Deck {
    // first(where:) 返回第一个符合条件的卡组
    //
    // 找不到时返回 nil 使用 ?? 回退到进入页面时的副本
    appModel.decks.first { storedDeck in
      storedDeck.id == deck.id
    } ?? deck
  }

  // 准备在详情页中编辑的卡组
  // nil 时不显示弹窗
  @State private var deckPendingEditing: Deck?

  var body: some View {
    VStack(spacing: 16) {
      // 顶部卡组信息
      VStack(spacing: 6) {
        Text(currentDeck.title)
          .font(.largeTitle)
          .fontWeight(.semibold)

        // 使用最新的说明，而不是原始 deck 副本

        if let description = currentDeck.description {
          Text(description)
            .foregroundStyle(.secondary)
        }

        // Text(deck.title)
        //   .font(.largeTitle)
        //   .fontWeight(.semibold)
        //
        // if let description = deck.description {
        //   Text(description)
        //     .foregroundStyle(.secondary)
        // }
      }

      Button("在浮动窗口中学习") {
        // 先把这个卡组设为当前卡组
        appModel.selectDeck(deck.id)

        // 打开或激活唯一浮动窗口
        openWindow(
          id: "floating-study-window"
        )

        appModel.markFloatingWindowOpen()
      }
      .buttonStyle(.borderedProminent)

      if isLoading {
        // 数据库查询尚未完成时显示进度
        ProgressView("正在读取卡片")
          .frame(
            maxWidth: .infinity,
            maxHeight: .infinity
          )
      } else if let errorMessage {
        // 查询失败时显示错误
        ContentUnavailableView(
          "无法读取卡片",
          systemImage: "exclamationmark.triangle",
          description: Text(errorMessage)
        )
      } else if cards.isEmpty {
        // 卡组存在但没有卡片时显示空状态
        ContentUnavailableView(
          "这个卡组还没有卡片",
          systemImage: "rectangle.stack.badge.plus"
        )
      } else {
        // List 是 SwiftUI 的系统列表组件
        //
        // Card 遵守 Identifiable
        // SwiftUI 会使用 card.id 区分每一行
        List(cards) { card in
          VStack(
            alignment: .leading,
            spacing: 5,
          ) {
            Text(card.title)
              .font(.headline)

            Text(card.content)
              .font(.callout)
              .foregroundStyle(.secondary)
              .lineLimit(2)
          }
          .padding(.vertical, 4)
        }
      }
    }
    .padding(20)
    // deck.id 改变时重新执行任务
    .task(id: deck.id) {
      isLoading = true
      errorMessage = nil

      do {
        let loadedCards = try await appModel.fetchCards(deckID: deck.id)

        // 任务被取消时不要使用旧结果
        guard !Task.isCancelled else {
          return
        }
        cards = loadedCards
      } catch is CancellationError {
        // 页面切换导致的取消不显示错误
      } catch {
        errorMessage = error.localizedDescription
      }
      isLoading = false
    }
    .toolbar {
      // toolbar 将按钮发到窗口的导航工具栏中
      ToolbarItem {
        Button("编辑卡组", systemImage: "pencil") {
          // 编辑时 使用最新的卡组数据
          deckPendingEditing = currentDeck
        }
      }
    }
    .sheet(item: $deckPendingEditing) { editingDeck in
      // 保存成功后， AppModel 会重新加载 decks
      // currentDeck  随之取得最新的名称和说明
      DeckEditView(deck: editingDeck)
        .environment(appModel)
    }

  }

}
