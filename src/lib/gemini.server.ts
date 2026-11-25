/**
 * Small Gemini helper (Google AI Studio key in GEMINI_API_KEY, OpenAI-compatible endpoint).
 * Google retires model names (gemini-2.5-flash answers 404 for new keys) and its busiest models
 * answer 503 at peak times, so a missing or busy model falls through to the next one.
 * GEMINI_MODEL, when set, is tried first.
 */
export const GEMINI_MODELS = ['gemini-3.5-flash', 'gemini-flash-latest', 'gemini-flash-lite-latest'];
export const GEMINI_ENDPOINT = 'https://generativelanguage.googleapis.com/v1beta/openai/chat/completions';

const env = (name: string) => (process.env[name] ?? '').trim();

export const geminiConfigured = () => Boolean(env('GEMINI_API_KEY'));

export function geminiModels(): string[] {
  return [...new Set([env('GEMINI_MODEL'), ...GEMINI_MODELS].filter(Boolean))];
}

/** One short completion. Returns null when Gemini is not configured, busy everywhere or too slow. */
export async function geminiText(
  system: string,
  user: string,
  opts: { timeoutMs?: number; models?: string[] } = {},
): Promise<string | null> {
  const key = env('GEMINI_API_KEY');
  if (!key) return null;
  const deadline = Date.now() + (opts.timeoutMs ?? 7000);
  for (const model of opts.models ?? geminiModels()) {
    const left = deadline - Date.now();
    if (left < 500) break;
    try {
      const res = await fetch(GEMINI_ENDPOINT, {
        method: 'POST',
        headers: { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({
          model,
          temperature: 0,
          messages: [
            { role: 'system', content: system },
            { role: 'user', content: user },
          ],
        }),
        signal: AbortSignal.timeout(left),
      });
      if (!res.ok) {
        if ([404, 429, 500, 503].includes(res.status)) continue;
        return null;
      }
      const j = (await res.json()) as { choices?: Array<{ message?: { content?: string } }> };
      return (j.choices?.[0]?.message?.content ?? '').trim() || null;
    } catch {
      // timed out or network trouble: try the next model while time is left
      continue;
    }
  }
  return null;
}
