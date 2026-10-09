import Foundation
import Observation

@Observable  // 属性变化时，使用他的 SwiftUI 界面自动刷新
@MainActor  // 这个类型只能在主线程中操作
final class StudyViewModel {
  private(set) var currentIndex = 0
  private(set) var isShowingBack = false

  // 当前正在学习的全部卡片
  //
  // private(set) 表示外部界面可以读取
  // 但只能通过 StudyViewModel 的方法修改
  //
  // 使用 var 而不是 let
  // 因为用户选择其他卡组时需要替换卡片数组
  private(set) var cards: [Card]

  init(
    cards: [Card]
  ) {
    self.cards = cards
  }

  convenience init() {

    let sampleDeckID = UUID()

    self.init(
      cards: [
        Card(
          deckID: sampleDeckID,
          title: "Apple",
          content: "苹果是一种常见水果。",
          speechText: "Apple",
          memoryTip: "联想苹果公司的标志。",
          position: 0
        ),
        Card(
          deckID: sampleDeckID,
          title: "Book",
          content: "书籍用于记录和传播知识。",
          speechText: "Book",
          memoryTip: "联想一本打开的书。",
          position: 1
        ),
      ]
    )
  }

  var currentCard: Card? {
    guard cards.indices.contains(currentIndex) else {
      return nil
    }
    return cards[currentIndex]
  }

  func flipCard() {
    guard !cards.isEmpty else {
      return
    }
    isShowingBack.toggle()
  }

  func showPreviousCard() {

    guard !cards.isEmpty else {
      return
    }

    currentIndex = (currentIndex - 1 + cards.count) % cards.count
    isShowingBack = false
  }

  func showNextCard() {
    guard !cards.isEmpty else {
      return
    }
    currentIndex = (currentIndex + 1) % cards.count
    isShowingBack = false
  }

  // 使用另一个卡组的卡片替换当前内容
  func replaceCards(
    with newCards: [Card]
  ) {
    // 保存新的卡组数组
    cards = newCards
    // 切换卡组后从第一张开始
    currentIndex = 0
    // 切换卡组后始终显示正面
    isShowingBack = false
  }

}
