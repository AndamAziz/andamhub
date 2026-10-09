/**
 * Subtitles for films and episodes.
 *
 *  - Real subtitles come from OpenSubtitles.com (OPENSUBTITLES_API_KEY; optional
 *    OPENSUBTITLES_USERNAME / OPENSUBTITLES_PASSWORD raise the daily download allowance).
 *  - Kurdish (Sorani) is made from the English subtitle (else Arabic, else any human-made file).
 *    Engines, each stored on its own so a title is translated once per engine:
 *      g      — free Google Translate, run on the viewer's device (app or browser), which uploads
 *               each finished part here (the default; spreads Google's rate limit over devices);
 *      ai     — Lovable AI / Gemini on this server (LOVABLE_API_KEY or GEMINI_API_KEY);
 *      claude — Claude on this server (ANTHROPIC_API_KEY): "claude-best" (Opus) / "claude-fast" (Haiku).
 *    A film is translated in parts of KURDISH_PART cues so each request stays short; the finished
 *    Kurdish file is stored and every later viewer gets it at once.
 *
 * Every downloaded or generated file is kept in the private storage bucket "subtitles", so a
 * subtitle is fetched from OpenSubtitles (or translated) only once.
 */
import Anthropic from '@anthropic-ai/sdk';
import { supabaseAdmin } from '@/integrations/supabase/client.server';

const OS = 'https://api.opensubtitles.com/api/v1';
const UA = 'Andam v1.0';
const BUCKET = 'subtitles';
export const KURDISH_PART = 120;

export const LANGS: Record<string, string> = {
  en: 'English',
  ar: 'Arabic',
  ku: 'Kurdish',
  fa: 'Persian',
  tr: 'Turkish',
  fr: 'French',
  de: 'German',
  es: 'Spanish',
};

const env = (name: string) => (process.env[name] ?? '').trim();
export const subtitlesConfigured = () => Boolean(env('OPENSUBTITLES_API_KEY'));
export const kurdishAiConfigured = () => Boolean(env('LOVABLE_API_KEY') || env('GEMINI_API_KEY'));
export const claudeConfigured = () => Boolean(env('ANTHROPIC_API_KEY'));

/** Kurdish engines and where each keeps its parts / finished file. */
export type KurdishEngine = 'g' | 'ai' | 'claude-best' | 'claude-fast';
const ENGINE_DIR: Record<KurdishEngine, string> = { g: 'kug', ai: 'ku', 'claude-best': 'kuo', 'claude-fast': 'kuh' };
export function kurdishEngine(v: string | null | undefined): KurdishEngine {
  return v === 'ai' || v === 'claude-best' || v === 'claude-fast' ? v : 'g';
}
/** Engines this server can run itself (the free Google engine runs on the viewer's device). */
export function serverEngines(): KurdishEngine[] {
  const out: KurdishEngine[] = [];
  if (kurdishAiConfigured()) out.push('ai');
  if (claudeConfigured()) out.push('claude-best', 'claude-fast');
  return out;
}

// ---------------------------------------------------------------- storage

let bucketReady = false;
async function ensureBucket(): Promise<void> {
  if (bucketReady) return;
  const { data } = await supabaseAdmin.storage.getBucket(BUCKET);
  if (!data) await supabaseAdmin.storage.createBucket(BUCKET, { public: false });
  bucketReady = true;
}

async function readText(path: string): Promise<string | null> {
  try {
    const { data, error } = await supabaseAdmin.storage.from(BUCKET).download(path);
    if (error || !data) return null;
    return await data.text();
  } catch {
    return null;
  }
}

async function writeText(path: string, text: string, type: string): Promise<void> {
  try {
    await ensureBucket();
    await supabaseAdmin.storage
      .from(BUCKET)
      .upload(path, new Blob([text], { type }), { upsert: true, contentType: type });
  } catch (err) {
    console.error('[subtitles] store failed', path, err);
  }
}

async function listNames(prefix: string): Promise<string[]> {
  try {
    const { data } = await supabaseAdmin.storage.from(BUCKET).list(prefix, { limit: 1000 });
    return (data ?? []).map((f) => f.name);
  } catch {
    return [];
  }
}

// ---------------------------------------------------------------- OpenSubtitles

let login: { token: string; at: number } | null = null;

async function osHeaders(): Promise<Record<string, string>> {
  const h: Record<string, string> = {
    'Api-Key': env('OPENSUBTITLES_API_KEY'),
    'User-Agent': UA,
    Accept: 'application/json',
    'Content-Type': 'application/json',
  };
  const user = env('OPENSUBTITLES_USERNAME');
  const pass = env('OPENSUBTITLES_PASSWORD');
  if (user && pass) {
    if (!login || Date.now() - login.at > 12 * 60 * 60 * 1000) {
      try {
        const res = await fetch(`${OS}/login`, { method: 'POST', headers: h, body: JSON.stringify({ username: user, password: pass }) });
        const j = (await res.json()) as { token?: string };
        if (j.token) login = { token: j.token, at: Date.now() };
      } catch {
        /* anonymous download still works */
      }
    }
    if (login) h['Authorization'] = `Bearer ${login.token}`;
  }
  return h;
}

