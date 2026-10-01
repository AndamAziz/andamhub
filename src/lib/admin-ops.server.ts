/**
 * Server-only implementations of every admin panel operation.
 *
 * These run with the service role, but only after `assertAdmin` has verified
 * the caller's role against the database.
 */
import {
  supabaseAdmin,
  maskSecret,
  probeProvider,
  relayHealth,
  verifyOwnPassword,
  type HealthSource,
} from '@/lib/admin.server';
import { playerApi, type Source } from '@/lib/xtream';
import type { OverrideKind } from '@/lib/overrides.server';

export type SourceType = 'xtream' | 'm3u';

export type ProviderRow = {
  id: string;
  slug: string;
  name: string;
  type: SourceType;
  base_url: string;
  username: string;
  playlist_url: string;
  passwordMasked: string;
  is_active: boolean;
  is_public: boolean;
  sort_order: number;
  created_at: string;
  /** Playlist cache stats, for m3u sources only. */
  playlist?: { channelCount: number; categoryCount: number; fetchedAt: string } | undefined;
};

const slugify = (value: string) =>
  value
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 48) || `provider-${Date.now()}`;

export async function listProviders(): Promise<ProviderRow[]> {
  const { playlistCacheStats } = await import('@/lib/m3u.server');
  const [{ data, error }, stats] = await Promise.all([
    supabaseAdmin
      .from('sources')
      .select(
        'id, slug, name, type, base_url, username, password, playlist_url, is_active, is_public, sort_order, created_at',
      )
      .order('sort_order', { ascending: true }),
    playlistCacheStats(),
  ]);
  if (error) throw new Error(error.message);
  return (data ?? []).map((s) => ({
    id: s.id,
    slug: s.slug,
    name: s.name,
    type: (s.type === 'm3u' ? 'm3u' : 'xtream') as SourceType,
    base_url: s.base_url ?? '',
    username: s.username ?? '',
    playlist_url: s.playlist_url ?? '',
    passwordMasked: maskSecret(s.password ?? ''),
    is_active: s.is_active,
    is_public: s.is_public,
    sort_order: s.sort_order,
    created_at: s.created_at,
    playlist: stats[s.id],
  }));
}

export type ProviderInput = {
  id?: string | undefined;
  type?: SourceType | undefined;
  name: string;
  slug?: string | undefined;
  base_url?: string | undefined;
  username?: string | undefined;
  password?: string | undefined;
  playlist_url?: string | undefined;
  is_active: boolean;
  is_public: boolean;
  sort_order: number;
};

export async function saveProvider(input: ProviderInput): Promise<{ id: string }> {
  const type: SourceType = input.type === 'm3u' ? 'm3u' : 'xtream';
  if (!input.name.trim()) throw new Error('Name is required');

  type Patch = {
    name: string;
    type: SourceType;
    is_active: boolean;
    is_public: boolean;
    sort_order: number;
    base_url?: string | null;
    username?: string | null;
    password?: string | null;
    playlist_url?: string | null;
    slug?: string;
  };
  const patch: Patch = {
    name: input.name.trim(),
    type,
    is_active: input.is_active,
    is_public: input.is_public,
    sort_order: input.sort_order,
  };

  if (type === 'm3u') {
    const playlist = (input.playlist_url ?? '').trim();
    if (!/^https?:\/\//i.test(playlist)) {
      throw new Error('Playlist URL must start with http:// or https://');
    }
    patch['playlist_url'] = playlist;
    patch['base_url'] = null;
    patch['username'] = null;
    patch['password'] = null;
  } else {
    const base_url = (input.base_url ?? '').trim().replace(/\/+$/, '');
    if (!/^https?:\/\//i.test(base_url)) {
      throw new Error('Base URL must start with http:// or https://');
    }
    patch['base_url'] = base_url;
    patch['username'] = (input.username ?? '').trim();
    patch['playlist_url'] = null;
    if (input.password) patch['password'] = input.password;
    else if (!input.id) throw new Error('Password is required for a new provider');
  }

  // The slug is unique in the database. Admins usually type only a name, so a
  // second provider with a similar name used to fail with a raw Postgres
  // "duplicate key" error. Derive a free slug instead.
  const desired = slugify(input.slug?.trim() || input.name);
  const slug = await freeSlug(desired, input.id);

  if (input.id) {
    const { error } = await supabaseAdmin
      .from('sources')
      .update({ ...patch, slug })
      .eq('id', input.id);
    if (error) throw new Error(friendlyError(error.message));
    return { id: input.id };
  }

  const { data, error } = await supabaseAdmin
    .from('sources')
    .insert({ ...patch, slug })
    .select('id')
    .single();
  if (error) throw new Error(friendlyError(error.message));
  return { id: data.id };
}

