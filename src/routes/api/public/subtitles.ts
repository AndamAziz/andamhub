import { createFileRoute } from '@tanstack/react-router';

/**
 * Subtitles for the apps' player.
 *
 *  GET ?action=list&tmdb=&type=movie|episode&season=&episode=
 *      → { subtitles: [{ lang, label, url }], kurdishAuto: { url, partUrl } | null }
 *      (signed-in viewers with Live TV / films access)
 *  GET ?action=file&t=TOKEN          → the subtitle as text/vtt
 *  GET ?action=kurdish&t=TOKEN       → Kurdish (Sorani) text/vtt when ready,
 *                                      otherwise 202 { preparing, parts, missing }
 *  GET ?action=kurdish-part&t=TOKEN&n=N → translates part N (signed-in viewers only)
 *
 * TOKEN is an opaque, expiring token for one OpenSubtitles file id, so subtitle links can be
 * handed to the player without exposing anything or opening the API to everyone.
 */

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' },
  });

const vtt = (text: string) =>
  new Response(text, {
    status: 200,
    headers: { 'Content-Type': 'text/vtt; charset=utf-8', 'Cache-Control': 'private, max-age=86400' },
  });

const TOKEN_TTL = 30 * 24 * 60 * 60;

async function fileOf(token: string | null): Promise<number> {
  if (!token) return 0;
  const { openUrl } = await import('@/lib/xtream-crypto');
  const v = await openUrl(token);
  const m = v ? /^os:(\d+)$/.exec(v) : null;
  return m ? Number(m[1]) : 0;
}

async function viewer(request: Request): Promise<boolean> {
  const { resolveAccess, canOpen } = await import('@/lib/access.server');
  const access = await resolveAccess(request);
  return access.signedIn && (canOpen(access, 'live') || canOpen(access, 'movies') || canOpen(access, 'series'));
}

export const Route = createFileRoute('/api/public/subtitles')({
  server: {
    handlers: {
      GET: async ({ request }) => {
        const url = new URL(request.url);
        const action = url.searchParams.get('action') ?? 'list';
        try {
          const subs = await import('@/lib/subtitles.server');

          if (action === 'list') {
            if (!(await viewer(request))) return json({ error: 'Sign in first.' }, 403);
            const tmdb = Number(url.searchParams.get('tmdb') ?? 0);
            const type = url.searchParams.get('type') === 'episode' ? 'episode' : 'movie';
            const season = Number(url.searchParams.get('season') ?? 0);
            const episode = Number(url.searchParams.get('episode') ?? 0);
            if (!subs.subtitlesConfigured()) return json({ subtitles: [], kurdishAuto: null, configured: false });
            const files = await subs.searchSubtitles({ tmdb, type, season, episode });
            const { sealUrl } = await import('@/lib/xtream-crypto');
            const base = '/api/public/subtitles';
            const out = await Promise.all(
              files.map(async (f) => {
                const t = encodeURIComponent(await sealUrl(`os:${f.fileId}`, TOKEN_TTL));
                return { lang: f.lang, label: f.label, url: `${base}?action=file&t=${t}`, token: t };
              }),
            );
            const english = out.find((s) => s.lang === 'en');
            const hasKurdish = out.some((s) => s.lang === 'ku');
            const kurdishAuto =
              english && !hasKurdish && subs.kurdishAiConfigured()
                ? {
                    url: `${base}?action=kurdish&t=${english.token}`,
                    partUrl: `${base}?action=kurdish-part&t=${english.token}`,
                  }
                : null;
            return json({
              subtitles: out.map(({ token: _t, ...s }) => s),
              kurdishAuto,
              configured: true,
            });
          }

          if (action === 'file') {
            const fileId = await fileOf(url.searchParams.get('t'));
            if (!fileId) return json({ error: 'Invalid subtitle link' }, 404);
            return vtt(await subs.subtitleVtt(fileId));
          }

          if (action === 'kurdish') {
            const fileId = await fileOf(url.searchParams.get('t'));
            if (!fileId) return json({ error: 'Invalid subtitle link' }, 404);
            const { vtt: text, state } = await subs.kurdishState(fileId);
            if (text) return vtt(text);
            return json({ preparing: true, ...state }, 202);
          }

          if (action === 'kurdish-part') {
            if (!(await viewer(request))) return json({ error: 'Sign in first.' }, 403);
            const fileId = await fileOf(url.searchParams.get('t'));
            const n = Number(url.searchParams.get('n') ?? -1);
            if (!fileId || !(n >= 0)) return json({ error: 'Invalid request' }, 400);
            await subs.translateKurdishPart(fileId, n);
            return json({ ok: true, part: n });
          }

          return json({ error: `Unknown action: ${action}` }, 400);
        } catch (err) {
          console.error('[subtitles]', action, err);
          return json({ error: err instanceof Error ? err.message : 'Subtitle request failed' }, 502);
        }
      },
    },
  },
});
