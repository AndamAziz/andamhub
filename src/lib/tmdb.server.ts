/**
 * Film / series details for the apps' detail pages: the provider's own info (get_vod_info /
 * get_series_info) enriched with TMDB (cast photos, backdrop, trailer, genres, runtime).
 *
 * TMDB is optional: without TMDB_API_KEY (a v3 key or a v4 read token) — or when TMDB cannot be
 * reached — the provider's info is returned on its own. Results are kept in memory for a few hours.
 *
 * Language: `lang` ku | ar | en picks TMDB's text language; when TMDB has no story in that
 * language the English one is used. The provider's own poster and story come first (TMDB fills
 * the gaps); an English TMDB story never replaces the provider's story on a non-English page.
 */
import { playerApi, type Source } from '@/lib/xtream';
import {
  ARABIC_SCRIPT,
  extractTmdbId,
  MIN_CONFIDENCE,
  norm,
  pickBest,
  queryVariants,
  scoreCandidate,
  STRONG_CONFIDENCE,
  unify,
  type Candidate,
  type ExplicitId,
  type SearchHit,
  type Want,
} from '@/lib/tmdb-match';

export { MIN_CONFIDENCE, STRONG_CONFIDENCE };
export type { SearchHit };

const TMDB = 'https://api.themoviedb.org/3';
const IMG = 'https://image.tmdb.org/t/p';

export type CastMember = { name: string; role: string; photo: string };

export type Lang = 'en' | 'ar' | 'ku';
export const langOf = (v: string | null | undefined): Lang => (v === 'ar' || v === 'ku' ? v : 'en');
const tmdbLang = (l: Lang) => (l === 'en' ? 'en-US' : l);

export type MediaDetails = {
  type: 'movie' | 'series';
  title: string;
  originalTitle: string;
  tagline: string;
  overview: string;
  year: string;
  runtime: number;
  rating: number;
  votes: number;
  genres: string[];
  certification: string;
  director: string;
  /** Series creators (films leave it empty). */
  creator: string;
  country: string;
  cast: CastMember[];
  poster: string;
  backdrop: string;
  trailer: string;
  tmdbId: number;
  imdbId: string;
  seasons: number;
  episodes: number;
  /** Why this title was chosen (debug only; the apps never show it). */
  match?: MatchInfo;
};

export type MatchInfo = {
  how: 'tmdb id' | 'search';
  id: number;
  type: 'movie' | 'series';
  score: number;
  strong: boolean;
  /** The search text that found it, and the number of the search call (1 = the first). */
  query: string;
  pass: number;
  reason: string;
};

const cache = new Map<string, { at: number; ttl: number; value: MediaDetails }>();
/** A matched title is kept for 7 days. */
const TTL = 7 * 24 * 60 * 60 * 1000;
/** A title TMDB has no confident match for is asked again after a day (a changed name / year has its own key). */
const MISS_TTL = 24 * 60 * 60 * 1000;
/** TMDB itself failing (down, slow, rate-limited) is retried soon. */
const ERROR_TTL = 10 * 60 * 1000;

/** Drops every remembered answer (the apps' "Clear cache"). */
export function clearDetailsCache() {
  cache.clear();
  seasonCache.clear();
}

function key(): string {
  return (process.env['TMDB_API_KEY'] ?? '').trim();
}

async function tmdb<T>(path: string, search: Record<string, string> = {}): Promise<T | null> {
  const k = key();
  if (!k) return null;
  const url = new URL(TMDB + path);
  const headers: Record<string, string> = { accept: 'application/json' };
  if (k.startsWith('ey')) headers['Authorization'] = `Bearer ${k}`;
  else url.searchParams.set('api_key', k);
  url.searchParams.set('language', 'en-US');
  for (const [name, value] of Object.entries(search)) if (value) url.searchParams.set(name, value);
  try {
    // TMDB must never hold a page up: a slow answer counts as no answer.
    const res = await fetch(url, { headers, signal: AbortSignal.timeout(8000) });
    if (!res.ok) return null;
    return (await res.json()) as T;
  } catch {
    return null;
  }
}

const QUALITY =
  /\b(4K|UHD|FHD|HD|SD|HQ|HEVC|H\.?26[45]|x26[45]|10bit|HDR|1080p|720p|480p|2160p|MULTI|MULTISUB|VOSTFR|SUB(BED|S)?|DUB(BED)?|NF|AMZN|WEB-?DL|WEBRIP|BLURAY|BRRIP|DVDRIP|CAM|KURDISH|KURDI|ARABIC|TURKISH|PERSIAN|ENGLISH)\b/gi;

