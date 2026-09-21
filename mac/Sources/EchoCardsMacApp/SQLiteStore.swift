import Foundation
import SQLite3

final class SQLiteStore {
    private var database: OpaquePointer?

    init(path: String = ":memory:") throws {
        guard sqlite3_open(path, &database) == SQLITE_OK else { throw StoreError.openFailed }
        try execute("PRAGMA foreign_keys = ON;")
        try createSchema()
    }

    deinit { sqlite3_close(database) }

    func execute(_ sql: String) throws {
        var error: UnsafeMutablePointer<CChar>?
        guard sqlite3_exec(database, sql, nil, nil, &error) == SQLITE_OK else {
            defer { sqlite3_free(error) }
            let message = error.map { String(cString: UnsafePointer($0)) } ?? "SQLite error"
            throw StoreError.queryFailed(message)
        }
    }

    func importDeck(_ deck: ImportedDeck, displayTitle: String, now: String = ISO8601DateFormatter().string(from: Date())) throws -> String {
        let id = UUID().uuidString
        let title = displayTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !title.isEmpty else { throw StoreError.invalidInput }
        let fingerprint = JsonDeckImporter.fingerprint(deck)
        try execute("BEGIN TRANSACTION")
        do {
            try insertDeck(id: id, title: title, description: deck.description, position: nextDeckPosition(), createdAt: now, fingerprint: fingerprint)
            for (index, card) in deck.cards.enumerated() {
                try insertCard(id: UUID().uuidString, deckId: id, card: card, position: index, now: now)
            }
            try execute("INSERT INTO deck_progress(deck_id,current_card_id,last_mode,updated_at) SELECT '\(id)', id, 'manual', '\(now)' FROM cards WHERE deck_id='\(id)' AND position=0")
            try execute("COMMIT")
            return id
        } catch {
            try? execute("ROLLBACK")
            throw error
        }
    }

    func decks() throws -> [DeckRecord] {
        try query("SELECT id,title,COALESCE(description,''),position FROM decks ORDER BY position,created_at") { statement in
            DeckRecord(id: String(cString: sqlite3_column_text(statement, 0)), title: String(cString: sqlite3_column_text(statement, 1)), description: String(cString: sqlite3_column_text(statement, 2)), position: Int(sqlite3_column_int(statement, 3)))
        }
    }

    func createDeck(title: String, description: String) throws -> String {
        let deck = ImportedDeck(title: title, description: description, cards: [ImportedCard(title: "第一张卡片", content: "请编辑这张卡片", speechText: nil, memoryTip: nil)])
        return try importDeck(deck, displayTitle: title)
    }

    func deleteDeck(id: String) throws { try bindAndExecute("DELETE FROM decks WHERE id = ?", [id]) }

    func saveCard(deckId: String, cardId: String?, title: String, content: String, speechText: String, memoryTip: String) throws {
        let cleanTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanContent = content.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanTitle.isEmpty, !cleanContent.isEmpty else { throw StoreError.invalidInput }
        let now = ISO8601DateFormatter().string(from: Date())
        if let cardId {
            try bindAndExecute("UPDATE cards SET title=?,content=?,speech_text=?,memory_tip=?,updated_at=? WHERE id=? AND deck_id=?", [cleanTitle, cleanContent, speechText.trimmedOrNull, memoryTip.trimmedOrNull, now, cardId, deckId])
        } else {
            let position = try cards(deckId: deckId).count
            try bindAndExecute("INSERT INTO cards VALUES (?,?,?,?,?,?,?,?,?,?)", [UUID().uuidString, deckId, cleanTitle, cleanContent, speechText.trimmedOrNull, memoryTip.trimmedOrNull, position, 1, now, now])
        }
    }

    func deleteCard(id: String) throws { try bindAndExecute("DELETE FROM cards WHERE id=?", [id]) }

    func settings() throws -> SettingsRecord {
        let rows = try query("SELECT default_mode,speech_rate,auto_advance_delay_ms FROM user_settings WHERE id=1") { s in SettingsRecord(mode: String(cString: sqlite3_column_text(s, 0)), rate: sqlite3_column_double(s, 1), delay: Int(sqlite3_column_int64(s, 2))) }
        return rows.first ?? SettingsRecord(mode: "manual", rate: 1.0, delay: 600)
    }

    func saveSettings(_ settings: SettingsRecord) throws {
        let now = ISO8601DateFormatter().string(from: Date())
        try bindAndExecute("INSERT INTO user_settings(id,default_mode,speech_rate,auto_advance_delay_ms,updated_at) VALUES(1,?,?,?,?) ON CONFLICT(id) DO UPDATE SET default_mode=excluded.default_mode,speech_rate=excluded.speech_rate,auto_advance_delay_ms=excluded.auto_advance_delay_ms,updated_at=excluded.updated_at", [settings.mode, settings.rate, settings.delay, now])
    }

