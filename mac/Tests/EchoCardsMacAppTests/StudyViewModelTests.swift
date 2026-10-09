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
    #expect(viewModel.currentCard.front == "Apple")

    viewModel.showNextCard()

    #expect(viewModel.currentIndex == 1)
    #expect(viewModel.currentCard.front == "Book")

    viewModel.showNextCard()

    #expect(viewModel.currentIndex == 0)
    #expect(viewModel.currentCard.front == "Apple")
  }
}
