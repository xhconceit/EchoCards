import Foundation
import Testing

@testable import EchoCardsMacApp

struct AppSessionStoreTests {
  @Test
  func savesCurrentCardSeparatelyForEachDeck() throws {
    let suiteName = "AppSessionStoreTests.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { defaults.removePersistentDomain(forName: suiteName) }

    let store = AppSessionStore(userDefaults: defaults)
    let firstDeckID = UUID()
    let secondDeckID = UUID()
    let firstCardID = UUID()
    let secondCardID = UUID()

    store.saveCurrentCardID(firstCardID, for: firstDeckID)
    store.saveCurrentCardID(secondCardID, for: secondDeckID)

    #expect(store.loadCurrentCardID(for: firstDeckID) == firstCardID)
    #expect(store.loadCurrentCardID(for: secondDeckID) == secondCardID)
  }
}