/**
 * "EN - The Batman (2022) [4K]" → { title: "The Batman", year: "2022" }.
 * Year from "(2026)", "[2026]" or a trailing year; tags like "(...)", "[4K]", "|EN|", "EN - ",
 * quality words and " - S01E01" are removed; a name written in two scripts
 * ("پێغەمبەر یوسف - Prophet Joseph") keeps its Latin part.
 */
export function cleanTitle(raw: string): { title: string; year: string; tmdb?: ExplicitId } {
  // An explicit TMDB id ("tmdb:75404", "{tmdb-75404}", tmdb-id="75404", "tmdb:tv/94146") is taken out first.
  const ex = extractTmdbId(raw ?? '');
  let t = unify(ex.rest).replace(/[\u200e\u200f\u202a-\u202e]/g, ' ');
  const maxYear = new Date().getFullYear() + 1;
  const isYear = (y: string | undefined) => !!y && Number(y) >= 1900 && Number(y) <= maxYear;
  const bracketed = /[([]\s*((?:19|20)\d{2})\s*[)\]]/.exec(t)?.[1];
  let year = isYear(bracketed) ? (bracketed as string) : '';
  t = t
    .replace(/\|[^|]{1,12}\|/g, ' ') // "|EN|", "|AR|"
    .replace(/^\s*[[(]?[A-Za-z]{2,3}[\])]?\s*[-:|]\s+/u, '') // "EN - ", "[FR] : "
    .replace(/\[[^\]]*\]|\([^)]*\)|\{[^}]*\}/g, ' ')
    .replace(/\s[-–:|]?\s*S\d{1,2}\s*E\d{1,3}\b.*$/i, ' ') // " - S01E01 …"
    .replace(/\bS\d{1,2}\s*E\d{1,3}\b/gi, ' ')
    .replace(QUALITY, ' ')
    .replace(/[\s\-–|:.]+$/, '');
  // A year that ends the name is the release year ("Blade Runner 2049" keeps its number).
  const trailing = /(?:^|[\s\-–|:])((?:19|20)\d{2})$/.exec(t)?.[1];
  if (isYear(trailing) && t.length > 4) {
    year ||= trailing as string;
    t = t.slice(0, -4);
  }
  // Two scripts ("Kurdish/Arabic name - Latin name"): search the Latin part.
  const parts = t.split(/\s[-–|/]\s|\s{2,}/).map((x) => x.trim()).filter(Boolean);
  if (parts.length > 1) {
    const latin = parts.filter((x) => /[A-Za-z]/.test(x) && !/[\u0600-\u06FF]/.test(x));
    if (latin.length && latin.length < parts.length) t = latin.join(' ');
  }
  // Still mixed: drop the Arabic-script words when a Latin name is there.
  if (/[\u0600-\u06FF]/.test(t) && /[A-Za-z]{3}/.test(t)) t = t.replace(/[\u0600-\u06FF\u200c]+/g, ' ');
  t = t
    .replace(/[-_|:–.]+\s*$/g, ' ')
    .replace(/^\s*[-_|:–]+/g, ' ')
    .replace(/\s{2,}/g, ' ')
    .trim();
  return { title: t, year, ...(ex.explicit ? { tmdb: ex.explicit } : {}) };
}

const num = (v: unknown) => {
  const n = Number(v);
  return Number.isFinite(n) ? n : 0;
};
const str = (v: unknown) => (typeof v === 'string' ? v.trim() : v == null ? '' : String(v));
const firstOf = (v: unknown) => (Array.isArray(v) ? str(v[0]) : str(v));
const youtubeKey = (v: string) => {
  if (!v) return '';
  const m = /(?:v=|youtu\.be\/|embed\/)([\w-]{6,})/.exec(v);
  return m?.[1] ?? (/^[\w-]{6,}$/.test(v) ? v : '');
};

