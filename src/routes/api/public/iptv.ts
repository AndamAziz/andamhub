import { createFileRoute } from '@tanstack/react-router';
import { sealUrl } from '@/lib/xtream-crypto';
import { tagStreamHeaders } from '@/lib/xtream';
import {
  getPlaylistChannels,
  loadPlaylistSource,
  loadPlaylistSources,
  type M3uChannel,
  type PlaylistSource,
} from '@/lib/m3u.server';
import { applyOverrides, loadOverrides } from '@/lib/overrides.server';

/**
 * Public metadata API for the IPTV section (M3U playlist sources).
 *
 * Independent of /api/public/xtream: different source type, different parsing,
 * different cache. Raw playlist stream URLs are never returned — the client
 * gets an opaque token that /api/public/xtream-play resolves and relays.
 */

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' },
  });

async function grantedSourceIds(request: Request): Promise<Set<string> | null> {
  const token = (request.headers.get('Authorization') ?? '').replace(/^Bearer\s+/i, '');
  if (!token) return null;
  const { supabaseAdmin } = await import('@/integrations/supabase/client.server');
  const { data: userData } = await supabaseAdmin.auth.getUser(token);
  const userId = userData?.user?.id;
  if (!userId) return null;
  const { data } = await supabaseAdmin
    .from('user_source_access')
    .select('source_id')
    .eq('user_id', userId);
  return new Set((data ?? []).map((r) => String(r.source_id)));
}

function visible(sources: PlaylistSource[], granted: Set<string> | null): PlaylistSource[] {
  return sources.filter((s) => s.is_public !== false || granted?.has(s.id));
}

async function pickSource(
  slugOrId: string,
  granted: Set<string> | null,
): Promise<PlaylistSource | null> {
  const sources = visible(await loadPlaylistSources(), granted);
  return sources.find((s) => s.slug === slugOrId || s.id === slugOrId) ?? sources[0] ?? null;
}

/** Admin overrides hide/re-sort playlist channels and fix broken logos. */
async function withOverrides(
  source: PlaylistSource,
  channels: M3uChannel[],
): Promise<M3uChannel[]> {
  const overrides = await loadOverrides(source.id, 'live');
  return applyOverrides(channels, overrides, 'logo');
}

export const Route = createFileRoute('/api/public/iptv')({
  server: {
    handlers: {
      GET: async ({ request }) => {
        const url = new URL(request.url);
        const action = url.searchParams.get('action') ?? 'sources';

        try {
          const granted = await grantedSourceIds(request);

          if (action === 'sources') {
            const sources = visible(await loadPlaylistSources(), granted);
            return json({ sources: sources.map((s) => ({ id: s.slug, name: s.name })) });
          }

          const source = await pickSource(url.searchParams.get('source') ?? '', granted);
          if (!source) return json({ error: 'No IPTV playlist configured' }, 404);

          if (action === 'channels') {
            const { channels, fetchedAt, stale } = await getPlaylistChannels(source);
            const list = (await withOverrides(source, channels)) as M3uChannel[];
            const group = url.searchParams.get('group') ?? '';
            const filtered = group ? list.filter((c) => c.group === group) : list;
            const groups = new Map<string, number>();
            for (const c of list) groups.set(c.group, (groups.get(c.group) ?? 0) + 1);

            return json({
              source: source.slug,
              fetchedAt,
              stale,
              groups: [...groups.entries()].map(([name, count]) => ({ name, count })),
              total: list.length,
              channels: filtered.map((c, i) => ({
                id: c.id,
                num: c.num || i + 1,
                name: c.name,
                logo: c.logo,
                group: c.group,
              })),
            });
          }

          if (action === 'play') {
            const id = url.searchParams.get('id') ?? '';
            const { channels } = await getPlaylistChannels(source);
            const visibleList = (await withOverrides(source, channels)) as M3uChannel[];
            const channel = visibleList.find((c) => c.id === id);
            if (!channel) return json({ error: 'Channel not found' }, 404);
            return json({
              name: channel.name,
              logo: channel.logo,
              token: await sealUrl(tagStreamHeaders(channel.url, channel.headers)),
              // The native app plays these straight from the source (faster start, and the
              // Referer/Origin/User-Agent a channel needs are sent by the phone itself).
              // Public playlists only for plain links — a private playlist may carry account
              // credentials in its URLs; header-protected channels are always included.
              ...(/^https?:\/\//i.test(channel.url) &&
              (source.is_public !== false ||
                (channel.headers && Object.keys(channel.headers).length))
                ? { direct: { url: channel.url, headers: channel.headers ?? {} } }
                : {}),
            });
          }

          return json({ error: `Unknown action: ${action}` }, 400);
        } catch (err) {
          const message = err instanceof Error ? err.message : 'IPTV request failed';
          return json({ error: message }, 502);
        }
      },
    },
  },
});
