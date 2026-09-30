import { createFileRoute } from '@tanstack/react-router';
import { sealUrl } from '@/lib/xtream-crypto';
import {
  liveStreamUrl,
  playerApi,
  seriesStreamUrl,
  tagRelay,
  timeshiftUrl,
  vodStreamUrl,
  type Source,
  type XtreamKind,
} from '@/lib/xtream';
import { applyOverrides, loadOverrides } from '@/lib/overrides.server';

/**
 * Metadata proxy for the Live TV section.
 *
 * All player_api.php traffic happens here, server-side, so provider
 * credentials never appear in browser devtools. Playable URLs are returned as
 * opaque encrypted tokens that only /api/public/xtream-play can resolve.
 */

type Category = { category_id: string; category_name: string };

type LiveStream = {
  stream_id: number;
  name: string;
  stream_icon?: string;
  epg_channel_id?: string | null;
  num?: number;
  tv_archive?: number | string;
  tv_archive_duration?: number | string;
  category_id?: string;
};

type VodStream = {
  stream_id: number;
  name: string;
  stream_icon?: string;
  rating?: string | number;
  container_extension?: string;
  year?: string | number;
  added?: string;
  genre?: string;
  category_id?: string;
};

type SeriesItem = {
  series_id: number;
  name: string;
  cover?: string;
  rating?: string | number;
  plot?: string;
  releaseDate?: string;
  last_modified?: string;
  genre?: string;
  category_id?: string;
  season?: number | string;
  seasons?: unknown[];
};

type SeriesEpisode = {
  id: string | number;
  episode_num: number | string;
  title: string;
  container_extension?: string;
  info?: { movie_image?: string; plot?: string; duration?: string; rating?: number | string };
};

type SeriesInfo = {
  info?: { name?: string; cover?: string; plot?: string; genre?: string; rating?: string | number };
  seasons?: Array<{ season_number: number | string; name?: string; cover?: string }>;
  episodes?: Record<string, SeriesEpisode[]>;
};

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' },
  });

async function loadSources(): Promise<Source[]> {
  const { supabaseAdmin } = await import('@/integrations/supabase/client.server');
  const { data, error } = await supabaseAdmin
    .from('sources')
    .select('id, slug, name, base_url, username, password, is_public, relay_url, relay_token')
    .eq('type', 'xtream')
    .eq('is_active', true)
    .order('sort_order', { ascending: true });
  if (error) throw new Error(error.message);
  return (data ?? []).map((s) => ({
    id: s.id,
    slug: s.slug,
    name: s.name,
    base_url: s.base_url ?? '',
    username: s.username ?? '',
    password: s.password ?? '',
    is_public: s.is_public,
    relay_url: s.relay_url,
    relay_token: s.relay_token,
  })) as Source[];
}

/**
 * Live TV providers the caller may use. `'all'` (admins, or a code issued for
 * all providers) keeps the public/private rule; a list means the caller was
 * activated for those providers only and sees nothing else.
 */
function visible(sources: Source[], allowed: string[] | 'all'): Source[] {
  if (allowed === 'all') return sources;
  return sources.filter((s) => allowed.includes(s.id));
}

async function loadSource(slugOrId: string, allowed: string[] | 'all'): Promise<Source | null> {
  const sources = visible(await loadSources(), allowed);
  return sources.find((s) => s.slug === slugOrId || s.id === slugOrId) ?? sources[0] ?? null;
}


/** Curated Live TV channels for a provider; empty when none were imported. */
async function curatedChannels(sourceId: string) {
  const { listLiveChannels } = await import('@/lib/live-channels.server');
  try {
    return await listLiveChannels(sourceId);
  } catch (err) {
    // A curated-list read must never take Live TV down; fall back to the provider.
    console.error('[xtream] curated list unavailable', err);
    return [];
  }
}

/**
 * Providers whose streams the native apps may open straight from the viewer's device.
 * Used when a provider refuses the relay's IP (e.g. MYSTREEM after 2026-10-01): the apps
 * play this link from the viewer's own connection (the website opens it in the browser's own
 * player in a new tab, since an https page cannot load http provider streams). Returned only
 * when the caller asks (`device=1`) and only for these hosts.
 */
const DEVICE_DIRECT_HOSTS = ['myrestreamer.com'];