type TmdbCredit = { name?: string; character?: string; job?: string; profile_path?: string | null; roles?: Array<{ character?: string }> };
type TmdbFull = {
  id?: number;
  title?: string;
  name?: string;
  original_title?: string;
  original_name?: string;
  tagline?: string;
  overview?: string;
  release_date?: string;
  first_air_date?: string;
  runtime?: number;
  episode_run_time?: number[];
  vote_average?: number;
  vote_count?: number;
  genres?: Array<{ name?: string }>;
  poster_path?: string | null;
  backdrop_path?: string | null;
  imdb_id?: string | null;
  number_of_seasons?: number;
  number_of_episodes?: number;
  production_countries?: Array<{ iso_3166_1?: string; name?: string }>;
  origin_country?: string[];
  external_ids?: { imdb_id?: string | null };
  credits?: { cast?: TmdbCredit[]; crew?: TmdbCredit[] };
  created_by?: Array<{ name?: string }>;
  images?: { posters?: Array<{ file_path?: string }>; backdrops?: Array<{ file_path?: string }> };
  translations?: { translations?: Array<{ iso_639_1?: string; data?: { overview?: string; title?: string; name?: string } }> };
  videos?: { results?: Array<{ site?: string; type?: string; key?: string; official?: boolean }> };
  release_dates?: { results?: Array<{ iso_3166_1?: string; release_dates?: Array<{ certification?: string }> }> };
  content_ratings?: { results?: Array<{ iso_3166_1?: string; rating?: string }> };
};

export type Match = {
  id: number;
  type: 'movie' | 'series';
  score: number;
  strong: boolean;
  query: string;
  pass: number;
  reason: string;
};
export type Lookup = { match: Match | null; /** TMDB did not answer (so "no match" is not final). */ failed: boolean };
type Fetcher = typeof tmdb;
type Cand = Candidate & { type: 'movie' | 'series'; query: string; pass: number };

/** Most TMDB calls one lookup may make (searches + alternative-title checks). */
const MAX_CALLS = 20;

/**
 * Finds the TMDB title for a playlist name, never just results[0]:
 *  1. the cleaned name, then its spelling variants (articles, hyphens, joined words, Persian
 *     transliteration swaps); each with the year, then without; Arabic-script names also with
 *     language=fa-IR; always include_adult=false;
 *  2. TMDB's alternative titles / translations of the best few candidates (a Latin spelling such as
 *     "Marmoulak" is only an alias of "The Lizard" / مارمولک);
 *  3. the other media type as a last resort (exact title only).
 * Candidates are merged by id and scored (tmdb-match.ts); below MIN_CONFIDENCE there is no match.
 */