    func cards(deckId: String) throws -> [CardRecord] {
        try query("SELECT id,title,content,COALESCE(speech_text,''),COALESCE(memory_tip,''),position FROM cards WHERE deck_id='\(deckId)' ORDER BY position") { statement in
            CardRecord(id: String(cString: sqlite3_column_text(statement, 0)), title: String(cString: sqlite3_column_text(statement, 1)), content: String(cString: sqlite3_column_text(statement, 2)), speechText: String(cString: sqlite3_column_text(statement, 3)), memoryTip: String(cString: sqlite3_column_text(statement, 4)), position: Int(sqlite3_column_int(statement, 5)))
        }
    }

    private func query<T>(_ sql: String, map: (OpaquePointer) -> T) throws -> [T] {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(database, sql, -1, &statement, nil) == SQLITE_OK else { throw StoreError.queryFailed(sql) }
        defer { sqlite3_finalize(statement) }
        var result: [T] = []
        while sqlite3_step(statement) == SQLITE_ROW { result.append(map(statement!)) }
        return result
    }

    private func createSchema() throws {
        try execute("""
        CREATE TABLE IF NOT EXISTS decks(id TEXT PRIMARY KEY, title TEXT NOT NULL CHECK(length(trim(title)) > 0), description TEXT, position INTEGER NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, import_fingerprint TEXT UNIQUE);
        CREATE TABLE IF NOT EXISTS cards(id TEXT PRIMARY KEY, deck_id TEXT NOT NULL REFERENCES decks(id) ON DELETE CASCADE, title TEXT NOT NULL CHECK(length(trim(title)) > 0), content TEXT NOT NULL CHECK(length(trim(content)) > 0), speech_text TEXT, memory_tip TEXT, position INTEGER NOT NULL, revision INTEGER NOT NULL CHECK(revision >= 1), created_at TEXT NOT NULL, updated_at TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS deck_progress(deck_id TEXT PRIMARY KEY REFERENCES decks(id) ON DELETE CASCADE, current_card_id TEXT, last_mode TEXT NOT NULL, updated_at TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS card_attempts(id TEXT PRIMARY KEY, deck_id TEXT NOT NULL REFERENCES decks(id) ON DELETE CASCADE, card_id TEXT NOT NULL, card_revision INTEGER NOT NULL, mode TEXT NOT NULL, outcome TEXT NOT NULL, coverage REAL, ending_matched INTEGER, algorithm_version TEXT, started_at TEXT NOT NULL, ended_at TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS user_settings(id INTEGER PRIMARY KEY CHECK(id = 1), default_mode TEXT NOT NULL, speech_rate REAL NOT NULL CHECK(speech_rate BETWEEN 0.5 AND 2.0), auto_advance_delay_ms INTEGER NOT NULL CHECK(auto_advance_delay_ms BETWEEN 0 AND 3000), updated_at TEXT NOT NULL);
        CREATE INDEX IF NOT EXISTS idx_cards_deck_position ON cards(deck_id, position);
        """)
    }

    private func nextDeckPosition() throws -> Int {
        var statement: OpaquePointer?
        defer { sqlite3_finalize(statement) }
        guard sqlite3_prepare_v2(database, "SELECT COALESCE(MAX(position) + 1, 0) FROM decks", -1, &statement, nil) == SQLITE_OK, sqlite3_step(statement) == SQLITE_ROW else { throw StoreError.queryFailed("position") }
        return Int(sqlite3_column_int(statement, 0))
    }

    private func insertDeck(id: String, title: String, description: String?, position: Int, createdAt: String, fingerprint: String) throws {
        let sql = "INSERT INTO decks VALUES (?, ?, ?, ?, ?, ?, ?)"
        try bindAndExecute(sql, [id, title, description ?? NSNull(), position, createdAt, createdAt, fingerprint])
    }

    private func insertCard(id: String, deckId: String, card: ImportedCard, position: Int, now: String) throws {
        try bindAndExecute("INSERT INTO cards VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", [id, deckId, card.title, card.content, card.speechText ?? NSNull(), card.memoryTip ?? NSNull(), position, 1, now, now])
    }

    private func bindAndExecute(_ sql: String, _ values: [Any]) throws {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(database, sql, -1, &statement, nil) == SQLITE_OK else { throw StoreError.queryFailed(sql) }
        defer { sqlite3_finalize(statement) }
        for (index, value) in values.enumerated() {
            let parameter = Int32(index + 1)
            if value is NSNull { sqlite3_bind_null(statement, parameter) }
            else if let number = value as? Int { sqlite3_bind_int(statement, parameter, Int32(number)) }
            else {
                let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
                sqlite3_bind_text(statement, parameter, String(describing: value), -1, transient)
            }
        }
        guard sqlite3_step(statement) == SQLITE_DONE else { throw StoreError.queryFailed(sql) }
    }
}

enum StoreError: LocalizedError { case openFailed, invalidInput, queryFailed(String) }

struct DeckRecord: Identifiable, Equatable { let id: String; let title: String; let description: String; let position: Int }
struct CardRecord: Identifiable, Equatable { let id: String; let title: String; let content: String; let speechText: String; let memoryTip: String; let position: Int }
struct SettingsRecord: Equatable { var mode: String; var rate: Double; var delay: Int }

private extension String { var trimmedOrNull: Any { let value = trimmingCharacters(in: .whitespacesAndNewlines); return value.isEmpty ? NSNull() : value } }
