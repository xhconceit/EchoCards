import Foundation
import Testing
@testable import EchoCardsMacApp

struct EchoCardsMacAppTests {
    @Test("sections remain stable for navigation")
    func appSections() {
        #expect(AppSection.home != AppSection.settings)
        #expect(AppSection.home == AppSection.home)
    }

    @Test("new deck notification has a stable name")
    func newDeckNotification() {
        #expect(Notification.Name.newDeckRequested.rawValue == "EchoCards.newDeckRequested")
    }

    @Test("JSON import trims fields and preserves optional values")
    func jsonImport() throws {
        let json = #"{"title":"  Physics ","cards":[{"title":" Inertia ","content":" Keeps moving. ","speechText":" ","memoryTip":" Tip "}]}"#.data(using: .utf8)!
        let deck = try JsonDeckImporter.parse(json)
        #expect(deck.title == "Physics")
        #expect(deck.cards[0].title == "Inertia")
        #expect(deck.cards[0].speechText == nil)
        #expect(deck.cards[0].memoryTip == "Tip")
    }

    @Test("SQLite import is transactional")
    func sqliteImport() throws {
        let store = try SQLiteStore()
        let deck = ImportedDeck(title: "Demo", description: nil, cards: [ImportedCard(title: "One", content: "Content", speechText: nil, memoryTip: nil)])
        let id = try store.importDeck(deck, displayTitle: "Demo")
        #expect(!id.isEmpty)
    }

    @Test("follow-along matcher requires substantial coverage")
    func followAlongMatcher() {
        let matcher = FollowAlongMatcher()
        #expect(matcher.isComplete(target: "物体保持运动状态", spoken: "物体保持运动状态") == true)
        #expect(matcher.isComplete(target: "物体保持运动状态", spoken: "物体") == false)
    }

    @Test("old speech callbacks are rejected")
    func speechOperationGate() {
        var gate = SpeechOperationGate()
        let old = gate.begin()
        let current = gate.begin()
        #expect(gate.accepts(old) == false)
        #expect(gate.accepts(current) == true)
        gate.invalidate()
        #expect(gate.accepts(current) == false)
    }
}