export async function findTmdb(
  type: 'movie' | 'series',
  title: string,
  year: string,
  group = '',
  fetcher: Fetcher = tmdb,
): Promise<Lookup> {
  let failed = false;
  if (!norm(title)) return { match: null, failed };
  const variants = queryVariants(title);
  const want: Want = { title: unify(title).trim(), variants, year, group };
  const cands = new Map<string, Cand>();
  const aliased = new Set<string>();
  let calls = 0;
  let pass = 0;

  const search = async (t: 'movie' | 'series', q: string, withYear: boolean, language = '') => {
    if (calls >= MAX_CALLS) return;
    calls += 1;
    pass += 1;
    const path = t === 'movie' ? '/search/movie' : '/search/tv';
    const yearKey = t === 'movie' ? 'year' : 'first_air_date_year';
    const res = await fetcher<{ results?: SearchHit[] }>(path, {
      query: q,
      include_adult: 'false',
      ...(withYear ? { [yearKey]: year } : {}),
      ...(language ? { language } : {}),
    });
    if (!res) {
      failed = true;
      return;
    }
    const list = res.results ?? [];
    list.slice(0, 10).forEach((hit, rank) => {
      if (!hit.id) return;
      const k = `${t}:${hit.id}`;
      const old = cands.get(k);
      if (old) {
        if (rank < old.rank) {
          old.rank = rank;
          old.total = list.length;
        }
      } else cands.set(k, { hit, rank, total: list.length, type: t, query: q, pass });
    });
  };

  const of = (t: 'movie' | 'series') => [...cands.values()].filter((c) => c.type === t);
  const best = (t: 'movie' | 'series') => {
    const b = pickBest(of(t), want);
    return b ? { ...b, cand: b.cand as Cand } : null;
  };
  const accepted = (t: 'movie' | 'series', min = MIN_CONFIDENCE) => {
    const b = best(t);
    return b && b.score >= min ? b : null;
  };

  /** Alternative titles + translations of the best few candidates still without them. */
  const addAliases = async (t: 'movie' | 'series') => {
    const todo = of(t)
      .map((c) => ({ c, s: scoreCandidate(c, want).score }))
      .sort((a, b) => b.s - a.s)
      .map((x) => x.c)
      .filter((c) => !aliased.has(`${t}:${c.hit.id}`))
      .slice(0, 4);
    for (const c of todo) {
      if (calls >= MAX_CALLS) return;
      calls += 1;
      aliased.add(`${t}:${c.hit.id}`);
      const r = await fetcher<{
        alternative_titles?: { titles?: Array<{ title?: string }>; results?: Array<{ title?: string }> };
        translations?: { translations?: Array<{ data?: { title?: string; name?: string } }> };
      }>(`${t === 'movie' ? '/movie' : '/tv'}/${c.hit.id}`, { append_to_response: 'alternative_titles,translations' });
      if (!r) {
        failed = true;
        continue;
      }
      c.aliases = [
        ...(r.alternative_titles?.titles ?? r.alternative_titles?.results ?? []).map((a) => a.title ?? ''),
        ...(r.translations?.translations ?? []).map((x) => x.data?.title || x.data?.name || ''),
      ].filter(Boolean);
    }
  };

  const done = (b: NonNullable<ReturnType<typeof best>>, t: 'movie' | 'series'): Lookup => ({
    match: {
      id: b.id,
      type: t,
      score: b.score,
      strong: b.score >= STRONG_CONFIDENCE,
      query: b.cand.query,
      pass: b.cand.pass,
      reason: b.reason,
    },
    failed,
  });

  const years = year ? [true, false] : [false];
  for (const [qi, q] of variants.entries()) {
    for (const withYear of years) {
      await search(type, q, withYear);
      if (ARABIC_SCRIPT.test(q)) await search(type, q, withYear, 'fa-IR');
      const hit = accepted(type);
      if (hit) return done(hit, type);
    }
    // After the first spelling: look at what TMDB calls the candidates before trying odder spellings.
    if (qi === 0 && cands.size) {
      await addAliases(type);
      const hit = accepted(type);
      if (hit) return done(hit, type);
    }
  }
  if (of(type).length) {
    await addAliases(type);
    const hit = accepted(type);
    if (hit) return done(hit, type);
  }

  // Last resort: the other media type, on an exact title only.
  const other = type === 'movie' ? 'series' : 'movie';
  for (const q of variants.slice(0, 3)) {
    for (const withYear of years) {
      await search(other, q, withYear);
      const hit = accepted(other, 100);
      if (hit) return done(hit, other);
    }
  }
  return { match: null, failed };
}

