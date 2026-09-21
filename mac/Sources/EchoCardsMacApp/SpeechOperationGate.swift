import Foundation

struct SpeechOperationGate {
    private(set) var activeID: UUID?
    mutating func begin() -> UUID { let id = UUID(); activeID = id; return id }
    mutating func invalidate() { activeID = nil }
    func accepts(_ id: UUID) -> Bool { activeID == id }
}
