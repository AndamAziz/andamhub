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
};

const cache = new Map<string, { at: number; ttl: number; value: MediaDetails }>();
const TTL = 6 * 60 * 60 * 1000;
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
export function cleanTitle(raw: string): { title: string; year: string } {
  let t = (raw ?? '').replace(/[\u200e\u200f\u202a-\u202e]/g, ' ');
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
  return { title: t, year };
}

const norm = (s: string) =>
  s
    .toLowerCase()
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/&/g, 'and')
    .replace(/[^\p{L}\p{N}]+/gu, '');

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
  videos?: { results?: Array<{ site?: string; type?: string; key?: string; official?: boolean }> };
  release_dates?: { results?: Array<{ iso_3166_1?: string; release_dates?: Array<{ certification?: string }> }> };
  content_ratings?: { results?: Array<{ iso_3166_1?: string; rating?: string }> };
};

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
export const MIN_CONFIDENCE = 60;
/** From this score on the match is certain enough to use TMDB's poster / backdrop over the provider's. */
export const STRONG_CONFIDENCE = 120;

const ARABIC_SCRIPT = /[؀-ۿݐ-ݿﭐ-﷿ﹰ-﻿]/;
/** Languages written in Arabic script, ranked by how likely a Persian / Kurdish / Arabic name is theirs. */
const SIBLINGS = new Set(['ku', 'ckb', 'ar', 'tr', 'ps', 'ur']);

/** Does the playlist group / category look Persian, Kurdish or Arabic? */
export function groupLanguage(group: string): 'fa' | 'ku' | 'ar' | 'tr' | '' {
  const g = group.toLowerCase();
  if (/persian|farsi|iran|پارسی|فارسی|ایران/.test(g)) return 'fa';
  if (/kurd|kurdi|sorani|کورد|کوردی/.test(g)) return 'ku';
  if (/arab|عرب/.test(g)) return 'ar';
  if (/turk|türk/.test(g)) return 'tr';
  return '';
}

/**
 * How well one TMDB search result fits the name (and year) of a playlist item.
 * Exact title / original-title matches dominate, a matching year (±1) and the right language add to
 * it, popularity only breaks ties. `rank` is TMDB's own position for the query (0 = first).
 */
export function scoreHit(
  hit: SearchHit,
  want: { title: string; year: string; group?: string },
  rank: number,
): number {
  const wanted = norm(want.title);
  if (!wanted) return 0;
  const names = [hit.title, hit.name].filter(Boolean).map((n) => norm(n as string));
  const originals = [hit.original_title, hit.original_name].filter(Boolean).map((n) => norm(n as string));
  let s = 0;
  if (originals.includes(wanted)) s += 100;
  else if (names.includes(wanted)) s += 90;
  else if (wanted.length >= 4 && [...names, ...originals].some((n) => n.length >= 4 && (n.startsWith(wanted) || wanted.startsWith(n)))) s += 20;

  const nonLatin = ARABIC_SCRIPT.test(want.title) || !/[A-Za-z]/.test(want.title);
  // A title written in another script can only be found through TMDB's own translations: its ranking counts.
  s += nonLatin ? [25, 15, 8][rank] ?? 0 : [8, 4, 2][rank] ?? 0;

  const hitYear = Number((hit.release_date || hit.first_air_date || '').slice(0, 4));
  const wantYear = Number(want.year);
  if (wantYear && hitYear) {
    const d = Math.abs(hitYear - wantYear);
    s += d === 0 ? 60 : d === 1 ? 45 : -25;
  }

  const lang = (hit.original_language ?? '').toLowerCase();
  const gl = want.group ? groupLanguage(want.group) : '';
  const hint = nonLatin ? gl || 'fa' : gl; // Arabic-script names are Persian unless the group says otherwise
  if (lang && hint) {
    if (lang === hint || (hint === 'ku' && lang === 'ckb')) s += 40;
    else if (SIBLINGS.has(lang) || lang === 'fa') s += nonLatin ? 15 : 5;
    else s -= nonLatin ? 25 : 15;
  }

  // Tie-breakers only.
  s += Math.min(5, Math.log10(1 + (hit.popularity ?? 0)) * 2) + Math.min(3, Math.log10(1 + (hit.vote_count ?? 0)));
  return s;
}

export function pickBest(
  hits: SearchHit[],
  want: { title: string; year: string; group?: string },
): { id: number; score: number } | null {
  let best: { id: number; score: number } | null = null;
  hits.slice(0, 10).forEach((h, i) => {
    if (!h.id) return;
    const score = scoreHit(h, want, i);
    if (!best || score > best.score) best = { id: h.id, score };
  });
  return best;
}