export async function mediaDetails(
  source: Source,
  type: 'movie' | 'series',
  id: string,
  lang: Lang = 'en',
  /** The name / year the app shows in its list: used when the provider's own info has none. */
  hint: { name?: string; year?: string; group?: string; refreshedAt?: number } = {},
): Promise<MediaDetails> {
  // The cleaned name + year are part of the key: renaming an item in the playlist fetches fresh data.
  const listName = cleanTitle(str(hint.name));
  const cacheKey = `${source.id}|${type}|${id}|${lang}|${norm(listName.title)}|${listName.year || str(hint.year)}|${listName.tmdb?.id ?? ''}`;
  const hit = cache.get(cacheKey);
  // "Clear cache" sends the time it was pressed: answers remembered before that are asked again.
  if (hit && hit.at > (hint.refreshedAt ?? 0) && Date.now() - hit.at < hit.ttl) return hit.value;

  // 1. The provider's own info.
  const raw = await playerApi<Record<string, unknown>>(
    source,
    type === 'movie' ? { action: 'get_vod_info', vod_id: id } : { action: 'get_series_info', series_id: id },
  ).catch(() => null);
  const info = ((raw?.['info'] as Record<string, unknown> | undefined) ?? {}) as Record<string, unknown>;
  const movieData = ((raw?.['movie_data'] as Record<string, unknown> | undefined) ?? {}) as Record<string, unknown>;
  const providerName = str(info['name']) || str(movieData['name']) || str(info['title']) || str(hint.name);
  const cleaned = cleanTitle(providerName);
  const providerYear =
    /\d{4}/.exec(str(info['releasedate']) || str(info['releaseDate']) || str(info['release_date']) || str(info['year']))?.[0] ||
    cleaned.year ||
    /\b(19|20)\d{2}\b/.exec(str(hint.year))?.[0] ||
    '';
  // An explicit TMDB id wins over any search: in the name, the list name, the original name or an
  // Xtream field ("tmdb_id", "tmdb", "tmdb-id").
  const explicitIds: ExplicitId[] = [
    cleaned.tmdb,
    listName.tmdb,
    cleanTitle(str(info['o_name'])).tmdb,
    cleanTitle(str(info['original_name'])).tmdb,
    ...['tmdb_id', 'tmdb', 'tmdb-id', 'tmdbid'].map((k) => {
      const v = str(info[k]) || str(movieData[k]);
      return /^\d+$/.test(v) && Number(v) > 0 ? ({ id: Number(v) } as ExplicitId) : (extractTmdbId(`tmdb:${v}`).explicit ?? undefined);
    }),
  ].filter((x): x is ExplicitId => !!x);
  const explicit = explicitIds[0];
  // Other names to search with when the first finds nothing (list name, original name).
  const altNames = [str(hint.name), str(info['o_name']), str(info['original_name'])]
    .map((n) => cleanTitle(n).title)
    .filter((n, i, all) => n && n !== cleaned.title && all.indexOf(n) === i);

  const base: MediaDetails = {
    type,
    title: cleaned.title || providerName,
    originalTitle: str(info['o_name']),
    tagline: '',
    overview: str(info['plot']) || str(info['description']),
    year: providerYear,
    runtime: Math.round(num(info['duration_secs']) / 60) || num(info['episode_run_time']),
    rating: num(info['rating']),
    votes: 0,
    genres: str(info['genre'])
      .split(/[,/]/)
      .map((g) => g.trim())
      .filter(Boolean),
    certification: str(info['mpaa_rating']) || str(info['age']),
    director: str(info['director']),
    creator: '',
    country: str(info['country']),
    cast: str(info['cast'] || info['actors'])
      .split(',')
      .map((n) => n.trim())
      .filter(Boolean)
      .slice(0, 15)
      .map((name) => ({ name, role: '', photo: '' })),
    poster: str(info['movie_image']) || str(info['cover']) || str(info['cover_big']),
    backdrop: firstOf(info['backdrop_path']),
    trailer: youtubeKey(str(info['youtube_trailer'])),
    // Without TMDB the provider's / playlist's own id still lets the apps ask for subtitles and seasons.
    tmdbId: explicit?.id ?? 0,
    imdbId: '',
    seasons: Array.isArray(raw?.['seasons']) ? (raw?.['seasons'] as unknown[]).length : 0,
    episodes: Object.values((raw?.['episodes'] as Record<string, unknown[]> | undefined) ?? {}).reduce(
      (n, list) => n + (Array.isArray(list) ? list.length : 0),
      0,
    ),
  };

  // 2. TMDB enrichment (any failure leaves the provider's info as it is).
  let details: MediaDetails = base;
  let ttl = TTL;
  if (key()) {
    try {
      const r = await enrich(base, type, lang, altNames, str(hint.group), explicit);
      details = r.details;
      ttl = r.details.tmdbId ? TTL : r.failed ? ERROR_TTL : MISS_TTL;
    } catch (err) {
      console.error('[tmdb] enrich failed', err);
      ttl = ERROR_TTL;
    }
  } else ttl = ERROR_TTL;

  cache.set(cacheKey, { at: Date.now(), ttl, value: details });
  if (cache.size > 2000) cache.delete(cache.keys().next().value as string);
  return details;
}