/** First slug in the `base`, `base-2`, `base-3`… series not used by another provider. */
async function freeSlug(base: string, ignoreId?: string): Promise<string> {
  const { data } = await supabaseAdmin.from('sources').select('id, slug');
  const taken = new Set(
    (data ?? []).filter((r) => r.id !== ignoreId).map((r) => r.slug),
  );
  if (!taken.has(base)) return base;
  for (let i = 2; i < 200; i += 1) {
    const candidate = `${base.slice(0, 44)}-${i}`;
    if (!taken.has(candidate)) return candidate;
  }
  return `${base.slice(0, 40)}-${Date.now().toString(36)}`;
}

/** Turns database noise into something an admin can act on. */
function friendlyError(message: string): string {
  if (/duplicate key|sources_slug_key/i.test(message)) {
    return 'A provider with that slug already exists — pick a different slug.';
  }
  if (/sources_type_check/i.test(message)) return 'Source type must be Xtream or M3U.';
  return message;
}


export async function deleteProvider(id: string): Promise<void> {
  const { error } = await supabaseAdmin.from('sources').delete().eq('id', id);
  if (error) throw new Error(error.message);
}

/** Re-fetches and re-parses an M3U source, returning the resulting counts. */
export async function refreshProviderPlaylist(id: string) {
  const { loadPlaylistSource, refreshPlaylist } = await import('@/lib/m3u.server');
  const source = await loadPlaylistSource(id);
  if (!source) throw new Error('That source is not an M3U playlist source');
  const result = await refreshPlaylist(source);
  return {
    channelCount: result.channelCount,
    categoryCount: result.categoryCount,
    fetchedAt: result.fetchedAt,
  };
}

async function loadSourceById(id: string): Promise<Source> {
  const { data, error } = await supabaseAdmin
    .from('sources')
    .select('id, slug, name, base_url, username, password')
    .eq('id', id)
    .single();
  if (error) throw new Error(error.message);
  return {
    id: data.id,
    slug: data.slug,
    name: data.name,
    base_url: data.base_url ?? '',
    username: data.username ?? '',
    password: data.password ?? '',
  };
}

export async function testProvider(input: {
  id?: string | undefined;
  base_url?: string | undefined;
  username?: string | undefined;
  password?: string | undefined;
}) {
  let source: Source;
  if (input.id) {
    source = await loadSourceById(input.id);
    if (input.base_url) source.base_url = input.base_url.replace(/\/+$/, '');
    if (input.username) source.username = input.username;
    if (input.password) source.password = input.password;
  } else {
    if (!input.base_url || !input.username || !input.password) {
      throw new Error('Base URL, username and password are required');
    }
    source = {
      id: 'probe',
      slug: 'probe',
      name: 'probe',
      base_url: input.base_url.replace(/\/+$/, ''),
      username: input.username,
      password: input.password,
    };
  }
  return probeProvider(source);
}

export async function revealPassword(
  adminEmail: string,
  adminPassword: string,
  sourceId: string,
): Promise<string> {
  const ok = await verifyOwnPassword(adminEmail, adminPassword);
  if (!ok) throw new Error('Your password is incorrect');
  const source = await loadSourceById(sourceId);
  return source.password;
}

export type AdminUser = {
  id: string;
  email: string;
  displayName: string;
  role: 'admin' | 'user';
  suspended: boolean;
  createdAt: string;
  lastLoginAt: string | null;
  sourceIds: string[];
};

export async function listUsers(): Promise<AdminUser[]> {
  const [{ data: profiles, error }, { data: roles }, { data: access }] = await Promise.all([
    supabaseAdmin
      .from('profiles')
      .select('id, email, display_name, is_suspended, last_login_at, created_at')
      .order('created_at', { ascending: false }),
    supabaseAdmin.from('user_roles').select('user_id, role'),
    supabaseAdmin.from('user_source_access').select('user_id, source_id'),
  ]);
  if (error) throw new Error(error.message);

  const adminIds = new Set((roles ?? []).filter((r) => r.role === 'admin').map((r) => r.user_id));
  return (profiles ?? []).map((p) => ({
    id: p.id,
    email: p.email ?? '',
    displayName: p.display_name ?? '',
    role: adminIds.has(p.id) ? ('admin' as const) : ('user' as const),
    suspended: Boolean(p.is_suspended),
    createdAt: p.created_at,
    lastLoginAt: p.last_login_at,
    sourceIds: (access ?? []).filter((a) => a.user_id === p.id).map((a) => a.source_id),
  }));
}