export type SubtitleFile = { lang: string; label: string; fileId: number; downloads: number; human: boolean };

/** Best subtitle per language for a film (tmdb id) or an episode (series tmdb id + S/E). */
export async function searchSubtitles(q: {
  tmdb: number;
  type: 'movie' | 'episode';
  season?: number;
  episode?: number;
}): Promise<SubtitleFile[]> {
  if (!subtitlesConfigured() || !q.tmdb) return [];
  const params: Record<string, string> =
    q.type === 'movie'
      ? { tmdb_id: String(q.tmdb) }
      : { parent_tmdb_id: String(q.tmdb), season_number: String(q.season ?? 0), episode_number: String(q.episode ?? 0) };
  params['languages'] = Object.keys(LANGS).sort().join(',');
  params['order_by'] = 'download_count';
  // A TMDB number can be a film or a series: the type keeps OpenSubtitles from mixing them.
  params['type'] = q.type;
  // OpenSubtitles asks for sorted, lower-case parameters (otherwise it redirects).
  const qs = Object.keys(params)
    .sort()
    .map((k) => `${k}=${encodeURIComponent(params[k] ?? '')}`)
    .join('&');
  const res = await fetch(`${OS}/subtitles?${qs}`, { headers: await osHeaders() });
  if (!res.ok) throw new Error(`OpenSubtitles ${res.status}`);
  const j = (await res.json()) as {
    data?: Array<{
      attributes?: {
        language?: string;
        download_count?: number;
        ai_translated?: boolean;
        machine_translated?: boolean;
        files?: Array<{ file_id?: number }>;
      };
    }>;
  };
  const best = new Map<string, SubtitleFile & { score: number }>();
  for (const row of j.data ?? []) {
    const a = row.attributes ?? {};
    const lang = (a.language ?? '').toLowerCase().slice(0, 2);
    const fileId = a.files?.[0]?.file_id;
    if (!LANGS[lang] || !fileId) continue;
    // Prefer human subtitles, then the most downloaded.
    const human = !(a.ai_translated || a.machine_translated);
    const score = (a.download_count ?? 0) - (human ? 0 : 1e9);
    const prev = best.get(lang);
    if (!prev || score > prev.score) {
      best.set(lang, { lang, label: LANGS[lang] ?? lang, fileId, downloads: a.download_count ?? 0, human, score });
    }
  }
  const order = Object.keys(LANGS);
  return [...best.values()]
    .sort((a, b) => order.indexOf(a.lang) - order.indexOf(b.lang))
    .map(({ score: _s, ...f }) => f);
}

/** The file Kurdish is made from: English, else Arabic, else any human-made subtitle. */
export function kurdishSource(files: SubtitleFile[]): SubtitleFile | null {
  if (files.some((f) => f.lang === 'ku' && f.human)) return null;
  return (
    files.find((f) => f.lang === 'en') ??
    files.find((f) => f.lang === 'ar') ??
    files.find((f) => f.human && f.lang !== 'ku') ??
    null
  );
}

/** SRT → WebVTT (players read VTT everywhere). */
export function toVtt(text: string): string {
  const body = text
    .replace(/^\uFEFF/, '')
    .replace(/\r\n?/g, '\n')
    .replace(/(\d{2}:\d{2}:\d{2}),(\d{3})/g, '$1.$2')
    .trim();
  return body.startsWith('WEBVTT') ? body + '\n' : `WEBVTT\n\n${body}\n`;
}

/** The subtitle file as WebVTT: from storage, or downloaded once from OpenSubtitles. */
export async function subtitleVtt(fileId: number): Promise<string> {
  const path = `os/${fileId}.vtt`;
  const stored = await readText(path);
  if (stored) return stored;
  const res = await fetch(`${OS}/download`, {
    method: 'POST',
    headers: await osHeaders(),
    body: JSON.stringify({ file_id: fileId, sub_format: 'srt' }),
  });
  const j = (await res.json().catch(() => ({}))) as { link?: string; message?: string; remaining?: number };
  // OpenSubtitles' own words (daily quota reached, etc.) are passed on to the viewer.
  if (!res.ok || !j.link) throw new Error(j.message || `OpenSubtitles download ${res.status}`);
  const file = await fetch(j.link, { headers: { 'User-Agent': UA } });
  if (!file.ok) throw new Error(`Subtitle file ${file.status}`);
  const vtt = toVtt(new TextDecoder('utf-8').decode(await file.arrayBuffer()));
  await writeText(path, vtt, 'text/vtt');
  return vtt;
}