async function enrich(
  base: MediaDetails,
  type: 'movie' | 'series',
  lang: Lang,
  altNames: string[] = [],
  group = '',
  explicit?: ExplicitId,
): Promise<{ details: MediaDetails; failed: boolean }> {
  let tmdbId = 0;
  let mtype = type;
  let strong = false;
  let failed = false;
  let match: MatchInfo | undefined;
  const append = (t: 'movie' | 'series') =>
    t === 'movie' ? 'credits,videos,release_dates,images,translations' : 'credits,videos,external_ids,content_ratings,images,translations';
  const fetchFull = (id: number, t: 'movie' | 'series') =>
    tmdb<TmdbFull>(t === 'movie' ? `/movie/${id}` : `/tv/${id}`, {
      language: tmdbLang(lang),
      append_to_response: append(t),
      include_video_language: lang === 'en' ? 'en,null' : `${lang},en,null`,
      include_image_language: 'en,fa,null',
    });
  let t: TmdbFull | null = null;

  // 1. An explicit id needs no search (the tab's media type unless the id names one).
  if (explicit) {
    const first = explicit.type ?? type;
    t = await fetchFull(explicit.id, first);
    mtype = first;
    if (!t?.id && !explicit.type) {
      const other = first === 'movie' ? 'series' : 'movie';
      const t2 = await fetchFull(explicit.id, other);
      if (t2?.id) ({ t, mtype } = { t: t2, mtype: other });
    }
    if (t?.id) {
      tmdbId = t.id;
      strong = true;
      match = { how: 'tmdb id', id: t.id, type: mtype, score: 1000, strong: true, query: `tmdb:${explicit.id}`, pass: 0, reason: 'explicit TMDB id' };
    } else if (!t) failed = true;
    t = t?.id ? t : null;
  }

  // 2. Otherwise a scored search; only a confident candidate is trusted.
  if (!tmdbId) {
    // "Dune: Part Two - Extended" → "Dune: Part Two"; then the list / original names.
    const shorter = base.title.split(/\s[-–|]\s/)[0]?.trim() ?? '';
    for (const name of [base.title, shorter !== base.title ? shorter : '', ...altNames]) {
      if (tmdbId || !name) continue;
      const r = await findTmdb(type, name, base.year, group);
      failed ||= r.failed;
      if (r.match) {
        ({ id: tmdbId, type: mtype, strong } = r.match);
        match = { how: 'search', ...r.match };
      }
    }
    // No confident match: the provider's own title, poster and facts stay; nothing from another film.
    if (!tmdbId) return { details: base, failed };
    t = await fetchFull(tmdbId, mtype);
  }
  if (!t?.id) return { details: base, failed: true };
  const path = mtype === 'movie' ? `/movie/${tmdbId}` : `/tv/${tmdbId}`;
  // No story (or genres) in the page's language: TMDB's English ones (translations first, then one call).
  const enTrans = t.translations?.translations?.find((x) => x.iso_639_1 === 'en')?.data;
  let enOverview = enTrans?.overview ?? '';
  let enGenres: string[] = [];
  if (lang !== 'en' && ((!t.overview && !enOverview) || !(t.genres ?? []).length)) {
    const en = await tmdb<TmdbFull>(path);
    enOverview = enOverview || (en?.overview ?? '');
    enGenres = (en?.genres ?? []).map((g) => g.name ?? '').filter(Boolean);
  }
  const crew = t.credits?.crew ?? [];
  const videos = t.videos?.results ?? [];
  const trailer =
    videos.find((v) => v.site === 'YouTube' && v.type === 'Trailer' && v.official)?.key ||
    videos.find((v) => v.site === 'YouTube' && v.type === 'Trailer')?.key ||
    videos.find((v) => v.site === 'YouTube' && v.type === 'Teaser')?.key ||
    '';
  const cert =
    mtype === 'movie'
      ? (t.release_dates?.results ?? [])
          .filter((r) => r.iso_3166_1 === 'GB' || r.iso_3166_1 === 'US')
          .flatMap((r) => r.release_dates ?? [])
          .map((r) => r.certification ?? '')
          .find(Boolean) ?? ''
      : (t.content_ratings?.results ?? []).find((r) => r.iso_3166_1 === 'GB' || r.iso_3166_1 === 'US')?.rating ?? '';
  const genres = (t.genres ?? []).map((g) => g.name ?? '').filter(Boolean);
  const names = (list: Array<{ name?: string }> | undefined) =>
    (list ?? []).map((c) => c.name ?? '').filter(Boolean).slice(0, 2).join(', ');
  // Story: TMDB's in the page's language; otherwise the provider's; English TMDB last.
  const overview =
    lang === 'en'
      ? base.overview || t.overview || enOverview
      : t.overview || base.overview || enOverview;
  // Poster and backdrop always come from the same title. A certain match uses TMDB's poster and
  // w1280 backdrop (large poster as the backdrop when it has none); a weaker match keeps the
  // provider's own artwork, and TMDB's is only used when the provider has no poster at all.
  const posterPath = t.poster_path || t.images?.posters?.[0]?.file_path || '';
  const backdropPath = t.backdrop_path || t.images?.backdrops?.[0]?.file_path || '';
  const tmdbPoster = posterPath ? `${IMG}/w500${posterPath}` : '';
  const useTmdbArt = strong || !base.poster;
  const poster = useTmdbArt ? tmdbPoster || base.poster : base.poster;
  const backdrop = useTmdbArt
    ? backdropPath
      ? `${IMG}/w1280${backdropPath}`
      : posterPath
        ? `${IMG}/w780${posterPath}`
        : base.backdrop
    : base.backdrop;
  const details: MediaDetails = {
    ...base,
    title: t.title || t.name || base.title,
    originalTitle: t.original_title || t.original_name || base.originalTitle,
    tagline: t.tagline ?? '',
    overview,
    year: (t.release_date || t.first_air_date || '').slice(0, 4) || base.year,
    runtime: t.runtime || t.episode_run_time?.[0] || base.runtime,
    rating: t.vote_average ? Math.round(t.vote_average * 10) / 10 : base.rating,
    votes: t.vote_count ?? 0,
    genres: genres.length ? genres : enGenres.length ? enGenres : base.genres,
    certification: cert || base.certification,
    director: names(crew.filter((c) => c.job === 'Director')) || (mtype === 'movie' ? base.director : ''),
    creator: names(t.created_by),
    country:
      (t.production_countries ?? []).map((c) => c.name ?? '').filter(Boolean).slice(0, 2).join(', ') ||
      (t.origin_country ?? []).slice(0, 2).join(', ') ||
      base.country,
    cast: (t.credits?.cast ?? []).length
      ? (t.credits?.cast ?? []).slice(0, 15).map((c) => ({
          name: c.name ?? '',
          role: c.character ?? '',
          photo: c.profile_path ? `${IMG}/w185${c.profile_path}` : '',
        }))
      : base.cast,
    poster,
    backdrop,
    trailer: trailer || base.trailer,
    tmdbId: t.id,
    imdbId: t.imdb_id || t.external_ids?.imdb_id || '',
    seasons: t.number_of_seasons || base.seasons,
    episodes: t.number_of_episodes || base.episodes,
    ...(match ? { match: { ...match, strong } } : {}),
  };
  return { details, failed };
}