export async function setUserRole(
  actorId: string,
  userId: string,
  role: 'admin' | 'user',
): Promise<void> {
  if (actorId === userId && role === 'user') {
    throw new Error('You cannot remove your own admin role');
  }
  if (role === 'admin') {
    const { error } = await supabaseAdmin
      .from('user_roles')
      .upsert({ user_id: userId, role: 'admin' }, { onConflict: 'user_id,role' });
    if (error) throw new Error(error.message);
  } else {
    const { error } = await supabaseAdmin
      .from('user_roles')
      .delete()
      .eq('user_id', userId)
      .eq('role', 'admin');
    if (error) throw new Error(error.message);
    await supabaseAdmin
      .from('user_roles')
      .upsert({ user_id: userId, role: 'user' }, { onConflict: 'user_id,role' });
  }
}

/** Suspends or restores an account: bans the login and flags the profile. */
export async function setUserSuspended(
  actorId: string,
  userId: string,
  suspended: boolean,
): Promise<void> {
  if (actorId === userId) throw new Error('You cannot suspend your own account');
  const url = process.env['SUPABASE_URL'];
  const key = process.env['SUPABASE_SERVICE_ROLE_KEY'];
  if (url && key) {
    const res = await fetch(`${url}/auth/v1/admin/users/${userId}`, {
      method: 'PUT',
      headers: { apikey: key, Authorization: `Bearer ${key}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ ban_duration: suspended ? '876000h' : 'none' }),
    });
    if (!res.ok) throw new Error(`Could not update login access (${res.status})`);
  }
  const { error } = await supabaseAdmin
    .from('profiles')
    .update({ is_suspended: suspended })
    .eq('id', userId);
  if (error) throw new Error(error.message);
}

/**
 * Sets a new password for any account without an email round-trip, then revokes
 * that user's refresh tokens so old sessions stop working immediately.
 * Session revocation is best-effort: a failure there must not hide the fact the
 * password itself was changed successfully.
 */
export async function setUserPassword(userId: string, password: string): Promise<void> {
  if (password.length < 8) throw new Error('Password must be at least 8 characters');

  const { error } = await supabaseAdmin.auth.admin.updateUserById(userId, { password });
  if (error) throw new Error(error.message);

  const url = process.env['SUPABASE_URL'];
  const key = process.env['SUPABASE_SERVICE_ROLE_KEY'];
  if (!url || !key) return;
  try {
    const res = await fetch(`${url}/auth/v1/admin/users/${userId}/logout`, {
      method: 'POST',
      headers: { apikey: key, Authorization: `Bearer ${key}` },
    });
    if (!res.ok) console.warn('[admin] could not revoke sessions', res.status);
  } catch (err) {
    console.warn('[admin] session revocation failed', err);
  }
}

export async function setUserAccess(
  userId: string,
  sourceId: string,
  grant: boolean,
): Promise<void> {
  if (grant) {
    const { error } = await supabaseAdmin
      .from('user_source_access')
      .upsert({ user_id: userId, source_id: sourceId }, { onConflict: 'user_id,source_id' });
    if (error) throw new Error(error.message);
  } else {
    const { error } = await supabaseAdmin
      .from('user_source_access')
      .delete()
      .eq('user_id', userId)
      .eq('source_id', sourceId);
    if (error) throw new Error(error.message);
  }
}

export type OverrideRow = {
  id: string;
  kind: OverrideKind;
  item_id: string;
  label: string | null;
  hidden: boolean;
  sort_order: number | null;
  logo_url: string | null;
};

export async function listOverrides(sourceId: string): Promise<OverrideRow[]> {
  // Paged: the database answers at most 1000 rows per request.
  const PAGE = 1000;
  const all: OverrideRow[] = [];
  for (let from = 0; ; from += PAGE) {
    const { data, error } = await supabaseAdmin
      .from('content_overrides')
      .select('id, kind, item_id, label, hidden, sort_order, logo_url')
      .eq('source_id', sourceId)
      .order('id', { ascending: true })
      .range(from, from + PAGE - 1);
    if (error) throw new Error(error.message);
    all.push(...((data ?? []) as OverrideRow[]));
    if (!data || data.length < PAGE) break;
  }
  return all;
}

export async function saveOverride(input: {
  sourceId: string;
  kind: OverrideKind;
  itemId: string;
  label?: string | undefined;
  hidden?: boolean | undefined;
  sortOrder?: number | null | undefined;
  logoUrl?: string | null | undefined;
}): Promise<void> {
  const row: {
    source_id: string;
    kind: OverrideKind;
    item_id: string;
    label?: string | null;
    hidden?: boolean;
    sort_order?: number | null;
    logo_url?: string | null;
  } = {
    source_id: input.sourceId,
    kind: input.kind,
    item_id: input.itemId,
  };
  if (input.label !== undefined) row.label = input.label;
  if (input.hidden !== undefined) row.hidden = input.hidden;
  if (input.sortOrder !== undefined) row.sort_order = input.sortOrder;
  if (input.logoUrl !== undefined) row.logo_url = input.logoUrl || null;

  const { error } = await supabaseAdmin
    .from('content_overrides')
    .upsert(row, { onConflict: 'source_id,kind,item_id' });
  if (error) throw new Error(error.message);
}

export async function clearOverride(sourceId: string, kind: OverrideKind, itemId: string) {
  const { error } = await supabaseAdmin
    .from('content_overrides')
    .delete()
    .eq('source_id', sourceId)
    .eq('kind', kind)
    .eq('item_id', itemId);
  if (error) throw new Error(error.message);
}

export type ContentItem = { id: string; name: string; num?: number; logo?: string };

/** Raw provider listing (no overrides applied) for the content manager. */
export async function listProviderItems(
  sourceId: string,
  kind: OverrideKind,
  categoryId: string,
): Promise<ContentItem[]> {
  const source = await loadSourceById(sourceId);
  if (kind === 'category') {
    const cats = await playerApi<Array<{ category_id: string; category_name: string }>>(source, {
      action: 'get_live_categories',
    });
    return (Array.isArray(cats) ? cats : []).map((c) => ({
      id: String(c.category_id),
      name: c.category_name,
    }));
  }
  const action = kind === 'live' ? 'get_live_streams' : kind === 'vod' ? 'get_vod_streams' : 'get_series';
  const raw = await playerApi<
    Array<{
      stream_id?: number;
      series_id?: number;
      name: string;
      num?: number;
      stream_icon?: string;
      cover?: string;
    }>
  >(source, { action, ...(categoryId ? { category_id: categoryId } : {}) });
  return (Array.isArray(raw) ? raw : []).map((s, i) => ({
    id: String(s.stream_id ?? s.series_id ?? i),
    name: s.name,
    num: Number(s.num ?? i + 1),
    logo: s.stream_icon || s.cover || '',
  }));
}

export async function adminOverview() {
  // The relay and provider checks need the stored relay address/token, which the
  // provider list intentionally omits, so read the full rows once up front.
  const { data: sourceRows } = await supabaseAdmin
    .from('sources')
    .select('*')
    .order('sort_order', { ascending: true });
  const sources = (sourceRows ?? []) as HealthSource[];
  const liveSources = sources.filter((s) => s.is_active && s.type === 'xtream');

  const [providers, health, probes, users, logins, errors] = await Promise.all([
    listProviders(),
    relayHealth(sources),
    Promise.all(
      liveSources.map(async (s) => ({
        id: s.id,
        name: s.name,
        ...(await probeProvider(s)),
      })),
    ),
    supabaseAdmin.from('profiles').select('id', { count: 'exact', head: true }),
    supabaseAdmin
      .from('login_activity')
      .select('email, created_at, user_agent')
      .order('created_at', { ascending: false })
      .limit(10),
    supabaseAdmin
      .from('playback_errors')
      .select('kind, item_id, status, message, created_at, source_id')
      .order('created_at', { ascending: false })
      .limit(15),
  ]);

  return {
    providers: providers.map((p) => ({ id: p.id, name: p.name, active: p.is_active })),
    activeProviders: providers.filter((p) => p.is_active).length,
    totalProviders: providers.length,
    totalUsers: users.count ?? 0,
    relay: health,
    providerProbes: probes,
    recentLogins: logins.data ?? [],
    recentErrors: errors.data ?? [],
  };
}

/** Wipes the login activity log (admin-only maintenance action). */
export async function clearLoginActivity(): Promise<{ ok: true }> {
  const { error } = await supabaseAdmin
    .from('login_activity')
    .delete()
    .not('id', 'is', null);
  if (error) throw new Error(error.message);
  return { ok: true };
}
