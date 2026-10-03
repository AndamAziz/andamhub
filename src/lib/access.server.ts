/**
 * Section entitlements.
 *
 * New viewers start with the IPTV section only. Live TV, Movies and Shows stay
 * locked until an admin-issued activation code is redeemed. Every check here
 * runs server-side against the database — a client flag unlocks nothing.
 */
import { supabaseAdmin } from '@/integrations/supabase/client.server';

export type Section = 'live' | 'movies' | 'series';
export const SECTIONS: Section[] = ['live', 'movies', 'series'];
/** Always available, no code required. */
export const OPEN_SECTIONS = ['iptv'] as const;

export type Access = {
  signedIn: boolean;
  userId: string | null;
  admin: boolean;
  /** Sections the caller may open. */
  sections: Section[];
  /** Provider ids allowed for `live`; `'all'` means every provider. */
  liveSources: string[] | 'all';
  /** Signed-in account's e-mail (used to register the account on first sight). */
  email?: string | null;
};

const LOCKED: Access = {
  signedIn: false,
  userId: null,
  admin: false,
  sections: [],
  liveSources: [],
};

function bearer(request: Request): string {
  return (request.headers.get('Authorization') ?? '').replace(/^Bearer\s+/i, '').trim();
}

/**
 * Entitlements granted by an activation code only stay alive while that code is
 * still valid. Once the code expires, is revoked or is deleted, the grant stops
 * counting and the viewer falls back to IPTV-only until a new code is redeemed.
 * Manual admin grants (no code_id) are never time-limited.
 */
async function liveGrants(
  userId: string,
): Promise<Array<{ section: Section; source_id: string | null }>> {
  const { data: rows } = await supabaseAdmin
    .from('user_entitlements')
    .select('section, source_id, code_id')
    .eq('user_id', userId);

  const list = (rows ?? []).filter((r) => SECTIONS.includes(r.section as Section));
  const codeIds = [...new Set(list.map((r) => r.code_id).filter((id): id is string => !!id))];
  if (!codeIds.length) {
    return list.map((r) => ({ section: r.section as Section, source_id: r.source_id }));
  }

  const { data: codes } = await supabaseAdmin
    .from('activation_codes')
    .select('id, expires_at, revoked')
    .in('id', codeIds);

  const valid = new Set(
    (codes ?? [])
      .filter(
        (c) =>
          !c.revoked && !(c.expires_at && new Date(c.expires_at).getTime() < Date.now()),
      )
      .map((c) => c.id),
  );

  return list
    .filter((r) => !r.code_id || valid.has(r.code_id))
    .map((r) => ({ section: r.section as Section, source_id: r.source_id }));
}

/** Sections a viewer may open right now, honouring code expiry/revocation. */
export async function effectiveSections(userId: string): Promise<Section[]> {
  const rows = await liveGrants(userId);
  return [...new Set(rows.map((r) => r.section))];
}

/**
 * Platform owners. These accounts always hold full access to every section and
 * every provider — no activation code, no per-user provider assignment, and no
 * dependency on a role row existing in the database.
 */
const OWNER_EMAILS = ['andam@outlook.com'];

export function isOwnerEmail(email: string | null | undefined): boolean {
  return !!email && OWNER_EMAILS.includes(email.trim().toLowerCase());
}

/** Resolves what the caller of a public API route is entitled to see. */
export async function resolveAccess(request: Request): Promise<Access> {
  const token = bearer(request);
  if (!token) return { ...LOCKED };

  const { data: userData } = await supabaseAdmin.auth.getUser(token);
  const userId = userData?.user?.id ?? null;
  if (!userId) return { ...LOCKED };
  const email = userData?.user?.email ?? null;

  if (isOwnerEmail(userData?.user?.email)) {
    return { signedIn: true, userId, email, admin: true, sections: [...SECTIONS], liveSources: 'all' };
  }

  const { data: roles } = await supabaseAdmin
    .from('user_roles')
    .select('role')
    .eq('user_id', userId);

  const admin = (roles ?? []).some((r) => r.role === 'admin');
  if (admin) {
    return { signedIn: true, userId, email, admin: true, sections: [...SECTIONS], liveSources: 'all' };
  }

  const rows = await liveGrants(userId);

  const sections = new Set<Section>();
  for (const row of rows) sections.add(row.section);

  /**
   * Which Live TV providers this viewer sees is set explicitly by the admin in
   * user_source_access — an activation code only unlocks the *section*. A
   * viewer therefore needs both: the `live` section unlocked AND at least one
   * provider assigned. No assignment means no provider, never "all".
   */
  const { data: assigned } = await supabaseAdmin
    .from('user_source_access')
    .select('source_id')
    .eq('user_id', userId);

  return {
    signedIn: true,
    userId,
    email,
    admin: false,
    sections: [...sections],
    liveSources: [...new Set((assigned ?? []).map((r) => String(r.source_id)))],
  };

}