// ---------------------------------------------------------------- Kurdish (Sorani)

type Cue = { head: string[]; text: string[] };

/** Cues of a VTT/SRT file: blocks split on blank lines; the line with "-->" is the timing line. */
function parseCues(text: string): Cue[] {
  const blocks = text.replace(/^\uFEFF/, '').replace(/\r\n?/g, '\n').split(/\n[ \t]*\n+/);
  const cues: Cue[] = [];
  for (const block of blocks) {
    const lines = block.split('\n').filter((l) => l.trim().length > 0);
    const t = lines.findIndex((l) => l.includes('-->'));
    if (t < 0) continue;
    cues.push({ head: lines.slice(0, t + 1), text: lines.slice(t + 1) });
  }
  return cues;
}

const clean = (s: string) => s.replace(/<[^>]+>/g, '').replace(/\{\\[^}]*\}/g, '').trim();

/** One cue's text for translation: its lines joined by a line break (kept by every engine). */
const cueText = (c: Cue) => c.text.map(clean).filter(Boolean).join('\n');

const SYSTEM =
  'You translate film and TV subtitles into Central Kurdish (Sorani, Arabic script, as spoken in Sulaymaniyah and Erbil). ' +
  'You receive a JSON array of subtitle cues. Return exactly the same number of items, in the same order, each the natural ' +
  'spoken Sorani translation of the matching cue. Keep it short like real subtitles. Keep names of people and places. ' +
  'Keep the line breaks inside a cue. Do not add notes.';

