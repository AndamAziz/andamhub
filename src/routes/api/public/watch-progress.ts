import { createFileRoute } from '@tanstack/react-router';

/**
 * Per-user watch history for the Continue Watching row.
 *
 * The homepage lives in a same-origin iframe of static HTML, so it calls this
 * route with the Supabase access token the host page bridges to it. Every
 * request is scoped to the caller's own rows — the token is verified here and
 * the user id never comes from the request body.
 */

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' },
  });

async function callerId(request: Request): Promise<string | null> {
  const token = (request.headers.get('Authorization') ?? '').replace(/^Bearer\s+/i, '');
  if (!token) return null;
  const { supabaseAdmin } = await import('@/integrations/supabase/client.server');
  const { data } = await supabaseAdmin.auth.getUser(token);
  return data?.user?.id ?? null;
}

const int = (v: unknown, fallback = 0) => {
  const n = Math.round(Number(v));
  return Number.isFinite(n) && n >= 0 ? n : fallback;
};

const str = (v: unknown, max = 300) => (typeof v === 'string' ? v.slice(0, max) : '');

export const Route = createFileRoute('/api/public/watch-progress')({
  server: {
    handlers: {
      GET: async ({ request }) => {
        // A backend outage must never break the homepage: show no history instead.
        const userId = await callerId(request).catch((err) => {
          console.error('[watch-progress] caller lookup failed', err);
          return null;
        });
        if (!userId) return json({ items: [] });
        const { supabaseAdmin } = await import('@/integrations/supabase/client.server');
        const { data, error } = await supabaseAdmin
          .from('watch_progress')
          .select(
            'content_type, content_id, title, poster, season, episode, position_seconds, duration_seconds, updated_at',
          )
          .eq('user_id', userId)
          .order('updated_at', { ascending: false })
          .limit(20);
        if (error) return json({ error: 'Could not load watch history' }, 502);
        return json({
          items: (data ?? []).map((r) => ({
            tmdbId: r.content_id,
            type: r.content_type,
            title: r.title ?? '',
            poster: r.poster ?? '',
            season: r.season ?? 1,
            episode: r.episode ?? 1,
            position: r.position_seconds ?? 0,
            duration: r.duration_seconds ?? 0,
            updated: new Date(r.updated_at as string).getTime(),
          })),
        });
      },

      POST: async ({ request }) => {
        const userId = await callerId(request);
        if (!userId) return json({ ok: false }, 401);
        let body: Record<string, unknown>;
        try {
          body = (await request.json()) as Record<string, unknown>;
        } catch {
          return json({ error: 'Invalid body' }, 400);
        }
        const { supabaseAdmin } = await import('@/integrations/supabase/client.server');

        if (body['clear']) {
          await supabaseAdmin.from('watch_progress').delete().eq('user_id', userId);
          return json({ ok: true });
        }

        const type = body['type'] === 'tv' ? 'tv' : 'movie';
        const contentId = str(body['tmdbId'], 40);
        if (!contentId) return json({ error: 'tmdbId is required' }, 400);

        if (body['remove']) {
          await supabaseAdmin
            .from('watch_progress')
            .delete()
            .eq('user_id', userId)
            .eq('content_type', type)
            .eq('content_id', contentId);
          return json({ ok: true });
        }

        const { error } = await supabaseAdmin.from('watch_progress').upsert(
          {
            user_id: userId,
            content_type: type,
            content_id: contentId,
            title: str(body['title']) || null,
            poster: str(body['poster'], 600) || null,
            season: int(body['season'], 1),
            episode: int(body['episode'], 1),
            position_seconds: int(body['position']),
            duration_seconds: int(body['duration']),
            updated_at: new Date().toISOString(),
          },
          { onConflict: 'user_id,content_type,content_id' },
        );
        if (error) return json({ error: 'Could not save progress' }, 502);
        return json({ ok: true });
      },
    },
  },
});
