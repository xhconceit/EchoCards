import type { SQLiteDatabase } from 'expo-sqlite';

export type DeckSummary = { id: string; title: string; description: string | null; position: number; cardCount: number };
export type Card = { id: string; deckId: string; title: string; content: string; speechText: string | null; explanation: string | null; language: string; position: number; revision: number };
export type LearningMode = 'manual' | 'repeat';

export async function migrateDatabase(db: SQLiteDatabase) {
  await db.execAsync(`
    PRAGMA journal_mode = WAL;
    PRAGMA foreign_keys = ON;
    CREATE TABLE IF NOT EXISTS decks (
      id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, description TEXT,
      position INTEGER NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS cards (
      id TEXT PRIMARY KEY NOT NULL,
      deck_id TEXT NOT NULL REFERENCES decks(id) ON DELETE CASCADE,
      title TEXT NOT NULL, content TEXT NOT NULL, speech_text TEXT, explanation TEXT,
      language TEXT NOT NULL DEFAULT 'zh-CN', position INTEGER NOT NULL,
      revision INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL, updated_at TEXT NOT NULL
    );
    CREATE INDEX IF NOT EXISTS cards_deck_position ON cards(deck_id, position);
    CREATE TABLE IF NOT EXISTS learning_sessions (
      id TEXT PRIMARY KEY NOT NULL,
      deck_id TEXT NOT NULL REFERENCES decks(id) ON DELETE CASCADE,
      mode TEXT NOT NULL, status TEXT NOT NULL, current_index INTEGER NOT NULL DEFAULT 0,
      started_at TEXT NOT NULL, updated_at TEXT NOT NULL, ended_at TEXT
    );
    CREATE TABLE IF NOT EXISTS session_cards (
      session_id TEXT NOT NULL REFERENCES learning_sessions(id) ON DELETE CASCADE,
      card_id TEXT NOT NULL, position INTEGER NOT NULL,
      PRIMARY KEY (session_id, position)
    );
    CREATE TABLE IF NOT EXISTS card_attempts (
      id TEXT PRIMARY KEY NOT NULL,
      session_id TEXT NOT NULL REFERENCES learning_sessions(id) ON DELETE CASCADE,
      card_id TEXT NOT NULL, card_revision INTEGER NOT NULL, mode TEXT NOT NULL,
      outcome TEXT NOT NULL, coverage REAL, ending_matched INTEGER,
      algorithm_version TEXT, started_at TEXT NOT NULL, ended_at TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS user_settings (
      id INTEGER PRIMARY KEY CHECK (id = 1), default_mode TEXT NOT NULL,
      speech_rate REAL NOT NULL, auto_advance_delay_ms INTEGER NOT NULL, updated_at TEXT NOT NULL
    );
    INSERT OR IGNORE INTO user_settings
      (id, default_mode, speech_rate, auto_advance_delay_ms, updated_at)
      VALUES (1, 'manual', 1, 600, CURRENT_TIMESTAMP);
  `);
}

export function listDecks(db: SQLiteDatabase) {
  return db.getAllAsync<DeckSummary>(`
    SELECT d.id, d.title, d.description, d.position, COUNT(c.id) AS cardCount
    FROM decks d LEFT JOIN cards c ON c.deck_id = d.id
    GROUP BY d.id ORDER BY d.position, d.created_at
  `);
}

export function listCards(db: SQLiteDatabase, deckId: string) {
  return db.getAllAsync<Card>(`
    SELECT id, deck_id AS deckId, title, content, speech_text AS speechText,
      explanation, language, position, revision
    FROM cards WHERE deck_id = ? ORDER BY position, created_at
  `, deckId);
}

export async function saveDeck(db: SQLiteDatabase, input: { id?: string; title: string; description?: string }) {
  const title = input.title.trim();
  if (!title) throw new Error('卡组名称不能为空');
  const description = input.description?.trim() || null;
  const now = new Date().toISOString();
  if (input.id) {
    await db.runAsync('UPDATE decks SET title = ?, description = ?, updated_at = ? WHERE id = ?', title, description, now, input.id);
    return;
  }
  const row = await db.getFirstAsync<{ nextPosition: number }>('SELECT COALESCE(MAX(position), -1) + 1 AS nextPosition FROM decks');
  await db.runAsync('INSERT INTO decks (id, title, description, position, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)', makeId(), title, description, row?.nextPosition ?? 0, now, now);
}

export async function deleteDeck(db: SQLiteDatabase, id: string) {
  await db.runAsync('DELETE FROM decks WHERE id = ?', id);
}

export async function saveCard(db: SQLiteDatabase, input: { id?: string; deckId: string; title: string; content: string; speechText?: string; explanation?: string }) {
  const title = input.title.trim();
  const content = input.content.trim();
  if (!title || !content) throw new Error('标题和正文不能为空');
  const speechText = input.speechText?.trim() || null;
  const explanation = input.explanation?.trim() || null;
  const now = new Date().toISOString();
  if (input.id) {
    const old = await db.getFirstAsync<{ content: string; speechText: string | null }>('SELECT content, speech_text AS speechText FROM cards WHERE id = ?', input.id);
    const changed = old?.content !== content || old?.speechText !== speechText;
    await db.runAsync(`UPDATE cards SET title = ?, content = ?, speech_text = ?, explanation = ?, revision = revision + ?, updated_at = ? WHERE id = ?`, title, content, speechText, explanation, changed ? 1 : 0, now, input.id);
    return;
  }
  const row = await db.getFirstAsync<{ nextPosition: number }>('SELECT COALESCE(MAX(position), -1) + 1 AS nextPosition FROM cards WHERE deck_id = ?', input.deckId);
  await db.runAsync(`INSERT INTO cards (id, deck_id, title, content, speech_text, explanation, language, position, revision, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 'zh-CN', ?, 1, ?, ?)`, makeId(), input.deckId, title, content, speechText, explanation, row?.nextPosition ?? 0, now, now);
}

