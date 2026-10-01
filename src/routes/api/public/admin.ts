import { createFileRoute } from '@tanstack/react-router';

/**
 * CEO admin API for the native apps (Android / TV). Same operations as the web admin panel.
 *
 * Every request needs the signed-in admin's bearer token; the admin role is checked against
 * the database on each call (resolveAccess), so a tampered app gets nothing.
 *
 *   GET  ?action=overview   providers, users, relay health, recent errors / sign-ins
 *   GET  ?action=codes      every activation code (with who redeemed it)
 *   GET  ?action=users      every account with its unlocked sections
 *   POST {action:'code_create', sourceId, sections, maxUses, days, note}
 *   POST {action:'code_renew', id, days, extraUses} | {action:'code_revoke'|'code_delete', id}
 *   POST {action:'user_section', userId, section, grant} | {action:'user_suspend', userId, suspended}
 *   POST {action:'user_role', userId, role}
 */

type Section = 'live' | 'movies' | 'series';

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' },
  });

async function adminOnly(request: Request): Promise<string> {
  const { resolveAccess } = await import('@/lib/access.server');
  const access = await resolveAccess(request);
  if (!access.signedIn || !access.userId) throw Object.assign(new Error('Sign in first.'), { status: 401 });
  if (!access.admin) throw Object.assign(new Error('Admin access only.'), { status: 403 });
  return access.userId;
}

const fail = (err: unknown) => {
  const status = (err as { status?: number })?.status ?? 400;
  return json({ error: err instanceof Error ? err.message : 'Request failed' }, status);
};

const days = (n: unknown): string | null => {
  const d = Number(n);
  return Number.isFinite(d) && d > 0 ? new Date(Date.now() + d * 86_400_000).toISOString() : null;
};

const sectionsOf = (v: unknown): Section[] =>
  (Array.isArray(v) ? v : []).filter((s): s is Section => s === 'live' || s === 'movies' || s === 'series');

export const Route = createFileRoute('/api/public/admin')({
  server: {
    handlers: {
      GET: async ({ request }) => {
        try {
          await adminOnly(request);
          const action = new URL(request.url).searchParams.get('action') ?? 'overview';

          if (action === 'overview') {
            const ops = await import('@/lib/admin-ops.server');
            const o = await ops.adminOverview();
            return json({
              providers: o.providers,
              activeProviders: o.activeProviders,
              totalProviders: o.totalProviders,
              totalUsers: o.totalUsers,
              relay: o.relay,
              recentErrors: o.recentErrors,
              recentLogins: o.recentLogins,
            });
          }

          if (action === 'codes') {
            const { listCodes } = await import('@/lib/codes.server');
            const ops = await import('@/lib/admin-ops.server');
            const [codes, providers] = await Promise.all([listCodes(), ops.listProviders()]);
            return json({
              codes,
              providers: providers.map((p) => ({ id: p.id, name: p.name, active: p.is_active })),
            });
          }

          if (action === 'users') {
            const ops = await import('@/lib/admin-ops.server');
            const { listEntitlements } = await import('@/lib/codes.server');
            const users = await ops.listUsers();
            const ent = await listEntitlements(users.map((u) => u.id));
            return json({ users: users.map((u) => ({ ...u, sections: ent[u.id] ?? [] })) });
          }

          return json({ error: `Unknown action: ${action}` }, 400);
        } catch (err) {
          return fail(err);
        }
      },

      POST: async ({ request }) => {
        try {
          const me = await adminOnly(request);
          const body = (await request.json().catch(() => ({}))) as Record<string, unknown>;
          const action = String(body['action'] ?? '');
          const id = String(body['id'] ?? '');
          const userId = String(body['userId'] ?? '');
          const codes = await import('@/lib/codes.server');
          const ops = await import('@/lib/admin-ops.server');

          switch (action) {
            case 'code_create':
              return json(
                await codes.createCode({
                  createdBy: me,
                  sourceId: body['sourceId'] ? String(body['sourceId']) : null,
                  sections: sectionsOf(body['sections']),
                  maxUses: Number(body['maxUses']) || 1,
                  expiresAt: days(body['days']),
                  note: String(body['note'] ?? ''),
                }),
              );
            case 'code_renew':
              return json(
                await codes.renewCode({
                  id,
                  expiresAt: days(body['days']),
                  extraUses: Number(body['extraUses']) || 0,
                }),
              );
            case 'code_revoke':
              return json(await codes.revokeCode(id));
            case 'code_delete':
              return json(await codes.deleteCode(id));
            case 'user_section': {
              const section = sectionsOf([body['section']])[0];
              if (!section) return json({ error: 'Unknown section' }, 400);
              await codes.setEntitlement(userId, section, Boolean(body['grant']));
              return json({ ok: true });
            }
            case 'user_suspend':
              await ops.setUserSuspended(me, userId, Boolean(body['suspended']));
              return json({ ok: true });
            case 'user_role':
              await ops.setUserRole(me, userId, body['role'] === 'admin' ? 'admin' : 'user');
              return json({ ok: true });
            default:
              return json({ error: `Unknown action: ${action}` }, 400);
          }
        } catch (err) {
          return fail(err);
        }
      },
    },
  },
});
