import Foundation
import Testing

@testable import EchoCardsMacApp

@MainActor
struct StudyViewModelTests {
  @Test
  func flipCardTogglesTheVisibleSide() {
    let viewModel = StudyViewModel()

    #expect(viewModel.isShowingBack == false)

    viewModel.flipCard()

    #expect(viewModel.isShowingBack == true)

    viewModel.flipCard()

    #expect(viewModel.isShowingBack == false)
  }

  @Test
  func nextCardWrapsToTheBeginning() {
    let viewModel = StudyViewModel()

    #expect(viewModel.currentIndex == 0)
    #expect(viewModel.currentCard?.title == "Apple")

    viewModel.showNextCard()

    #expect(viewModel.currentIndex == 1)
    #expect(viewModel.currentCard?.title == "Book")

    viewModel.showNextCard()

    #expect(viewModel.currentIndex == 0)
    #expect(viewModel.currentCard?.title == "Apple")
  }

  @Test
  func replaceCardsRestoresPreferredCard() {
    let viewModel = StudyViewModel()
    let preferredCard = viewModel.cards[1]

    viewModel.replaceCards(
      with: viewModel.cards,
      preferredCardID: preferredCard.id
    )

    #expect(viewModel.currentIndex == 1)
    #expect(viewModel.currentCard?.id == preferredCard.id)
  }

  @Test
  func replaceCardsFallsBackWhenPreferredCardWasDeleted() {
    let viewModel = StudyViewModel()

    viewModel.replaceCards(
      with: viewModel.cards,
      preferredCardID: UUID()
    )

    #expect(viewModel.currentIndex == 0)
    #expect(viewModel.currentCard?.title == "Apple")
  }
}
