/**
 * Pure helpers of the TMDB matcher (no network, no env): text normalisation, explicit TMDB ids in
 * names, search-query variants and candidate scoring. tmdb.server.ts drives the searches with them.
 */

export type SearchHit = {
  id?: number;
  title?: string;
  name?: string;
  original_title?: string;
  original_name?: string;
  original_language?: string;
  release_date?: string;
  first_air_date?: string;
  popularity?: number;
  vote_count?: number;
};

/** A candidate needs at least this score, otherwise the provider's own info is shown instead. */
export const MIN_CONFIDENCE = 80;
/** From this score on the match is certain enough to use TMDB's poster / backdrop over the provider's. */
export const STRONG_CONFIDENCE = 140;

export const ARABIC_SCRIPT = /[؀-ۿݐ-ݿﭐ-﷿ﹰ-﻿]/;

/**
 * One spelling for Persian / Arabic text: ي→ی, ك→ک, ة/ۀ→ه, hamza-alef forms→ا, no diacritics,
 * tatweel or ZWNJ, and Persian / Arabic-Indic digits → ASCII.
 */
export function unify(s: string): string {
  return (s ?? '')
    .normalize('NFKC')
    .replace(/[ً-ٰٟـ‌‍‎‏]/g, '')
    .replace(/[يى]/g, 'ی')
    .replace(/ك/g, 'ک')
    .replace(/[ةۀہ]/g, 'ه')
    .replace(/[أإآٱ]/g, 'ا')
    .replace(/ؤ/g, 'و')
    .replace(/[۰-۹]/g, (d) => String(d.charCodeAt(0) - 0x06f0))
    .replace(/[٠-٩]/g, (d) => String(d.charCodeAt(0) - 0x0660));
}

/** Lowercase, no diacritics / punctuation / spaces, unified Persian letters: for comparing titles. */
export const norm = (s: string): string =>
  unify(s)
    .toLowerCase()
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/&/g, 'and')
    .replace(/[^\p{L}\p{N}]+/gu, '');

function levenshtein(a: string, b: string): number {
  if (a === b) return 0;
  if (!a.length) return b.length;
  if (!b.length) return a.length;
  let prev = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i += 1) {
    const cur = [i];
    for (let j = 1; j <= b.length; j += 1) {
      cur[j] = Math.min(prev[j]! + 1, cur[j - 1]! + 1, prev[j - 1]! + (a[i - 1] === b[j - 1] ? 0 : 1));
    }
    prev = cur;
  }
  return prev[b.length]!;
}

const words = (s: string) =>
  unify(s)
    .toLowerCase()
    .split(/[^\p{L}\p{N}]+/u)
    .filter(Boolean);

/** 0..1: character-level (Levenshtein) or word-overlap similarity of two titles, whichever is higher. */
export function similarity(a: string, b: string): number {
  const x = norm(a);
  const y = norm(b);
  if (!x || !y) return 0;
  const lev = 1 - levenshtein(x, y) / Math.max(x.length, y.length);
  const wa = new Set(words(a));
  const wb = new Set(words(b));
  const inter = [...wa].filter((w) => wb.has(w)).length;
  const jac = wa.size && wb.size ? inter / (wa.size + wb.size - inter) : 0;
  return Math.max(lev, jac);
}

// ---------------------------------------------------------------- explicit TMDB ids

export type ExplicitId = { id: number; type?: 'movie' | 'series' };

/**
 * An explicit TMDB id written in a name or playlist field:
 *   tmdb:75404   tmdbid=75404   {tmdb-75404}   [tmdb=75404]   tmdb-id="75404"   tmdb:tv/94146
 * Returns the id (and the media type when the text names one) and the text without it.
 */
