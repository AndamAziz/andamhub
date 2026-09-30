import { createFileRoute } from '@tanstack/react-router';

/**
 * Section access for the embedded homepage.
 *
 * GET  → what the bearer may open (IPTV is always open).
 * POST → redeem an activation code (requires a valid bearer token).
 *
 * This route only reports and grants access; every content API enforces it
 * independently, so a tampered client still gets nothing.
 */

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' },
  });

export const Route = createFileRoute('/api/public/access')({
  server: {
    handlers: {
      GET: async ({ request }) => {
        try {
          const { resolveAccess } = await import('@/lib/access.server');
          const access = await resolveAccess(request);
          return json({
            signedIn: access.signedIn,
            admin: access.admin,
            sections: access.sections,
            open: ['iptv'],
          });
        } catch (err) {
          // Backend unavailable: fall back to IPTV-only so the homepage still opens.
          console.error('[access] lookup failed', err);
          return json({ signedIn: false, admin: false, sections: [], open: ['iptv'], degraded: true });
        }
      },

      POST: async ({ request }) => {
        const { resolveAccess, redeemCode } = await import('@/lib/access.server');
        const access = await resolveAccess(request);
        if (!access.signedIn || !access.userId) {
          return json({ ok: false, message: 'Sign in first, then redeem your code.' }, 401);
        }

        let code = '';
        try {
          const body = (await request.json()) as { code?: unknown };
          code = typeof body.code === 'string' ? body.code : '';
        } catch {
          return json({ ok: false, message: 'Enter an activation code.' }, 400);
        }
        if (!/^[A-Za-z0-9-]{4,40}$/.test(code.trim())) {
          return json({ ok: false, message: 'That code is not valid.' }, 400);
        }

        const { supabaseAdmin } = await import('@/integrations/supabase/client.server');
        const { data: userData } = await supabaseAdmin.auth.getUser(
          (request.headers.get('Authorization') ?? '').replace(/^Bearer\s+/i, ''),
        );
        const result = await redeemCode(
          access.userId,
          userData?.user?.email ?? null,
          code,
        );
        return json(result, result.ok ? 200 : 400);
      },
    },
  },
});
