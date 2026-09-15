export type MatchResult = { coverage: number; endingMatched: boolean; completed: boolean };

export function normalizeSpeechText(text: string) {
  return text.normalize('NFKC').toLowerCase()
    .replace(/%|％/g, '百分之').replace(/°c/gi, '摄氏度').replace(/=/g, '等于')
    .replace(/\+/g, '加').replace(/-/g, '减')
    .replace(/[^\p{Script=Han}a-z0-9]/gu, '');
}

export function matchSpeech(targetText: string, transcript: string): MatchResult {
  const target = normalizeSpeechText(targetText);
  const heard = normalizeSpeechText(transcript);
  if (!target.length) return { coverage: 0, endingMatched: false, completed: false };
  let targetIndex = 0;
  for (const character of heard) {
    if (character === target[targetIndex]) targetIndex += 1;
    if (targetIndex === target.length) break;
  }
  const coverage = targetIndex / target.length;
  const window = target.length <= 8 ? 2 : target.length <= 20 ? 3 : 5;
  const ending = target.slice(-window);
  const endingMatched = ending.length > 0 && heard.includes(ending);
  const threshold = target.length <= 5 ? 1 : target.length <= 15 ? 0.95 : target.length <= 40 ? 0.9 : 0.88;
  return { coverage, endingMatched, completed: coverage >= threshold && endingMatched };
}