export function extractTmdbId(raw: string): { explicit: ExplicitId | null; rest: string } {
  const re = /[[{(]?\s*\btmdb(?:[-_ ]?id)?\s*[:=-]\s*["']?(?:(tv|movie|series)\s*[/:]\s*)?(\d{1,9})["']?\s*[\]})]?/i;
  const m = re.exec(raw ?? '');
  if (!m) return { explicit: null, rest: raw ?? '' };
  const id = Number(m[2]);
  const kind = m[1]?.toLowerCase();
  const type = kind === 'movie' ? 'movie' : kind ? 'series' : undefined;
  const rest = (raw ?? '').replace(re, ' ').replace(/\s{2,}/g, ' ').trim();
  return { explicit: id > 0 ? { id, ...(type ? { type } : {}) } : null, rest };
}

// ---------------------------------------------------------------- query variants

/**
 * Searches to try for one name, best first. Latin names: as given, without a leading article,
 * hyphens as spaces, words joined, and the usual Persian transliteration swaps (ou/oo/u/o).
 * Arabic-script names: as given (unified), and with the Arabic letter forms (ي ك).
 */
export function queryVariants(title: string, max = 8): string[] {
  const base = unify(title).replace(/\s+/g, ' ').trim();
  const out: string[] = [];
  const add = (q: string) => {
    const v = q.replace(/\s+/g, ' ').trim();
    if (v && !out.includes(v)) out.push(v);
  };
  if (ARABIC_SCRIPT.test(base)) {
    // TMDB matches the letters as written: search with آ kept (unify folds it into ا for
    // comparing only), then with آ restored where a playlist dropped it ("اتش بس" → "آتش بس").
    const asWritten = (title ?? '')
      .replace(/[\u064B-\u065F\u0670\u0640\u200e\u200f]/g, '')
      .replace(/[يى]/g, 'ی')
      .replace(/ك/g, 'ک')
      .replace(/\u200c/g, ' ')
      .replace(/\s+/g, ' ')
      .trim();
    add(asWritten);
    const words = asWritten.split(' ');
    if (/^ا/.test(words[0] ?? '')) add([words[0]!.replace(/^ا/, 'آ'), ...words.slice(1)].join(' '));
    if (words.some((w) => /^ا/.test(w))) add(words.map((w) => w.replace(/^ا/, 'آ')).join(' '));
    add(base);
    add(asWritten.replace(/ی/g, 'ي').replace(/ک/g, 'ك'));
    add(asWritten.replace(/\s+/g, ''));
    return out.slice(0, max);
  }
  add(base);
  const noArticle = base.replace(/^(?:the|a|an)\s+/i, '');
  add(noArticle);
  const dehyphen = noArticle.replace(/[-–_:]+/g, ' ');
  add(dehyphen);
  const w = dehyphen.split(' ').filter(Boolean);
  if (w.length >= 2 && w.length <= 3 && w.join('').length >= 6) add(w.join(''));
  // Persian words are written in many Latin spellings: Marmoulak / Marmulak / Marmoolak / Marmolak.
  const flat = dehyphen.toLowerCase();
  const swaps: Array<[RegExp, string[]]> = [
    [/ou/g, ['u', 'oo', 'o']],
    [/oo/g, ['u', 'ou', 'o']],
    [/(?<!o)u/g, ['ou', 'oo']],
    [/kh/g, ['k', 'h']],
    [/ee/g, ['i']],
    [/aa/g, ['a']],
    [/ei/g, ['ey', 'ai']],
  ];
  for (const [re, alts] of swaps) {
    if (!re.test(flat)) continue;
    for (const alt of alts) add(flat.replace(re, alt));
  }
  return out.slice(0, max);
}

// ---------------------------------------------------------------- language expectations

export type Lang = string;

/** Languages a playlist group name points to (Persian / Kurdish / Arabic / Turkish), if any. */
export function groupLanguages(group: string): Lang[] {
  const g = (group ?? '').toLowerCase();
  if (/persian|farsi|iran|پارسی|فارسی|ایران/.test(g)) return ['fa'];
  if (/kurd|sorani|badini|kurmanji|کورد/.test(g)) return ['ku', 'ckb', 'fa', 'ar', 'tr'];
  if (/arab|عرب/.test(g)) return ['ar', 'fa'];
  if (/turk|türk/.test(g)) return ['tr'];
  return [];
}

/** The original languages a name (script + group) makes likely; empty = no expectation. */
export function expectedLanguages(title: string, group = ''): Lang[] {
  const g = groupLanguages(group);
  if (g.length) return g;
  if (ARABIC_SCRIPT.test(title)) return /[پچژگ]/.test(title) ? ['fa', 'ku', 'ckb'] : ['fa', 'ar', 'ku', 'ckb'];
  return [];
}

// ---------------------------------------------------------------- scoring

export type Want = {
  /** Cleaned name, any script. */
  title: string;
  /** All spellings searched so far (match against any of them). */
  variants?: string[];
  year: string;
  group?: string;
};

export type Candidate = {
  hit: SearchHit;
  /** Best position (0 = first) in any search that returned it, and how many results that search had. */
  rank: number;
  total: number;
  /** Other names TMDB knows (alternative titles, translations), when looked up. */
  aliases?: string[];
};

export type Scored = { id: number; score: number; reason: string };

const REGIONAL_PRIOR = new Set(['ku', 'ckb', 'ar', 'tr']);

export function scoreCandidate(c: Candidate, want: Want): Scored {
  const hit = c.hit;
  const wants = [...new Set([want.title, ...(want.variants ?? [])].map(norm).filter(Boolean))];
  if (!wants.length || !hit.id) return { id: hit.id ?? 0, score: 0, reason: 'empty' };
  const titles = [hit.title, hit.name].filter(Boolean).map((n) => norm(n as string));
  const originals = [hit.original_title, hit.original_name].filter(Boolean).map((n) => norm(n as string));
  const aliases = (c.aliases ?? []).map(norm).filter(Boolean);
  const reasons: string[] = [];

  let text = 0;
  if (wants.some((w) => originals.includes(w))) {
    text = 100;
    reasons.push('exact original title');
  } else if (wants.some((w) => titles.includes(w))) {
    text = 100;
    reasons.push('exact title');
  } else if (wants.some((w) => aliases.includes(w))) {
    text = 90;
    reasons.push('exact alternative title');
  } else {
    const all = [...titles, ...originals, ...aliases];
    let contained = 0;
    for (const w of wants) {
      for (const n of all) {
        if (w.length < 4 || n.length < 4) continue;
        if (n.includes(w) || w.includes(n)) contained = Math.max(contained, Math.min(w.length, n.length) / Math.max(w.length, n.length) >= 0.5 ? 60 : 30);
      }
    }
    let sim = 0;
    for (const n of [hit.title, hit.name, hit.original_title, hit.original_name, ...(c.aliases ?? [])]) {
      if (!n) continue;
      for (const w of [want.title, ...(want.variants ?? [])]) sim = Math.max(sim, similarity(w, n));
    }
    text = Math.min(90, contained + sim * 50 * (contained ? 0.6 : 1));
    if (text) reasons.push(`name similarity ${Math.round(text)}`);
  }

  let s = text;
  const hitYear = Number((hit.release_date || hit.first_air_date || '').slice(0, 4));
  const wantYear = Number(want.year);
  if (wantYear && hitYear) {
    const d = Math.abs(hitYear - wantYear);
    const y = d === 0 ? 60 : d === 1 ? 30 : d > 2 ? -40 : 0;
    s += y;
    reasons.push(`year ${hitYear}${y ? ` ${y > 0 ? '+' : ''}${y}` : ''}`);
  }

  const lang = (hit.original_language ?? '').toLowerCase();
  const expected = expectedLanguages(want.title, want.group);
  if (lang && lang !== 'xx') {
    if (expected.length) {
      const l = expected.includes(lang) ? 40 : lang === 'en' ? -15 : -30;
      s += l;
      reasons.push(`language ${lang} ${l > 0 ? '+' : ''}${l}`);
    } else if (lang === 'fa') {
      s += 20;
      reasons.push('language fa prior +20');
    } else if (REGIONAL_PRIOR.has(lang)) {
      s += 8;
      reasons.push(`language ${lang} prior +8`);
    }
  }

  // TMDB's own ranking: a name in another script can only be found through TMDB's translations,
  // and a rare query that returns one or two titles points at the first of them.
  if (ARABIC_SCRIPT.test(want.title)) s += [25, 15, 8][c.rank] ?? 0;
  else if (c.rank === 0 && c.total <= 2 && text < 100) s += 30;

  // Tie-breakers only (0..15).
  s += Math.min(9, Math.log10(1 + (hit.popularity ?? 0)) * 3.5) + Math.min(6, Math.log10(1 + (hit.vote_count ?? 0)) * 2);
  return { id: hit.id, score: Math.round(s * 10) / 10, reason: reasons.join(', ') };
}

/**
 * The best candidate. Within a few points of the top, the one whose year / language matches wins
 * over the one TMDB (or popularity) happened to rank higher.
 */
export function pickBest(cands: Candidate[], want: Want): (Scored & { cand: Candidate }) | null {
  const scored = cands.map((cand) => ({ ...scoreCandidate(cand, want), cand })).filter((x) => x.id);
  if (!scored.length) return null;
  scored.sort((a, b) => b.score - a.score);
  const top = scored[0]!;
  const wantYear = Number(want.year);
  const expected = expectedLanguages(want.title, want.group);
  const fits = (x: (typeof scored)[number]) => {
    const y = Number((x.cand.hit.release_date || x.cand.hit.first_air_date || '').slice(0, 4));
    const yearOk = !wantYear || (y && Math.abs(y - wantYear) <= 1) ? 1 : 0;
    const lang = (x.cand.hit.original_language ?? '').toLowerCase();
    const langOk = !expected.length ? (lang === 'fa' ? 1 : 0) : expected.includes(lang) ? 1 : 0;
    return yearOk * 2 + langOk;
  };
  const near = scored.filter((x) => top.score - x.score <= 5);
  return near.sort((a, b) => fits(b) - fits(a) || b.score - a.score)[0] ?? top;
}