function deviceDirectAllowed(source: Source): boolean {
  try {
    const host = new URL(source.base_url).hostname.toLowerCase();
    return DEVICE_DIRECT_HOSTS.some((h) => host === h || host.endsWith(`.${h}`));
  } catch {
    return false;
  }
}

const num = (v: unknown, fallback = 0) => {
  const n = Number(v);
  return Number.isFinite(n) ? n : fallback;
};

export const Route = createFileRoute('/api/public/xtream')({
  server: {
    handlers: {
      GET: async ({ request }) => {
        const url = new URL(request.url);
        const action = url.searchParams.get('action') ?? 'providers';

        try {
          // Live TV is gated: locked viewers get no providers, categories or
          // channel data at all — not just a hidden UI.
          const { canOpen, lockedResponse, resolveAccess } = await import('@/lib/access.server');
          const access = await resolveAccess(request);
          if (!canOpen(access, 'live')) return lockedResponse('live');
          const granted = access.liveSources;

          if (action === 'providers') {
            const sources = visible(await loadSources(), granted);
            // Only non-sensitive fields leave the server.
            return json({ providers: sources.map((s) => ({ id: s.slug, name: s.name })) });
          }

          if (action === 'featured') {
            const { listFeatured } = await import('@/lib/featured.server');
            const rows = await listFeatured();
            return json({ featured: rows.map((r) => ({ pattern: r.pattern, order: r.sort_order })) });
          }

          const source = await loadSource(url.searchParams.get('source') ?? '', granted);
          if (!source) return json({ error: 'No provider configured' }, 404);

          // Account summary for the app's "Server & speed test" card: real counts of
          // live channels, movies and series plus the provider account status.
          // The panel address is only shown to admins; credentials never leave.
          if (action === 'info') {
            const t0 = Date.now();
            const safe = <T,>(p: Promise<T>) => p.catch(() => null);
            const [acct, liveCats, vodCats, seriesCats, live, vod, series, curated] = await Promise.all([
              safe(playerApi<Record<string, unknown>>(source, {})),
              safe(playerApi<unknown[]>(source, { action: 'get_live_categories' })),
              safe(playerApi<unknown[]>(source, { action: 'get_vod_categories' })),
              safe(playerApi<unknown[]>(source, { action: 'get_series_categories' })),
              safe(playerApi<unknown[]>(source, { action: 'get_live_streams' })),
              safe(playerApi<unknown[]>(source, { action: 'get_vod_streams' })),
              safe(playerApi<unknown[]>(source, { action: 'get_series' })),
              curatedChannels(source.id),
            ]);
            const count = (v: unknown) => (Array.isArray(v) ? v.length : null);
            const user = (acct?.['user_info'] ?? {}) as Record<string, unknown>;
            const server = (acct?.['server_info'] ?? {}) as Record<string, unknown>;
            const exp = num(user['exp_date'], 0);
            let host: string | null = null;
            if (access.admin) {
              try {
                host = new URL(source.base_url).host;
              } catch {
                host = null;
              }
            }
            return json({
              provider: source.name,
              server: host,
              reachable: acct != null,
              status: typeof user['status'] === 'string' ? user['status'] : '',
              expires: exp > 0 ? new Date(exp * 1000).toISOString() : null,
              trial: String(user['is_trial'] ?? '') === '1',
              maxConnections: num(user['max_connections'], 0),
              activeConnections: num(user['active_cons'], 0),
              timezone: typeof server['timezone'] === 'string' ? server['timezone'] : '',
              live: curated.length > 0 ? curated.length : count(live),
              liveCategories: curated.length > 0 ? new Set(curated.map((c) => c.group)).size : count(liveCats),
              vod: count(vod),
              vodCategories: count(vodCats),
              series: count(series),
              seriesCategories: count(seriesCats),
              ms: Date.now() - t0,
            });
          }

          if (action === 'categories') {
            const kind = (url.searchParams.get('type') ?? 'live') as XtreamKind;

            // Curated live lists carry their own groups instead of provider
            // category ids, so the filter bar must list those.
            if (kind === 'live') {
              const curated = await curatedChannels(source.id);
              if (curated.length > 0) {
                const groups = [...new Set(curated.map((c) => c.group))].sort((a, b) =>
                  a.localeCompare(b),
                );
                return json({
                  categories: applyOverrides(
                    groups.map((g) => ({ id: g, name: g })),
                    await loadOverrides(source.id, 'category'),
                  ),
                });
              }
            }

            const map: Record<XtreamKind, string> = {
              live: 'get_live_categories',
              vod: 'get_vod_categories',
              series: 'get_series_categories',
            };
            const cats = await playerApi<Category[]>(source, { action: map[kind] });
            const categories = (Array.isArray(cats) ? cats : []).map((c) => ({
              id: String(c.category_id),
              name: c.category_name,
            }));
            return json({
              categories: applyOverrides(categories, await loadOverrides(source.id, 'category')),
            });
          }

          if (action === 'live') {
            const categoryId = url.searchParams.get('category_id') ?? '';

            // A curated list (rebuilt from stored credentials, or imported from a
            // provider submission) replaces the provider's own live list.
            const curated = await curatedChannels(source.id);
            if (curated.length > 0) {
              const items = curated
                .filter((c) => !categoryId || c.group === categoryId)
                .map((c) => ({
                  id: c.key,
                  num: c.num,
                  name: c.name,
                  logo: c.logo,
                  archive: false,
                  archiveDays: 0,
                  categoryId: c.group,
                }));
              const shown = applyOverrides(items, await loadOverrides(source.id, 'live'), 'logo');
              return json({ items: shown, hasArchive: false, curated: true });
            }

            const streams = await playerApi<LiveStream[]>(source, {
              action: 'get_live_streams',
              ...(categoryId ? { category_id: categoryId } : {}),
            });
            const list = Array.isArray(streams) ? streams : [];
            // Playback tokens are minted on demand (action=play) so a 5000-channel
            // list stays fast.
            const items = list.map((s, i) => ({
              id: String(s.stream_id),
              num: num(s.num, i + 1),
              name: s.name,
              logo: s.stream_icon || '',
              archive: num(s.tv_archive) === 1,
              archiveDays: num(s.tv_archive_duration),
              categoryId: s.category_id ? String(s.category_id) : '',
            }));
            const visibleItems = applyOverrides(
              items,
              await loadOverrides(source.id, 'live'),
              'logo',
            );
            return json({
              items: visibleItems,
              hasArchive: visibleItems.some((i) => i.archive),
            });
          }

          if (action === 'vod') {
            const categoryId = url.searchParams.get('category_id') ?? '';
            const streams = await playerApi<VodStream[]>(source, {
              action: 'get_vod_streams',
              ...(categoryId ? { category_id: categoryId } : {}),
            });
            const list = Array.isArray(streams) ? streams : [];
            const items = list.map((s) => ({
              id: String(s.stream_id),
              name: s.name,
              poster: s.stream_icon || '',
              rating: s.rating ? String(s.rating) : '',
              year: s.year ? String(s.year) : '',
              genre: s.genre ? String(s.genre) : '',
              added: s.added ? String(s.added) : '',
              categoryId: s.category_id ? String(s.category_id) : '',
              ext: s.container_extension || 'mp4',
            }));
            return json({
              items: applyOverrides(items, await loadOverrides(source.id, 'vod'), 'poster'),
            });
          }

          if (action === 'series') {
            const categoryId = url.searchParams.get('category_id') ?? '';
            const list = await playerApi<SeriesItem[]>(source, {
              action: 'get_series',
              ...(categoryId ? { category_id: categoryId } : {}),
            });
            const items = (Array.isArray(list) ? list : []).map((s) => ({
              id: String(s.series_id),
              name: s.name,
              poster: s.cover || '',
              rating: s.rating ? String(s.rating) : '',
              year: (s.releaseDate || '').slice(0, 4),
              genre: s.genre ? String(s.genre) : '',
              categoryId: s.category_id ? String(s.category_id) : '',
              // Only surfaced when the provider actually reports it.
              seasonCount: Array.isArray(s.seasons)
                ? s.seasons.length
                : s.season
                  ? num(s.season)
                  : 0,
              lastModified: s.last_modified ? String(s.last_modified) : '',
            }));
            return json({
              items: applyOverrides(items, await loadOverrides(source.id, 'series'), 'poster'),
            });
          }

          if (action === 'play') {
            const kind = url.searchParams.get('type') ?? 'live';
            const device = url.searchParams.get('device') === '1' && deviceDirectAllowed(source);
            const id = url.searchParams.get('id') ?? '';
            const ext = (url.searchParams.get('ext') || '').replace(/[^a-z0-9]/gi, '');
            // Curated channel keys are not numeric, so live ids allow the wider set.
            const valid = kind === 'live' ? /^[A-Za-z0-9_.-]{1,80}$/ : /^\d+$/;
            if (!valid.test(id)) return json({ error: 'id is required' }, 400);
            if (kind === 'live') {
              const channel = await (async () => {
                const { findLiveChannel } = await import('@/lib/live-channels.server');
                try {
                  return await findLiveChannel(source.id, id);
                } catch {
                  return null;
                }
              })();
              if (channel) {
                // Imported/curated channels carry their own absolute stream URL.
                return json({
                  play: await sealUrl(tagRelay(channel.url, source)),
                  ...(device && /^https?:\/\//i.test(channel.url) ? { direct: channel.url } : {}),
                });
              }
              if (!/^\d+$/.test(id)) return json({ error: 'Unknown channel' }, 404);
              // Progressive MPEG-TS first: several providers hand out HLS
              // segment URLs whose token is bound to the IP that fetched the
              // playlist, so every segment fetched through the relay dies with
              // "411 invalid data" and the channel never starts. The `.ts`
              // endpoint has no such token and streams fine. `fallback` keeps
              // HLS available for providers that only publish playlists.
              return json({
                play: await sealUrl(tagRelay(liveStreamUrl(source, id, 'ts'), source)),
                fallback: await sealUrl(tagRelay(liveStreamUrl(source, id, 'm3u8'), source)),
                ...(device
                  ? { direct: liveStreamUrl(source, id, 'ts'), directHls: liveStreamUrl(source, id, 'm3u8') }
                  : {}),
              });
            }
            if (kind === 'vod')
              return json({
                play: await sealUrl(tagRelay(vodStreamUrl(source, id, ext || 'mp4'), source)),
                ...(device ? { direct: vodStreamUrl(source, id, ext || 'mp4') } : {}),
              });
            if (kind === 'series')
              return json({
                play: await sealUrl(tagRelay(seriesStreamUrl(source, id, ext || 'mp4'), source)),
                ...(device ? { direct: seriesStreamUrl(source, id, ext || 'mp4') } : {}),
              });
            return json({ error: 'Unknown play type' }, 400);
          }


          if (action === 'series_info') {
            const seriesId = url.searchParams.get('series_id') ?? '';
            if (!seriesId) return json({ error: 'series_id is required' }, 400);
            const device = url.searchParams.get('device') === '1' && deviceDirectAllowed(source);
            const info = await playerApi<SeriesInfo>(source, {
              action: 'get_series_info',
              series_id: seriesId,
            });
            const seasonKeys = Object.keys(info.episodes ?? {}).sort(
              (a, b) => Number(a) - Number(b),
            );
            const seasons = await Promise.all(
              seasonKeys.map(async (key) => ({
                season: Number(key),
                episodes: await Promise.all(
                  (info.episodes?.[key] ?? []).map(async (ep) => ({
                    id: String(ep.id),
                    episode: num(ep.episode_num),
                    title: ep.title,
                    image: ep.info?.movie_image || '',
                    plot: ep.info?.plot || '',
                    duration: ep.info?.duration || '',
                    play: await sealUrl(
                      tagRelay(
                        seriesStreamUrl(source, ep.id, ep.container_extension || 'mp4'),
                        source,
                      ),
                    ),
                    ...(device
                      ? { direct: seriesStreamUrl(source, ep.id, ep.container_extension || 'mp4') }
                      : {}),
                  })),
                ),
              })),
            );
            return json({
              title: info.info?.name ?? '',
              cover: info.info?.cover ?? '',
              plot: info.info?.plot ?? '',
              genre: info.info?.genre ?? '',
              rating: info.info?.rating ? String(info.info.rating) : '',
              seasons,
            });
          }

          if (action === 'timeshift') {
            const streamId = url.searchParams.get('stream_id') ?? '';
            const start = url.searchParams.get('start') ?? '';
            const duration = num(url.searchParams.get('duration'), 60);
            if (!streamId || !/^\d{4}-\d{2}-\d{2}:\d{2}-\d{2}$/.test(start)) {
              return json({ error: 'stream_id and start (yyyy-MM-dd:HH-mm) are required' }, 400);
            }
            return json({
              play: await sealUrl(
                tagRelay(timeshiftUrl(source, streamId, duration, start), source),
              ),
            });
          }

          return json({ error: `Unknown action "${action}"` }, 400);
        } catch (err) {
          console.error('[xtream]', action, err);
          return json({ error: 'Provider request failed' }, 502);
        }
      },
    },
  },
});