export type Match = { id: number; type: 'movie' | 'series'; score: number; strong: boolean };
export type Lookup = { match: Match | null; /** TMDB did not answer (so "no match" is not final). */ failed: boolean };

/**
 * Finds the TMDB title for a playlist name: with the year first, then without, then the other media
 * type as a last resort (only on a near-certain exact match). Candidates are scored, never just taken
 * from the top of the list; below MIN_CONFIDENCE there is no match.
 */
export async function findTmdb(
  type: 'movie' | 'series',
  title: string,
  year: string,
  group = '',
  fetcher: typeof tmdb = tmdb,
): Promise<Lookup> {
  let failed = false;
  if (!norm(title)) return { match: null, failed };
  const search = async (t: 'movie' | 'series', withYear: boolean) => {
    const path = t === 'movie' ? '/search/movie' : '/search/tv';
    const yearKey = t === 'movie' ? 'year' : 'first_air_date_year';
    const res = await fetcher<{ results?: SearchHit[] }>(path, withYear ? { query: title, [yearKey]: year } : { query: title });
    if (!res) failed = true;
    return pickBest(res?.results ?? [], { title, year, group });
  };
  const done = (t: 'movie' | 'series', b: { id: number; score: number }): Lookup => ({
    match: { id: b.id, type: t, score: b.score, strong: b.score >= STRONG_CONFIDENCE },
    failed,
  });
  for (const withYear of year ? [true, false] : [false]) {
    const b = await search(type, withYear);
    if (b && b.score >= MIN_CONFIDENCE) return done(type, b);
  }
  const other = type === 'movie' ? 'series' : 'movie';
  for (const withYear of year ? [true, false] : [false]) {
    const b = await search(other, withYear);
    if (b && b.score >= 100) return done(other, b);
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
  const cacheKey = `${source.id}|${type}|${id}|${lang}|${norm(listName.title)}|${listName.year || str(hint.year)}`;
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
    tmdbId: num(info['tmdb_id'] || info['tmdb']),
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
      const r = await enrich(base, type, lang, altNames, str(hint.group));
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
): Promise<{ details: MediaDetails; failed: boolean }> {
  let tmdbId = base.tmdbId;
  let mtype = type;
  // A provider-supplied tmdb id is trusted; a name search is only trusted above the confidence bar.
  let strong = tmdbId > 0;
  let failed = false;
  if (!tmdbId) {
    // "Dune: Part Two - Extended" → "Dune: Part Two"; then the list / original names.
    const shorter = base.title.split(/\s[-–|]\s/)[0]?.trim() ?? '';
    for (const name of [base.title, shorter !== base.title ? shorter : '', ...altNames]) {
      if (tmdbId || !name) continue;
      const r = await findTmdb(type, name, base.year, group);
      failed ||= r.failed;
      if (r.match) ({ id: tmdbId, type: mtype, strong } = r.match);
    }
  }
  // No confident match: the provider's own title, poster and facts stay; nothing from another film.
  if (!tmdbId) return { details: base, failed };
  const path = mtype === 'movie' ? `/movie/${tmdbId}` : `/tv/${tmdbId}`;
  const t = await tmdb<TmdbFull>(path, {
    language: tmdbLang(lang),
    append_to_response: mtype === 'movie' ? 'credits,videos,release_dates' : 'credits,videos,external_ids,content_ratings',
    include_video_language: lang === 'en' ? 'en,null' : `${lang},en,null`,
  });
  if (!t?.id) return { details: base, failed: true };
  // No story (or genres) in the page's language: TMDB's English ones.
  let enOverview = '';
  let enGenres: string[] = [];
  if (lang !== 'en' && (!t.overview || !(t.genres ?? []).length)) {
    const en = await tmdb<TmdbFull>(path);
    enOverview = en?.overview ?? '';
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
      ? base.overview || t.overview || ''
      : t.overview || base.overview || enOverview;
  // Poster and backdrop always come from the same title. A certain match uses TMDB's poster and
  // w1280 backdrop (large poster as the backdrop when it has none); a weaker match keeps the
  // provider's own artwork, and TMDB's is only used when the provider has no poster at all.
  const tmdbPoster = t.poster_path ? `${IMG}/w500${t.poster_path}` : '';
  const useTmdbArt = strong || !base.poster;
  const poster = useTmdbArt ? tmdbPoster || base.poster : base.poster;
  const backdrop = useTmdbArt
    ? t.backdrop_path
      ? `${IMG}/w1280${t.backdrop_path}`
      : t.poster_path
        ? `${IMG}/w780${t.poster_path}`
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
