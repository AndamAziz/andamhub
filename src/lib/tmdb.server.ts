/**
 * Film / series details for the apps' detail pages: the provider's own info (get_vod_info /
 * get_series_info) enriched with TMDB (cast photos, backdrop, trailer, genres, runtime).
 *
 * TMDB is optional: without TMDB_API_KEY (a v3 key or a v4 read token) the provider's info is
 * returned on its own. Results are kept in memory for a few hours.
 */
import { playerApi, type Source } from '@/lib/xtream';

const TMDB = 'https://api.themoviedb.org/3';
const IMG = 'https://image.tmdb.org/t/p';

export type CastMember = { name: string; role: string; photo: string };

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
  cast: CastMember[];
  poster: string;
  backdrop: string;
  trailer: string;
  tmdbId: number;
  imdbId: string;
  seasons: number;
};

const cache = new Map<string, { at: number; value: MediaDetails }>();
const TTL = 6 * 60 * 60 * 1000;

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
    const res = await fetch(url, { headers });
    if (!res.ok) return null;
    return (await res.json()) as T;
  } catch {
    return null;
  }
}

/** "EN - The Batman (2022) [4K]" → { title: "The Batman", year: "2022" } */
export function cleanTitle(raw: string): { title: string; year: string } {
  let t = raw ?? '';
  const year = /\b(19\d{2}|20\d{2})\b/.exec(t)?.[1] ?? '';
  t = t
    .replace(/^\s*[|[(]?[A-Z]{2,3}[|\])]?\s*[-:|]\s*/u, '') // "EN - ", "|AR| ", "[FR] "
    .replace(/\[[^\]]*\]|\([^)]*\)/g, ' ')
    .replace(/\b(19|20)\d{2}\b/g, ' ')
    .replace(/\b(4K|UHD|FHD|HD|SD|HEVC|H\.?26[45]|1080p|720p|2160p|MULTI|VOSTFR|SUB|DUB(BED)?|NF|AMZN)\b/gi, ' ')
    .replace(/[-_|:]+\s*$/g, ' ')
    .replace(/\s{2,}/g, ' ')
    .trim();
  return { title: t, year };
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
  external_ids?: { imdb_id?: string | null };
  credits?: { cast?: TmdbCredit[]; crew?: TmdbCredit[] };
  created_by?: Array<{ name?: string }>;
  videos?: { results?: Array<{ site?: string; type?: string; key?: string; official?: boolean }> };
  release_dates?: { results?: Array<{ iso_3166_1?: string; release_dates?: Array<{ certification?: string }> }> };
  content_ratings?: { results?: Array<{ iso_3166_1?: string; rating?: string }> };
};

async function findTmdbId(type: 'movie' | 'series', title: string, year: string): Promise<number> {
  if (!title) return 0;
  const path = type === 'movie' ? '/search/movie' : '/search/tv';
  const yearKey = type === 'movie' ? 'year' : 'first_air_date_year';
  const withYear = await tmdb<{ results?: Array<{ id?: number }> }>(path, { query: title, ...(year ? { [yearKey]: year } : {}) });
  const hit = withYear?.results?.[0]?.id;
  if (hit) return hit;
  if (!year) return 0;
  const any = await tmdb<{ results?: Array<{ id?: number }> }>(path, { query: title });
  return any?.results?.[0]?.id ?? 0;
}

