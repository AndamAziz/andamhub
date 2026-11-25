/**
 * Smarter Movies / Shows search on top of TMDB:
 *  - a year in the query ("batman 2022", "(2008) iron man") filters by release year;
 *  - a person's name ("brad pitt", "nolan") lists that actor's or director's titles first;
 *  - exact and starts-with title matches rise to the top;
 *  - nothing found and `fix` asked: Gemini guesses the intended English title or name
 *    (typos, Kurdish / Arabic / Persian spelling) and the search runs again with it;
 *  - the first page also reports how many matches the other section has (movies <-> shows).
 */
import { geminiText } from '@/lib/gemini.server';

type Raw = Record<string, unknown>;
type Fetcher = (path: string, search: Record<string, string>) => Promise<Response>;
export type MediaType = 'movie' | 'tv';

export type SmartResult = {
  results: Raw[];
  totalPages: number;
  /** the query actually used when Gemini corrected it */
  corrected?: string | undefined;
  /** the person whose titles lead the list */
  person?: { name: string; job: string } | undefined;
  year?: string | undefined;
  /** matches in the other section (movies <-> shows), first page only */
  other?: number | undefined;
};

const YEAR_END = /^(.*?)[\s,(]*\(?((?:19|20)\d{2})\)?$/;
const YEAR_START = /^\(?((?:19|20)\d{2})\)?\s+(.+)$/;

/** Splits "batman 2022" into the text and the year. A bare year ("1917") stays a title. */
export function splitYear(q: string): { text: string; year?: string | undefined } {
  const t = q.trim().replace(/\s+/g, ' ');
  let m = t.match(YEAR_END);
  if (m && m[1]?.trim()) return { text: m[1].trim(), year: m[2] };
  m = t.match(YEAR_START);
  if (m && m[2]?.trim()) return { text: m[2].trim(), year: m[1] };
  return { text: t };
}

/** lower case, accents and punctuation removed, "&" read as "and" */
export function fold(s: string): string {
  return s
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/&/g, ' and ')
    .replace(/[^\p{L}\p{N}]+/gu, ' ')
    .trim();
}

function titleOf(r: Raw): string {
  return String(r['title'] ?? r['name'] ?? '');
}
function origTitleOf(r: Raw): string {
  return String(r['original_title'] ?? r['original_name'] ?? '');
}

/** 0 exact, 1 starts with, 2 contains every word, 3 anything else */
function tier(r: Raw, q: string): number {
  const f = fold(q);
  if (!f) return 3;
  const names = [fold(titleOf(r)), fold(origTitleOf(r))].map((n) => n.replace(/^the /, ''));
  const g = f.replace(/^the /, '');
  if (names.some((n) => n === g)) return 0;
  if (names.some((n) => n.startsWith(g))) return 1;
  const words = g.split(' ');
  if (names.some((n) => words.every((w) => n.includes(w)))) return 2;
  return 3;
}

/** Stable re-rank: closer title matches first; inside a tier TMDB's own order stays. */
function rank(results: Raw[], q: string): Raw[] {
  return results
    .map((r, i) => ({ r, i, t: tier(r, q) }))
    .sort((a, b) => a.t - b.t || a.i - b.i)
    .map((x) => x.r);
}

function yearParam(type: MediaType, year?: string): Record<string, string> {
  if (!year) return {};
  return type === 'movie' ? { primary_release_year: year } : { first_air_date_year: year };
}

async function json<T>(res: Response): Promise<T | null> {
  if (!res.ok) return null;
  try {
    return (await res.json()) as T;
  } catch {
    return null;
  }
}

type Page = { results?: Raw[]; total_pages?: number; total_results?: number };

async function titleSearch(get: Fetcher, type: MediaType, text: string, page: number, year?: string) {
  const d = await json<Page>(
    await get(`/search/${type}`, { query: text, page: String(page), include_adult: 'false', ...yearParam(type, year) }),
  );
  return { results: d?.results ?? [], totalPages: Math.min(Number(d?.total_pages ?? 1), 500), total: Number(d?.total_results ?? 0) };
}

/** The person the query names, when it reads like their full name (or one well-known surname). */
async function personFor(
  get: Fetcher,
  text: string,
): Promise<{ id: number; name: string; dept: string; popularity: number } | null> {
  const words = fold(text).split(' ').filter(Boolean);
  if (!words.length || words.length > 4 || /\d/.test(text)) return null;
  const d = await json<Page>(await get('/search/person', { query: text, page: '1', include_adult: 'false' }));
  const p = d?.results?.[0];
  if (!p) return null;
  const name = String(p['name'] ?? '');
  const nameWords = fold(name).split(' ');
  const popularity = Number(p['popularity'] ?? 0);
  const allIn = words.every((w) => nameWords.some((n) => n === w || (w.length >= 4 && n.startsWith(w))));
  const looksLikeName = allIn && popularity >= (words.length >= 2 ? 3 : 8);
  if (!looksLikeName) return null;
  return { id: Number(p['id']), name, dept: String(p['known_for_department'] ?? 'Acting'), popularity };
}

