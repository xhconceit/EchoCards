import Foundation

enum LearningMode: String, Codable { case manual, followAlong = "follow_along", autoPlay = "auto_play" }

struct ImportedCard: Codable, Equatable {
    let title: String
    let content: String
    let speechText: String?
    let memoryTip: String?
}

struct ImportedDeck: Codable, Equatable {
    let title: String
    let description: String?
    let cards: [ImportedCard]
}

enum ImportError: LocalizedError, Equatable {
    case fileTooLarge
    case invalidJSON
    case invalidField(String)
    case cardLimitExceeded

    var errorDescription: String? {
        switch self {
        case .fileTooLarge: "文件不能超过 2 MiB"
        case .invalidJSON: "JSON 格式无效"
        case .invalidField(let field): "字段无效：\(field)"
        case .cardLimitExceeded: "卡片数量必须在 1 到 1000 张之间"
        }
    }
}

struct JsonDeckImporter {
    static let maximumBytes = 2 * 1024 * 1024

    static func parse(_ data: Data) throws -> ImportedDeck {
        guard data.count <= maximumBytes else { throw ImportError.fileTooLarge }
        let decoder = JSONDecoder()
        let deck: ImportedDeck
        do { deck = try decoder.decode(ImportedDeck.self, from: data) }
        catch { throw ImportError.invalidJSON }
        return try validate(deck)
    }

    static func validate(_ deck: ImportedDeck) throws -> ImportedDeck {
        guard !deck.title.trimmed.isEmpty else { throw ImportError.invalidField("title") }
        guard !deck.cards.isEmpty, deck.cards.count <= 1000 else { throw ImportError.cardLimitExceeded }
        for (index, card) in deck.cards.enumerated() {
            guard !card.title.trimmed.isEmpty else { throw ImportError.invalidField("cards[\(index)].title") }
            guard !card.content.trimmed.isEmpty else { throw ImportError.invalidField("cards[\(index)].content") }
        }
        return ImportedDeck(
            title: deck.title.trimmed,
            description: deck.description?.trimmed.nilIfEmpty,
            cards: deck.cards.map { ImportedCard(title: $0.title.trimmed, content: $0.content.trimmed, speechText: $0.speechText?.trimmed.nilIfEmpty, memoryTip: $0.memoryTip?.trimmed.nilIfEmpty) }
        )
    }

    static func fingerprint(_ deck: ImportedDeck) -> String {
        let canonical: [String: Any] = [
            "title": deck.title,
            "description": deck.description ?? "",
            "cards": deck.cards.map { ["title": $0.title, "content": $0.content, "speechText": $0.speechText ?? "", "memoryTip": $0.memoryTip ?? ""] }
        ]
        let data = try! JSONSerialization.data(withJSONObject: canonical, options: [.sortedKeys])
        return data.map { String(format: "%02x", $0) }.joined()
    }
}

private extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