export async function mediaDetails(source: Source, type: 'movie' | 'series', id: string): Promise<MediaDetails> {
  const cacheKey = `${source.id}|${type}|${id}`;
  const hit = cache.get(cacheKey);
  if (hit && Date.now() - hit.at < TTL) return hit.value;

  // 1. The provider's own info.
  const raw = await playerApi<Record<string, unknown>>(
    source,
    type === 'movie' ? { action: 'get_vod_info', vod_id: id } : { action: 'get_series_info', series_id: id },
  ).catch(() => null);
  const info = ((raw?.['info'] as Record<string, unknown> | undefined) ?? {}) as Record<string, unknown>;
  const movieData = ((raw?.['movie_data'] as Record<string, unknown> | undefined) ?? {}) as Record<string, unknown>;
  const providerName = str(info['name']) || str(movieData['name']) || str(info['title']);
  const cleaned = cleanTitle(providerName);
  const providerYear =
    /\d{4}/.exec(str(info['releasedate']) || str(info['releaseDate']) || str(info['release_date']) || str(info['year']))?.[0] ||
    cleaned.year;

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
  };

  // 2. TMDB enrichment.
  let details: MediaDetails = base;
  if (key()) {
    const tmdbId = base.tmdbId || (await findTmdbId(type, base.title, base.year));
    if (tmdbId) {
      const path = type === 'movie' ? `/movie/${tmdbId}` : `/tv/${tmdbId}`;
      const t = await tmdb<TmdbFull>(path, {
        append_to_response: type === 'movie' ? 'credits,videos,release_dates' : 'credits,videos,external_ids,content_ratings',
      });
      if (t?.id) {
        const crew = t.credits?.crew ?? [];
        const trailer =
          (t.videos?.results ?? []).find((v) => v.site === 'YouTube' && v.type === 'Trailer' && v.official)?.key ||
          (t.videos?.results ?? []).find((v) => v.site === 'YouTube' && v.type === 'Trailer')?.key ||
          '';
        const cert =
          type === 'movie'
            ? (t.release_dates?.results ?? [])
                .filter((r) => r.iso_3166_1 === 'GB' || r.iso_3166_1 === 'US')
                .flatMap((r) => r.release_dates ?? [])
                .map((r) => r.certification ?? '')
                .find(Boolean) ?? ''
            : (t.content_ratings?.results ?? []).find((r) => r.iso_3166_1 === 'GB' || r.iso_3166_1 === 'US')?.rating ?? '';
        details = {
          ...base,
          title: t.title || t.name || base.title,
          originalTitle: t.original_title || t.original_name || base.originalTitle,
          tagline: t.tagline ?? '',
          overview: t.overview || base.overview,
          year: (t.release_date || t.first_air_date || '').slice(0, 4) || base.year,
          runtime: t.runtime || t.episode_run_time?.[0] || base.runtime,
          rating: t.vote_average ? Math.round(t.vote_average * 10) / 10 : base.rating,
          votes: t.vote_count ?? 0,
          genres: (t.genres ?? []).map((g) => g.name ?? '').filter(Boolean).length
            ? (t.genres ?? []).map((g) => g.name ?? '').filter(Boolean)
            : base.genres,
          certification: cert || base.certification,
          director:
            crew.filter((c) => c.job === 'Director').map((c) => c.name ?? '').filter(Boolean).slice(0, 2).join(', ') ||
            (t.created_by ?? []).map((c) => c.name ?? '').filter(Boolean).slice(0, 2).join(', ') ||
            base.director,
          cast: (t.credits?.cast ?? []).length
            ? (t.credits?.cast ?? []).slice(0, 15).map((c) => ({
                name: c.name ?? '',
                role: c.character ?? '',
                photo: c.profile_path ? `${IMG}/w185${c.profile_path}` : '',
              }))
            : base.cast,
          poster: t.poster_path ? `${IMG}/w500${t.poster_path}` : base.poster,
          backdrop: t.backdrop_path ? `${IMG}/w1280${t.backdrop_path}` : base.backdrop,
          trailer: trailer || base.trailer,
          tmdbId: t.id,
          imdbId: t.imdb_id || t.external_ids?.imdb_id || '',
          seasons: t.number_of_seasons || base.seasons,
        };
      }
    }
  }

  cache.set(cacheKey, { at: Date.now(), value: details });
  if (cache.size > 2000) cache.delete(cache.keys().next().value as string);
  return details;
}
