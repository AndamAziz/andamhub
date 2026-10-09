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

const cache = new Map<string, { at: number; value: MediaDetails }>();
const TTL = 6 * 60 * 60 * 1000;
/** A title TMDB could not be matched for (or TMDB was down) is asked again soon, not hours later. */
const MISS_TTL = 10 * 60 * 1000;

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

type SearchHit = { id?: number; title?: string; name?: string; original_title?: string; original_name?: string };

/** Search with the year first, then without; an exact title match wins, else the first result. */
async function findTmdbId(type: 'movie' | 'series', title: string, year: string): Promise<number> {
  if (!title) return 0;
  const path = type === 'movie' ? '/search/movie' : '/search/tv';
  const yearKey = type === 'movie' ? 'year' : 'first_air_date_year';
  const want = norm(title);
  const pick = (hits: SearchHit[] | undefined) => {
    const list = hits ?? [];
    const exact = list.find((h) => [h.title, h.name, h.original_title, h.original_name].some((n) => n && norm(n) === want));
    return (exact ?? list[0])?.id ?? 0;
  };
  if (year) {
    const withYear = await tmdb<{ results?: SearchHit[] }>(path, { query: title, [yearKey]: year });
    const hit = pick(withYear?.results);
    if (hit) return hit;
  }
  const any = await tmdb<{ results?: SearchHit[] }>(path, { query: title });
  return pick(any?.results);
}

export async function mediaDetails(
  source: Source,
  type: 'movie' | 'series',
  id: string,
  lang: Lang = 'en',
  /** The name / year the app shows in its list: used when the provider's own info has none. */
  hint: { name?: string; year?: string } = {},
): Promise<MediaDetails> {
  const cacheKey = `${source.id}|${type}|${id}|${lang}`;
  const hit = cache.get(cacheKey);
  if (hit && Date.now() - hit.at < (hit.value.tmdbId ? TTL : MISS_TTL)) return hit.value;

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
  if (key()) {
    try {
      details = await enrich(base, type, lang, altNames);
    } catch (err) {
      console.error('[tmdb] enrich failed', err);
    }
  }

  cache.set(cacheKey, { at: Date.now(), value: details });
  if (cache.size > 2000) cache.delete(cache.keys().next().value as string);
  return details;
}

async function enrich(base: MediaDetails, type: 'movie' | 'series', lang: Lang, altNames: string[] = []): Promise<MediaDetails> {
  let tmdbId = base.tmdbId || (await findTmdbId(type, base.title, base.year));
  // "Dune: Part Two - Extended" → "Dune: Part Two"; then the list / original names.
  const shorter = base.title.split(/\s[-–|]\s/)[0]?.trim() ?? '';
  for (const name of [shorter !== base.title ? shorter : '', ...altNames]) {
    if (tmdbId || !name) continue;
    tmdbId = await findTmdbId(type, name, base.year);
  }
  if (!tmdbId) return base;
  const path = type === 'movie' ? `/movie/${tmdbId}` : `/tv/${tmdbId}`;
  const t = await tmdb<TmdbFull>(path, {
    language: tmdbLang(lang),
    append_to_response: type === 'movie' ? 'credits,videos,release_dates' : 'credits,videos,external_ids,content_ratings',
    include_video_language: lang === 'en' ? 'en,null' : `${lang},en,null`,
  });
  if (!t?.id) return { ...base, tmdbId };
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
    type === 'movie'
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
  return {
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
    director: names(crew.filter((c) => c.job === 'Director')) || (type === 'movie' ? base.director : ''),
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
    // The provider's own poster first; TMDB's only when it has none.
    poster: base.poster || (t.poster_path ? `${IMG}/w500${t.poster_path}` : ''),
    backdrop: t.backdrop_path ? `${IMG}/w780${t.backdrop_path}` : base.backdrop,
    trailer: trailer || base.trailer,
    tmdbId: t.id,
    imdbId: t.imdb_id || t.external_ids?.imdb_id || '',
    seasons: t.number_of_seasons || base.seasons,
    episodes: t.number_of_episodes || base.episodes,
  };
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
