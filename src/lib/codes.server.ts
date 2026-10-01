/** Server-only activation-code administration (admin role already verified). */
import { supabaseAdmin } from '@/integrations/supabase/client.server';
import { SECTIONS, type Section } from '@/lib/access.server';
import { allRows, chunks } from '@/lib/paged.server';

export type CodeStatus = 'active' | 'used' | 'expired' | 'revoked';

export type AdminCode = {
  id: string;
  code: string;
  sourceId: string | null;
  provider: string;
  sections: Section[];
  note: string;
  expiresAt: string | null;
  maxUses: number;
  uses: number;
  status: CodeStatus;
  createdAt: string;
  redeemedBy: Array<{ email: string; at: string }>;
};

const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';

function randomCode(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(12));
  const chars = [...bytes].map((b) => ALPHABET[b % ALPHABET.length]).join('');
  return `${chars.slice(0, 4)}-${chars.slice(4, 8)}-${chars.slice(8, 12)}`;
}

function statusOf(row: {
  revoked: boolean;
  expires_at: string | null;
  uses: number;
  max_uses: number;
}): CodeStatus {
  if (row.revoked) return 'revoked';
  if (row.expires_at && new Date(row.expires_at).getTime() < Date.now()) return 'expired';
  if (row.uses >= row.max_uses) return 'used';
  return 'active';
}

export async function listCodes(): Promise<AdminCode[]> {
  // Every code and every redemption, page by page (no cap on how many are listed).
  const [codes, { data: sources }, redemptions] = await Promise.all([
    allRows((a, b) =>
      supabaseAdmin
        .from('activation_codes')
        .select('id, code, source_id, sections, note, expires_at, max_uses, uses, revoked, created_at')
        .order('created_at', { ascending: false })
        .order('id', { ascending: true })
        .range(a, b),
    ),
    supabaseAdmin.from('sources').select('id, name'),
    allRows((a, b) =>
      supabaseAdmin
        .from('activation_code_redemptions')
        .select('code_id, email, created_at')
        .order('created_at', { ascending: false })
        .range(a, b),
    ),
  ]);

  const byCode = new Map<string, Array<{ email: string; at: string }>>();
  for (const r of redemptions) {
    const list = byCode.get(r.code_id) ?? [];
    list.push({ email: r.email ?? 'unknown', at: r.created_at });
    byCode.set(r.code_id, list);
  }

  const names = new Map((sources ?? []).map((s) => [s.id, s.name]));

  return codes.map((row) => ({
    id: row.id,
    code: row.code,
    sourceId: row.source_id,
    provider: row.source_id ? (names.get(row.source_id) ?? 'Unknown provider') : 'All providers',
    sections: (row.sections ?? []).filter((s): s is Section => SECTIONS.includes(s as Section)),
    note: row.note ?? '',
    expiresAt: row.expires_at,
    maxUses: row.max_uses,
    uses: row.uses,
    status: statusOf(row),
    createdAt: row.created_at,
    redeemedBy: byCode.get(row.id) ?? [],
  }));
}

export async function createCode(input: {
  createdBy: string;
  sourceId: string | null;
  sections: Section[];
  maxUses: number;
  expiresAt: string | null;
  note: string;
}): Promise<{ code: string }> {
  const sections = input.sections.filter((s) => SECTIONS.includes(s));
  if (!sections.length) throw new Error('Pick at least one section to unlock');

  const code = randomCode();
  const { error } = await supabaseAdmin.from('activation_codes').insert({
    code,
    source_id: input.sourceId,
    sections,
    note: input.note.slice(0, 200) || null,
    max_uses: Math.min(Math.max(1, Math.round(input.maxUses)), 1_000_000),
    expires_at: input.expiresAt,
    created_by: input.createdBy,
  });
  if (error) throw new Error(error.message);
  return { code };
}

/** Unused codes are deleted outright; redeemed ones are revoked to keep history. */
export async function revokeCode(id: string): Promise<{ deleted: boolean }> {
  const { data: row } = await supabaseAdmin
    .from('activation_codes')
    .select('id, uses')
    .eq('id', id)
    .maybeSingle();
  if (!row) return { deleted: false };
  if (row.uses === 0) {
    await supabaseAdmin.from('activation_codes').delete().eq('id', id);
    return { deleted: true };
  }
  await supabaseAdmin.from('activation_codes').update({ revoked: true }).eq('id', id);
  return { deleted: false };
}

/**
 * Hard-deletes a code. Redeemed codes are allowed too: their redemption rows and
 * the entitlements the code granted go with it, so those viewers drop back to
 * IPTV-only and must redeem a new code to unlock the other sections again.
 */
export async function deleteCode(id: string): Promise<{ deleted: true }> {
  const { data: row } = await supabaseAdmin
    .from('activation_codes')
    .select('id')
    .eq('id', id)
    .maybeSingle();
  if (!row) throw new Error('Code not found');
  await supabaseAdmin.from('user_entitlements').delete().eq('code_id', id);
  await supabaseAdmin.from('activation_code_redemptions').delete().eq('code_id', id);
  const { error } = await supabaseAdmin.from('activation_codes').delete().eq('id', id);
  if (error) throw new Error(error.message);
  return { deleted: true };
}


/**
 * Extends an existing code instead of issuing a new string, so a viewer who
 * already has the code keeps using it. Also un-revokes and tops the use count
 * back up when the code had been exhausted.
 */
export async function renewCode(input: {
  id: string;
  expiresAt: string | null;
  extraUses?: number;
}): Promise<{ expiresAt: string | null }> {
  const { data: row } = await supabaseAdmin
    .from('activation_codes')
    .select('id, uses, max_uses')
    .eq('id', input.id)
    .maybeSingle();
  if (!row) throw new Error('Code not found');

  const expiresAt =
    input.expiresAt ?? new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString();
  const extra = Math.min(Math.max(0, Math.round(input.extraUses ?? 0)), 1_000_000);
  const maxUses = Math.max(row.max_uses + extra, row.uses + 1);

  const { error } = await supabaseAdmin
    .from('activation_codes')
    .update({ expires_at: expiresAt, revoked: false, max_uses: maxUses })
    .eq('id', row.id);
  if (error) throw new Error(error.message);
  return { expiresAt };
}

/** Manual override so an admin can unlock or re-lock a viewer without a code. */
export async function setEntitlement(
  userId: string,
  section: Section,
  grant: boolean,
): Promise<void> {
  if (grant) {
    await supabaseAdmin
      .from('user_entitlements')
      .upsert(
        { user_id: userId, section, source_id: null },
        { onConflict: 'user_id,section,source_id', ignoreDuplicates: true },
      );
    return;
  }
  await supabaseAdmin.from('user_entitlements').delete().eq('user_id', userId).eq('section', section);
}

export async function listEntitlements(
  userIds: string[],
): Promise<Record<string, Section[]>> {
  if (!userIds.length) return {};
  const data: Array<{ user_id: string; section: string }> = [];
  for (const ids of chunks(userIds)) {
    data.push(
      ...(await allRows((a, b) =>
        supabaseAdmin
          .from('user_entitlements')
          .select('user_id, section')
          .in('user_id', ids)
          .order('user_id', { ascending: true })
          .range(a, b),
      )),
    );
  }
  const out: Record<string, Section[]> = {};
  for (const row of data) {
    const section = row.section as Section;
    if (!SECTIONS.includes(section)) continue;
    (out[row.user_id] ??= []).push(section);
  }
  return out;
}