async function translateWithAi(lines: string[]): Promise<string[]> {
  const lovable = env('LOVABLE_API_KEY');
  const gemini = env('GEMINI_API_KEY');
  const endpoint = lovable
    ? 'https://ai.gateway.lovable.dev/v1/chat/completions'
    : 'https://generativelanguage.googleapis.com/v1beta/openai/chat/completions';
  const model = lovable ? 'google/gemini-2.5-flash' : 'gemini-2.5-flash';
  const res = await fetch(endpoint, {
    method: 'POST',
    headers: { Authorization: `Bearer ${lovable || gemini}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      model,
      temperature: 0.2,
      messages: [
        { role: 'system', content: SYSTEM + ' Return ONLY a JSON array of strings.' },
        { role: 'user', content: JSON.stringify(lines) },
      ],
    }),
  });
  if (!res.ok) throw new Error(`AI translation ${res.status}`);
  const j = (await res.json()) as { choices?: Array<{ message?: { content?: string } }> };
  const content = j.choices?.[0]?.message?.content ?? '';
  const start = content.indexOf('[');
  const end = content.lastIndexOf(']');
  const out = start >= 0 && end > start ? (JSON.parse(content.slice(start, end + 1)) as unknown[]) : [];
  return lines.map((orig, i) => (typeof out[i] === 'string' && (out[i] as string).trim() ? (out[i] as string) : orig));
}

let anthropic: Anthropic | null = null;

/** Claude: "claude-best" = Opus, "claude-fast" = Haiku. Structured output keeps the cue count exact. */
async function translateWithClaude(lines: string[], engine: 'claude-best' | 'claude-fast'): Promise<string[]> {
  anthropic ??= new Anthropic({ apiKey: env('ANTHROPIC_API_KEY') });
  const best = engine === 'claude-best';
  const request = {
    model: best ? 'claude-opus-5-5' : 'claude-haiku-5-5',
    max_tokens: 16000,
    system: SYSTEM,
    output_config: {
      effort: best ? ('medium' as const) : ('low' as const),
      format: {
        type: 'json_schema' as const,
        schema: {
          type: 'object',
          properties: { lines: { type: 'array', items: { type: 'string' } } },
          required: ['lines'],
          additionalProperties: false,
        },
      },
    },
    messages: [{ role: 'user' as const, content: JSON.stringify(lines) }],
  };
  // Opus: a declined request is re-run on Anthropic's recommended fallback model server-side.
  const res = best
    ? await anthropic.beta.messages.create({ ...request, betas: ['server-side-fallback-2026-07-01'], fallbacks: 'default' })
    : await anthropic.messages.create(request);
  if (res.stop_reason === 'refusal') throw new Error('Claude declined to translate this part');
  if (res.stop_reason === 'max_tokens') throw new Error('Claude translation was cut off');
  const text = res.content.map((b) => (b.type === 'text' ? b.text : '')).join('');
  const out = (JSON.parse(text) as { lines?: unknown[] }).lines ?? [];
  return lines.map((orig, i) => (typeof out[i] === 'string' && (out[i] as string).trim() ? (out[i] as string) : orig));
}

export type KurdishState = { ready: boolean; parts: number; missing: number[] };

async function sourceCues(fileId: number): Promise<Cue[]> {
  return parseCues(await subtitleVtt(fileId));
}

const dir = (engine: KurdishEngine, fileId: number) => `${ENGINE_DIR[engine]}/${fileId}`;

/** Kurdish file if finished; otherwise which parts still need translating. */
export async function kurdishState(
  fileId: number,
  engine: KurdishEngine = 'g',
): Promise<{ vtt: string | null; state: KurdishState }> {
  const done = await readText(`${dir(engine, fileId)}.vtt`);
  if (done) return { vtt: done, state: { ready: true, parts: 0, missing: [] } };
  const cues = await sourceCues(fileId);
  const parts = Math.max(1, Math.ceil(cues.length / KURDISH_PART));
  const names = new Set(await listNames(dir(engine, fileId)));
  const missing = Array.from({ length: parts }, (_, i) => i).filter((i) => !names.has(`p${i}.json`));
  if (missing.length === 0) {
    const vtt = await assemble(fileId, engine, cues, parts);
    return { vtt, state: { ready: true, parts, missing: [] } };
  }
  return { vtt: null, state: { ready: false, parts, missing } };
}

async function assemble(fileId: number, engine: KurdishEngine, cues: Cue[], parts: number): Promise<string> {
  const lines: string[] = [];
  for (let p = 0; p < parts; p++) {
    const raw = await readText(`${dir(engine, fileId)}/p${p}.json`);
    const arr = raw ? (JSON.parse(raw) as string[]) : [];
    // A part always covers its full slice, so later parts stay aligned with their cues.
    const size = Math.min(KURDISH_PART, cues.length - p * KURDISH_PART);
    for (let i = 0; i < size; i++) lines.push(arr[i] ?? '');
  }
  const out = ['WEBVTT', ''];
  cues.forEach((c, i) => {
    // Older AI parts used " / " for line breaks.
    const t = (lines[i] || cueText(c)).split(' / ').join('\n');
    out.push(...c.head.filter((h) => !/^WEBVTT/.test(h)), t, '');
  });
  const vtt = out.join('\n');
  await writeText(`${dir(engine, fileId)}.vtt`, vtt, 'text/vtt');
  return vtt;
}

/** The source cues of one part, for the viewer's device to translate (free Google engine). */
export async function kurdishSourcePart(fileId: number, part: number): Promise<{ parts: number; lines: string[] }> {
  const cues = await sourceCues(fileId);
  const parts = Math.max(1, Math.ceil(cues.length / KURDISH_PART));
  const lines = cues.slice(part * KURDISH_PART, (part + 1) * KURDISH_PART).map(cueText);
  return { parts, lines };
}

/**
 * Stores one part translated on a viewer's device (Google engine). Accepted only when it matches
 * the part's cue count and has not been stored already, so a finished part is never replaced.
 */
export async function storeKurdishPart(fileId: number, part: number, lines: unknown): Promise<void> {
  if (!Array.isArray(lines)) throw new Error('lines must be a list');
  const { lines: src, parts } = await kurdishSourcePart(fileId, part);
  if (!(part >= 0 && part < parts) || lines.length !== src.length) throw new Error('Part does not match the subtitle');
  const out = lines.map((l, i) => {
    const t = typeof l === 'string' ? l.replace(/<[^>]*>/g, '').trim().slice(0, 600) : '';
    return t || src[i] || '';
  });
  const path = `${dir('g', fileId)}/p${part}.json`;
  if (await readText(path)) return;
  await writeText(path, JSON.stringify(out), 'application/json');
}

/** Translates one part (KURDISH_PART cues) on this server with an AI engine and stores it. */
export async function translateKurdishPart(fileId: number, part: number, engine: KurdishEngine = 'ai'): Promise<void> {
  if (engine === 'g') throw new Error('The Google engine runs on the device');
  if (engine === 'ai' && !kurdishAiConfigured()) throw new Error('AI translation is not set up');
  if (engine !== 'ai' && !claudeConfigured()) throw new Error('Claude is not set up');
  const path = `${dir(engine, fileId)}/p${part}.json`;
  if (await readText(path)) return;
  const cues = await sourceCues(fileId);
  const slice = cues.slice(part * KURDISH_PART, (part + 1) * KURDISH_PART);
  if (!slice.length) return;
  const source = slice.map(cueText);
  let out: string[];
  if (engine === 'ai') {
    // Two halves in parallel keeps each AI call short.
    const half = Math.ceil(source.length / 2);
    const [a, b] = await Promise.all([translateWithAi(source.slice(0, half)), translateWithAi(source.slice(half))]);
    out = [...a, ...b];
  } else {
    out = await translateWithClaude(source, engine);
  }
  await writeText(path, JSON.stringify(out), 'application/json');
}