async function personTitles(get: Fetcher, type: MediaType, person: { id: number; dept: string }, year?: string) {
  const d = await json<{ cast?: Raw[]; crew?: Raw[] }>(await get(`/person/${person.id}/${type}_credits`, {}));
  if (!d) return [];
  const list =
    person.dept === 'Acting'
      ? (d.cast ?? [])
      : (d.crew ?? []).filter((c) => ['Director', 'Writer', 'Screenplay', 'Creator', 'Producer'].includes(String(c['job'])));
  const seen = new Set<number>();
  return list
    .filter((r) => r['poster_path'] && !r['adult'])
    .filter((r) => !year || String(r['release_date'] ?? r['first_air_date'] ?? '').startsWith(year))
    .filter((r) => (type === 'movie' ? true : !(r['character'] && /himself|herself|self/i.test(String(r['character'])))))
    .sort((a, b) => Number(b['popularity'] ?? 0) - Number(a['popularity'] ?? 0))
    .filter((r) => {
      const id = Number(r['id']);
      if (seen.has(id)) return false;
      seen.add(id);
      return true;
    })
    .slice(0, 60);
}

/** Gemini's guess at what was meant, remembered per query so a retyped query costs nothing. */
const fixes = new Map<string, string | null>();
async function correctQuery(q: string, type: MediaType): Promise<string | null> {
  const k = `${type}:${fold(q)}`;
  if (fixes.has(k)) return fixes.get(k) ?? null;
  const out = await geminiText(
    'You fix search queries for a movie and TV catalogue (TMDB). The query may be misspelled, ' +
      'transliterated, or written in Kurdish (Sorani or Kurmanji), Arabic, Persian or Turkish. ' +
      'Reply with ONLY the most likely official English title of the ' +
      (type === 'movie' ? 'movie' : 'TV show') +
      ', or the English name of the actor or director meant. No quotes, no year, no notes. ' +
      'If you cannot tell, reply NONE.',
    q,
  );
  const fixed = out && !/^none\b/i.test(out) ? (out.replace(/^["'“”]+|["'“”.]+$/g, '').split('\n')[0] ?? '').trim().slice(0, 120) : null;
  const useful = fixed && fixed.length > 1 && fold(fixed) !== fold(q) ? fixed : null;
  fixes.set(k, useful);
  if (fixes.size > 500) fixes.delete(fixes.keys().next().value as string);
  return useful;
}

async function firstPage(get: Fetcher, type: MediaType, text: string, year?: string) {
  const [titles, person] = await Promise.all([titleSearch(get, type, text, 1, year), personFor(get, text)]);
  let results = rank(titles.results, text);
  let who: SmartResult['person'];
  // "John Wick" is a film before it is anyone's name: an exact title beats a little-known person.
  const exactTitle = results.some((r) => tier(r, text) === 0);
  if (person && (!exactTitle || person.popularity >= 15)) {
    const works = await personTitles(get, type, person, year);
    if (works.length) {
      // A title literally called what was typed still wins; the person's work follows.
      const exact = results.filter((r) => tier(r, text) === 0);
      const ids = new Set(exact.map((r) => Number(r['id'])));
      const merged = [...exact, ...works.filter((w) => !ids.has(Number(w['id'])))];
      merged.forEach((r) => ids.add(Number(r['id'])));
      results = [...merged, ...results.filter((r) => !ids.has(Number(r['id'])))];
      who = { name: person.name, job: person.dept === 'Acting' ? 'actor' : person.dept.toLowerCase() };
    }
  }
  return { results, totalPages: who ? 1 : titles.totalPages, person: who };
}

export async function smartSearch(
  get: Fetcher,
  type: MediaType,
  q: string,
  page: number,
  opts: { fix?: boolean } = {},
): Promise<SmartResult> {
  const { text, year } = splitYear(q);
  if (page > 1) {
    const t = await titleSearch(get, type, text, page, year);
    return { results: rank(t.results, text), totalPages: t.totalPages, year };
  }

  const other: MediaType = type === 'movie' ? 'tv' : 'movie';
  const [main, otherCount] = await Promise.all([
    firstPage(get, type, text, year),
    titleSearch(get, other, text, 1, year).then((t) => t.total).catch(() => 0),
  ]);
  if (main.results.length || !opts.fix) return { ...main, year, other: otherCount };

  const fixed = await correctQuery(text, type);
  if (!fixed) return { ...main, year, other: otherCount };
  const [again, otherAgain] = await Promise.all([
    firstPage(get, type, fixed, year),
    titleSearch(get, other, fixed, 1, year).then((t) => t.total).catch(() => 0),
  ]);
  return { ...again, year, corrected: fixed, other: otherAgain };
}
