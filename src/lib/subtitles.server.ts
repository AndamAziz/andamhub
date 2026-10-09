/**
 * Subtitles for films and episodes.
 *
 *  - Real subtitles come from OpenSubtitles.com (OPENSUBTITLES_API_KEY; optional
 *    OPENSUBTITLES_USERNAME / OPENSUBTITLES_PASSWORD raise the daily download allowance).
 *  - Kurdish (Sorani) can be generated from the English subtitle by AI translation
 *    (LOVABLE_API_KEY — Lovable AI — or GEMINI_API_KEY). A film is translated in parts so each
 *    request stays short; the finished Kurdish file is stored and every later viewer gets it at once.
 *
 * Every downloaded or generated file is kept in the private storage bucket "subtitles", so a
 * subtitle is fetched from OpenSubtitles (or translated) only once.
 */
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
};

const env = (name: string) => (process.env[name] ?? '').trim();
export const subtitlesConfigured = () => Boolean(env('OPENSUBTITLES_API_KEY'));
export const kurdishAiConfigured = () => Boolean(env('LOVABLE_API_KEY') || env('GEMINI_API_KEY'));

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

export type SubtitleFile = { lang: string; label: string; fileId: number; downloads: number };

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
    const score = (a.download_count ?? 0) - (a.ai_translated || a.machine_translated ? 1e9 : 0);
    const prev = best.get(lang);
    if (!prev || score > prev.score) {
      best.set(lang, { lang, label: LANGS[lang] ?? lang, fileId, downloads: a.download_count ?? 0, score });
    }
  }
  const order = Object.keys(LANGS);
  return [...best.values()]
    .sort((a, b) => order.indexOf(a.lang) - order.indexOf(b.lang))
    .map(({ score: _s, ...f }) => f);
}

/** SRT → WebVTT (players read VTT everywhere). */
export function toVtt(text: string): string {
  const body = text
    .replace(/^﻿/, '')
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
    body: JSON.stringify({ file_id: fileId }),
  });
  const j = (await res.json().catch(() => ({}))) as { link?: string; message?: string };
  if (!res.ok || !j.link) throw new Error(j.message || `OpenSubtitles download ${res.status}`);
  const file = await fetch(j.link, { headers: { 'User-Agent': UA } });
  if (!file.ok) throw new Error(`Subtitle file ${file.status}`);
  const vtt = toVtt(new TextDecoder('utf-8').decode(await file.arrayBuffer()));
  await writeText(path, vtt, 'text/vtt');
  return vtt;
}

// ---------------------------------------------------------------- Kurdish (AI translation)

type Cue = { head: string[]; text: string[] };

function parseVtt(vtt: string): Cue[] {
  const blocks = vtt.replace(/\r\n?/g, '\n').split(/\n{2,}/);
  const cues: Cue[] = [];
  for (const block of blocks) {
    const lines = block.split('\n').filter((l) => l.length > 0);
    const t = lines.findIndex((l) => l.includes('-->'));
    if (t < 0) continue;
    cues.push({ head: lines.slice(0, t + 1), text: lines.slice(t + 1) });
  }
  return cues;
}

const clean = (s: string) => s.replace(/<[^>]+>/g, '').replace(/\{\\[^}]*\}/g, '').trim();

async function translateBatch(lines: string[]): Promise<string[]> {
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
        {
          role: 'system',
          content:
            'You translate film and TV subtitles into Central Kurdish (Sorani, Arabic script, as spoken in Sulaymaniyah and Erbil). ' +
            'You receive a JSON array of subtitle lines. Return ONLY a JSON array of strings with exactly the same number of items, ' +
            'in the same order, each the natural spoken Sorani translation of the matching line. Keep it short like real subtitles. ' +
            'Keep names of people and places. Keep the " / " line breaks. Do not add notes.',
        },
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

export type KurdishState = { ready: boolean; parts: number; missing: number[] };

async function englishCues(fileId: number): Promise<Cue[]> {
  return parseVtt(await subtitleVtt(fileId));
}

/** Kurdish file if finished; otherwise which parts still need translating. */
export async function kurdishState(fileId: number): Promise<{ vtt: string | null; state: KurdishState }> {
  const done = await readText(`ku/${fileId}.vtt`);
  const cues = done ? [] : await englishCues(fileId);
  const parts = Math.max(1, Math.ceil(cues.length / KURDISH_PART));
  if (done) return { vtt: done, state: { ready: true, parts: 0, missing: [] } };
  const names = new Set(await listNames(`ku/${fileId}`));
  const missing = Array.from({ length: parts }, (_, i) => i).filter((i) => !names.has(`p${i}.json`));
  if (missing.length === 0) {
    const vtt = await assemble(fileId, cues, parts);
    return { vtt, state: { ready: true, parts, missing: [] } };
  }
  return { vtt: null, state: { ready: false, parts, missing } };
}

async function assemble(fileId: number, cues: Cue[], parts: number): Promise<string> {
  const lines: string[] = [];
  for (let p = 0; p < parts; p++) {
    const raw = await readText(`ku/${fileId}/p${p}.json`);
    const arr = raw ? (JSON.parse(raw) as string[]) : [];
    lines.push(...arr);
  }
  const out = ['WEBVTT', ''];
  cues.forEach((c, i) => {
    const t = (lines[i] ?? c.text.map(clean).join(' / ')).split(' / ').join('\n');
    out.push(...c.head, t, '');
  });
  const vtt = out.join('\n');
  await writeText(`ku/${fileId}.vtt`, vtt, 'text/vtt');
  return vtt;
}

/** Translates one part (KURDISH_PART cues) of the English subtitle into Sorani and stores it. */
export async function translateKurdishPart(fileId: number, part: number): Promise<void> {
  const path = `ku/${fileId}/p${part}.json`;
  if (await readText(path)) return;
  const cues = await englishCues(fileId);
  const slice = cues.slice(part * KURDISH_PART, (part + 1) * KURDISH_PART);
  if (!slice.length) return;
  const source = slice.map((c) => c.text.map(clean).join(' / '));
  // Two halves in parallel keeps each AI call short.
  const half = Math.ceil(source.length / 2);
  const [a, b] = await Promise.all([translateBatch(source.slice(0, half)), translateBatch(source.slice(half))]);
  await writeText(path, JSON.stringify([...a, ...b]), 'application/json');
}