export function canOpen(access: Access, section: Section): boolean {
  return access.admin || access.sections.includes(section);
}

export function canUseLiveSource(access: Access, sourceId: string): boolean {
  if (!canOpen(access, 'live')) return false;
  return access.liveSources === 'all' || access.liveSources.includes(sourceId);
}

export const lockedResponse = (section: Section) =>
  new Response(
    JSON.stringify({
      error: 'This section requires provider access. Please contact the admin to activate it.',
      locked: true,
      section,
    }),
    { status: 403, headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' } },
  );

export type RedeemResult = {
  ok: boolean;
  message: string;
  sections?: Section[];
  provider?: string;
};

/** Validates a code, records the redemption and writes the entitlement rows. */
export async function redeemCode(
  userId: string,
  email: string | null,
  rawCode: string,
): Promise<RedeemResult> {
  const code = rawCode.trim().toUpperCase();
  if (!code) return { ok: false, message: 'Enter an activation code.' };

  const { data: row } = await supabaseAdmin
    .from('activation_codes')
    .select('id, code, source_id, sections, expires_at, max_uses, uses, revoked')
    .eq('code', code)
    .maybeSingle();

  if (!row) return { ok: false, message: 'That code is not valid.' };
  if (row.revoked) return { ok: false, message: 'That code has been revoked.' };
  if (row.expires_at && new Date(row.expires_at).getTime() < Date.now()) {
    return { ok: false, message: 'That code has expired.' };
  }
  if (row.uses >= row.max_uses) return { ok: false, message: 'That code has already been used.' };

  const { data: mine } = await supabaseAdmin
    .from('activation_code_redemptions')
    .select('id')
    .eq('code_id', row.id)
    .eq('user_id', userId)
    .maybeSingle();
  if (mine) return { ok: false, message: 'You have already redeemed this code.' };

  const sections = (row.sections ?? []).filter((s): s is Section =>
    SECTIONS.includes(s as Section),
  );
  if (!sections.length) return { ok: false, message: 'That code unlocks nothing.' };

  // Re-point existing rows at the new code so a previously expired grant becomes
  // live again instead of staying attached to the dead code.
  await supabaseAdmin.from('user_entitlements').upsert(
    sections.map((section) => ({
      user_id: userId,
      section,
      // Movies/Shows come from TMDB, so they are never provider-scoped.
      source_id: section === 'live' ? row.source_id : null,
      code_id: row.id,
    })),
    { onConflict: 'user_id,section,source_id' },
  );

  // Provider grant so private Live TV sources become visible too.
  if (row.source_id && sections.includes('live')) {
    await supabaseAdmin
      .from('user_source_access')
      .upsert({ user_id: userId, source_id: row.source_id }, { ignoreDuplicates: true });
  }

  await supabaseAdmin
    .from('activation_code_redemptions')
    .insert({ code_id: row.id, user_id: userId, email });
  await supabaseAdmin
    .from('activation_codes')
    .update({ uses: row.uses + 1 })
    .eq('id', row.id);

  let provider = 'All providers';
  if (row.source_id) {
    const { data: src } = await supabaseAdmin
      .from('sources')
      .select('name')
      .eq('id', row.source_id)
      .maybeSingle();
    provider = src?.name ?? 'Provider';
  }

  return { ok: true, message: 'Code accepted — access unlocked.', sections, provider };
}