export async function deleteCard(db: SQLiteDatabase, id: string) {
  await db.runAsync('DELETE FROM cards WHERE id = ?', id);
}

export async function moveCard(db: SQLiteDatabase, cards: Card[], cardId: string, direction: -1 | 1) {
  const index = cards.findIndex((card) => card.id === cardId);
  const swapIndex = index + direction;
  if (index < 0 || swapIndex < 0 || swapIndex >= cards.length) return;
  const current = cards[index];
  const other = cards[swapIndex];
  await db.withExclusiveTransactionAsync(async (txn) => {
    await txn.runAsync('UPDATE cards SET position = ? WHERE id = ?', other.position, current.id);
    await txn.runAsync('UPDATE cards SET position = ? WHERE id = ?', current.position, other.id);
  });
}

function makeId() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (character) => {
    const value = Math.floor(Math.random() * 16);
    return (character === 'x' ? value : (value & 0x3) | 0x8).toString(16);
  });
}

export async function startSession(db: SQLiteDatabase, deckId: string, mode: LearningMode) {
  const cards = await listCards(db, deckId);
  if (!cards.length) throw new Error('空卡组不能开始学习');
  const existing = await db.getFirstAsync<{ id: string; currentIndex: number }>(
    `SELECT id, current_index AS currentIndex FROM learning_sessions
     WHERE deck_id = ? AND mode = ? AND status IN ('active', 'paused')
     ORDER BY updated_at DESC LIMIT 1`, deckId, mode,
  );
  if (existing && existing.currentIndex < cards.length) return existing;
  const id = makeId();
  const now = new Date().toISOString();
  await db.withExclusiveTransactionAsync(async (txn) => {
    await txn.runAsync(`INSERT INTO learning_sessions (id, deck_id, mode, status, current_index, started_at, updated_at) VALUES (?, ?, ?, 'active', 0, ?, ?)`, id, deckId, mode, now, now);
    for (const [position, card] of cards.entries()) {
      await txn.runAsync('INSERT INTO session_cards (session_id, card_id, position) VALUES (?, ?, ?)', id, card.id, position);
    }
  });
  return { id, currentIndex: 0 };
}

export async function loadSessionCards(db: SQLiteDatabase, sessionId: string) {
  return db.getAllAsync<Card>(`
    SELECT c.id, c.deck_id AS deckId, c.title, c.content, c.speech_text AS speechText,
      c.explanation, c.language, sc.position, c.revision
    FROM session_cards sc JOIN cards c ON c.id = sc.card_id
    WHERE sc.session_id = ? ORDER BY sc.position`, sessionId);
}

export async function setSessionIndex(db: SQLiteDatabase, sessionId: string, index: number, status: 'active' | 'paused' | 'completed' = 'active') {
  const now = new Date().toISOString();
  await db.runAsync(`UPDATE learning_sessions SET current_index = ?, status = ?, updated_at = ?, ended_at = CASE WHEN ? = 'completed' THEN ? ELSE NULL END WHERE id = ?`, index, status, now, status, now, sessionId);
}

export async function recordAttempt(db: SQLiteDatabase, input: { sessionId: string; card: Card; mode: LearningMode; outcome: 'viewed' | 'read_completed' | 'skipped'; coverage?: number; endingMatched?: boolean }) {
  const now = new Date().toISOString();
  await db.runAsync(`INSERT INTO card_attempts (id, session_id, card_id, card_revision, mode, outcome, coverage, ending_matched, algorithm_version, started_at, ended_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    makeId(), input.sessionId, input.card.id, input.card.revision, input.mode, input.outcome,
    input.coverage ?? null, input.endingMatched == null ? null : Number(input.endingMatched),
    input.coverage == null ? null : 'v1', now, now);
}

export async function getSettings(db: SQLiteDatabase) {
  return (await db.getFirstAsync<{ defaultMode: LearningMode; speechRate: number; autoAdvanceDelayMs: number }>('SELECT default_mode AS defaultMode, speech_rate AS speechRate, auto_advance_delay_ms AS autoAdvanceDelayMs FROM user_settings WHERE id = 1')) ?? { defaultMode: 'manual', speechRate: 1, autoAdvanceDelayMs: 600 };
}

export async function saveSettings(db: SQLiteDatabase, settings: { defaultMode: LearningMode; speechRate: number; autoAdvanceDelayMs: number }) {
  const rate = Math.max(0.5, Math.min(2, settings.speechRate));
  const delay = Math.max(0, Math.min(3000, settings.autoAdvanceDelayMs));
  await db.runAsync('UPDATE user_settings SET default_mode = ?, speech_rate = ?, auto_advance_delay_ms = ?, updated_at = ? WHERE id = 1', settings.defaultMode, rate, delay, new Date().toISOString());
}