export type EpisodeFacts = { episode: number; name: string; overview: string; still: string; runtime: number; airDate: string };

const seasonCache = new Map<string, { at: number; value: EpisodeFacts[] }>();

/** One season's episodes from TMDB (still, real name, summary); empty when unknown. */
export async function seasonFacts(tmdbId: number, season: number, lang: Lang = 'en'): Promise<EpisodeFacts[]> {
  if (!key() || !tmdbId || season < 0) return [];
  const ck = `${tmdbId}|${season}|${lang}`;
  const hit = seasonCache.get(ck);
  if (hit && Date.now() - hit.at < TTL) return hit.value;
  type Ep = { episode_number?: number; name?: string; overview?: string; still_path?: string | null; runtime?: number; air_date?: string };
  const t = await tmdb<{ episodes?: Ep[] }>(`/tv/${tmdbId}/season/${season}`, { language: tmdbLang(lang) });
  const eps = t?.episodes ?? [];
  // Summaries missing in the page's language: the English ones.
  let en = new Map<number, Ep>();
  if (lang !== 'en' && eps.some((e) => !e.overview)) {
    const e2 = await tmdb<{ episodes?: Ep[] }>(`/tv/${tmdbId}/season/${season}`);
    en = new Map((e2?.episodes ?? []).map((e) => [e.episode_number ?? 0, e]));
  }
  const value = eps.map((e) => {
    const n = e.episode_number ?? 0;
    const generic = (s: string | undefined) => !s || /^(episode|ئەڵقەی|الحلقة|الحلقه)\s*\d+$/i.test(s.trim());
    return {
      episode: n,
      name: generic(e.name) ? en.get(n)?.name ?? e.name ?? '' : e.name ?? '',
      overview: e.overview || en.get(n)?.overview || '',
      still: e.still_path ? `${IMG}/w300${e.still_path}` : '',
      runtime: e.runtime ?? 0,
      airDate: e.air_date ?? '',
    };
  });
  seasonCache.set(ck, { at: Date.now(), value });
  if (seasonCache.size > 2000) seasonCache.delete(seasonCache.keys().next().value as string);
  return value;
}
