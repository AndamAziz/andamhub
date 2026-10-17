import { createFileRoute } from "@tanstack/react-router";
import { z } from "zod";

const TMDB = "https://api.themoviedb.org/3";
const IMG = "https://image.tmdb.org/t/p";

const paramsSchema = z.object({
  kind: z.enum(["movies", "series", "details", "season"]).default("movies"),
  page: z.coerce.number().int().min(1).max(500).default(1),
  q: z.string().trim().max(120).optional(),
  sort: z.string().trim().max(40).optional(),
  genre: z.string().regex(/^\d{1,6}$/).optional(),
  id: z.string().regex(/^\d{1,10}$/).optional(),
  season: z.coerce.number().int().min(0).max(200).optional(),
});

function authFetch(path: string, key: string, search: Record<string, string>) {
  const url = new URL(TMDB + path);
  const headers: Record<string, string> = { accept: "application/json" };
  if (key.startsWith("ey")) headers["Authorization"] = `Bearer ${key}`;
  else url.searchParams.set("api_key", key);
  for (const [k, v] of Object.entries(search)) if (v) url.searchParams.set(k, v);
  return fetch(url, { headers });
}

type TmdbGenre = { id?: number; name?: string };
type TmdbSeason = { season_number?: number; name?: string; episode_count?: number };
type TmdbEpisode = {
  episode_number?: number;
  name?: string;
  overview?: string;
  still_path?: string | null;
  air_date?: string;
};
type TmdbDetails = {
  id?: number;
  title?: string;
  name?: string;
  overview?: string;
  backdrop_path?: string | null;
  poster_path?: string | null;
  release_date?: string;
  first_air_date?: string;
  vote_average?: number;
  runtime?: number | null;
  genres?: TmdbGenre[];
  imdb_id?: string | null;
  external_ids?: { imdb_id?: string | null };
  seasons?: TmdbSeason[];
};
type TmdbSeasonDetails = { episodes?: TmdbEpisode[] };

function mapItem(raw: Record<string, unknown>, kind: "movies" | "series") {
  const date = String(raw["release_date"] ?? raw["first_air_date"] ?? "");
  return {
    tmdbId: Number(raw["id"]),
    type: kind === "movies" ? ("movie" as const) : ("tv" as const),
    title: String(raw["title"] ?? raw["name"] ?? "Untitled"),
    overview: String(raw["overview"] ?? ""),
    poster: raw["poster_path"] ? `${IMG}/w500${raw["poster_path"]}` : null,
    backdrop: raw["backdrop_path"] ? `${IMG}/w1280${raw["backdrop_path"]}` : null,
    year: date ? date.slice(0, 4) : "",
    rating: raw["vote_average"] ? Number(raw["vote_average"]).toFixed(1) : null,
  };
}

async function handle(request: Request) {
  const key = process.env["TMDB_API_KEY"];
  if (!key) return Response.json({ error: "TMDB key not configured" }, { status: 500 });

  const url = new URL(request.url);
  const parsed = paramsSchema.safeParse(Object.fromEntries(url.searchParams));
  if (!parsed.success) return Response.json({ error: "Invalid parameters" }, { status: 400 });
  const { kind, page, q, sort, genre, id, season } = parsed.data;

  // Movies / Shows are activation-gated: a locked viewer gets no catalog data.
  const { canOpen, lockedResponse, resolveAccessCached: resolveAccess } = await import("@/lib/access.server");
  const access = await resolveAccess(request);
  const wants =
    kind === "movies"
      ? "movies"
      : kind === "series"
        ? "series"
        : url.searchParams.get("type") === "tv" || kind === "season"
          ? "series"
          : "movies";
  // Metadata lookups (details/season) are also used to enrich Live TV titles,
  // so any unlocked section is enough for those.
  const lookup = kind === "details" || kind === "season";
  const ok = lookup
    ? canOpen(access, "movies") || canOpen(access, "series") || canOpen(access, "live")
    : canOpen(access, wants);
  if (!ok) return lockedResponse(wants);

  try {
    if (kind === "details") {
      if (!id) return Response.json({ error: "id required" }, { status: 400 });
      const type = url.searchParams.get("type") === "tv" ? "tv" : "movie";
      const res = await authFetch(`/${type}/${id}`, key, {
        append_to_response: "external_ids",
      });
      if (!res.ok) return Response.json({ error: "TMDB unavailable" }, { status: 502 });
      const d = (await res.json()) as TmdbDetails;
      return Response.json({
        tmdbId: Number(d.id),
        type,
        title: String(d.title ?? d.name ?? ""),
        overview: String(d.overview ?? ""),
        backdrop: d.backdrop_path ? `${IMG}/w1280${d.backdrop_path}` : null,
        poster: d.poster_path ? `${IMG}/w500${d.poster_path}` : null,
        year: String(d.release_date ?? d.first_air_date ?? "").slice(0, 4),
        rating: d.vote_average ? Number(d.vote_average).toFixed(1) : null,
        runtime: d.runtime ?? null,
        genres: (d.genres ?? []).map((g: TmdbGenre) => String(g.name)),
        imdbId: d.external_ids?.imdb_id ?? d.imdb_id ?? null,
        seasons: (d.seasons ?? [])
          .filter((s: TmdbSeason) => Number(s.season_number) > 0)
          .map((s: TmdbSeason) => ({
            season: Number(s.season_number),
            name: String(s.name ?? ""),
            episodes: Number(s.episode_count ?? 0),
          })),
      });
    }

    if (kind === "season") {
      if (!id || season == null)
        return Response.json({ error: "id and season required" }, { status: 400 });
      const res = await authFetch(`/tv/${id}/season/${season}`, key, {});
      if (!res.ok) return Response.json({ error: "TMDB unavailable" }, { status: 502 });
      const d = (await res.json()) as TmdbSeasonDetails;
      return Response.json({
        season,
        episodes: (d.episodes ?? []).map((e: TmdbEpisode) => ({
          episode: Number(e.episode_number),
          name: String(e.name ?? `Episode ${e.episode_number}`),
          overview: String(e.overview ?? ""),
          still: e.still_path ? `${IMG}/w300${e.still_path}` : null,
          air: String(e.air_date ?? ""),
        })),
      });
    }

    const type = kind === "movies" ? "movie" : "tv";
    const res = q
      ? await authFetch(`/search/${type}`, key, {
          query: q,
          page: String(page),
          include_adult: "false",
        })
      : sort === "trending"
        ? await authFetch(`/trending/${type}/week`, key, { page: String(page) })
        : await authFetch(`/discover/${type}`, key, {
            page: String(page),
            include_adult: "false",
            sort_by: sort || "popularity.desc",
            with_genres: genre || "",
            "vote_count.gte": "50",
          });

    if (!res.ok) return Response.json({ error: "TMDB unavailable" }, { status: 502 });
    const data = (await res.json()) as { results?: Record<string, unknown>[]; total_pages?: number };
    return Response.json(
      {
        kind,
        page,
        totalPages: Math.min(Number(data.total_pages ?? 1), 500),
        items: (data.results ?? []).map((r) => mapItem(r, kind)).filter((i) => i.poster),
      },
      // Session-dependent (access-gated), so never cached by shared caches.
      { headers: { "Cache-Control": "private, no-store" } },
    );
  } catch {
    return Response.json({ error: "TMDB request failed" }, { status: 502 });
  }
}

export const Route = createFileRoute("/api/public/tmdb-discover")({
  server: {
    handlers: {
      GET: ({ request }) => handle(request),
      OPTIONS: () => new Response(null, { status: 204 }),
    },
  },
});
