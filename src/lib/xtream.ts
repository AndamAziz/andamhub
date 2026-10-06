/**
 * Server-side Xtream Codes helpers.
 *
 * Provider credentials live in the `sources` table (service-role only) and are
 * used exclusively here, on the server. Nothing in this module returns a URL
 * that contains a username or password to the browser.
 */

export const RELAY_BASE = 'https://relay.andam.uk:8443/proxy?url=';

export type Source = {
  id: string;
  slug: string;
  name: string;
  base_url: string;
  username: string;
  password: string;
  /** Optional per-provider relay, stored in the admin panel. */
  relay_url?: string | null;
  relay_token?: string | null;
};

export type XtreamKind = 'live' | 'vod' | 'series';

/** A resolved relay endpoint: the `?url=` prefix plus the token to send. */
export type RelayConfig = { base: string; token: string };

/** Optional request headers declared by an M3U playlist. */
export type StreamHeaders = { referer?: string; origin?: string; userAgent?: string };

/**
 * Query parameter that carries a per-provider relay through a sealed token.
 *
 * Playback tokens only hold the upstream URL, and /api/public/xtream-play has
 * no idea which provider produced one. Tagging the URL before it is sealed lets
 * the playback route use that provider's own relay and token; the marker is
 * stripped again before anything is sent upstream.
 */
export const RELAY_PARAM = '__arly';
export const STREAM_HEADERS_PARAM = '__arhd';

/** The relay token shipped with the project; overridable through a secret. */
const DEFAULT_RELAY_TOKEN =
  '009c95e9a8c6e50d992b8313bb90b01948b4a58e870bd69504a640b32306a5da';

const defaultRelay = (): RelayConfig => ({
  base: process.env['RELAY_URL'] ?? RELAY_BASE,
  token: process.env['RELAY_TOKEN'] ?? DEFAULT_RELAY_TOKEN,
});

/** Accepts `https://host/proxy` or `https://host/proxy?url=` and normalises it. */
function normaliseRelayBase(value: string): string {
  const base = value.trim();
  if (!base) return RELAY_BASE;
  if (/[?&]url=$/.test(base)) return base;
  return base.includes('?') ? `${base}&url=` : `${base}?url=`;
}

/** The relay to use for one provider: its stored values, else the shared relay. */
export function relayConfig(source?: {
  relay_url?: string | null;
  relay_token?: string | null;
}): RelayConfig {
  const fallback = defaultRelay();
  const base = (source?.relay_url ?? '').trim();
  const token = (source?.relay_token ?? '').trim();
  return {
    base: base ? normaliseRelayBase(base) : fallback.base,
    token: token || fallback.token,
  };
}

export function relayUrl(upstream: string, relay?: RelayConfig | null): string {
  return (relay?.base ?? defaultRelay().base) + encodeURIComponent(upstream);
}

export function relayHeaders(relay?: RelayConfig | null): Record<string, string> {
  return { 'X-Relay-Token': relay?.token ?? defaultRelay().token };
}

const b64url = (value: string) =>
  btoa(value).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

function fromB64url(value: string): string {
  const padded = value.replace(/-/g, '+').replace(/_/g, '/');
  return atob(padded + '='.repeat((4 - (padded.length % 4)) % 4));
}

/** Adds the relay marker to an upstream URL, unless it uses the shared relay. */
export function tagRelay(upstream: string, source?: Source): string {
  return tagWithRelay(upstream, relayConfig(source));
}

/** Same, from an already-resolved relay (used when rewriting HLS manifests). */
export function tagWithRelay(upstream: string, relay: RelayConfig | null): string {
  if (!relay) return upstream;
  const shared = defaultRelay();
  if (relay.base === shared.base && relay.token === shared.token) return upstream;
  const mark = b64url(JSON.stringify(relay));
  return upstream + (upstream.includes('?') ? '&' : '?') + `${RELAY_PARAM}=${mark}`;
}

/** Keeps M3U request headers inside the encrypted playback token. */
export function tagStreamHeaders(upstream: string, headers?: StreamHeaders): string {
  const clean: StreamHeaders = {};
  if (headers?.referer) clean.referer = headers.referer.slice(0, 2048);
  if (headers?.origin) clean.origin = headers.origin.slice(0, 2048);
  if (headers?.userAgent) clean.userAgent = headers.userAgent.slice(0, 512);
  if (!Object.keys(clean).length) return upstream;
  const mark = b64url(JSON.stringify(clean));
  return upstream + (upstream.includes('?') ? '&' : '?') + `${STREAM_HEADERS_PARAM}=${mark}`;
}

/** Removes and validates the optional M3U request-header marker. */
export function readStreamHeaders(tagged: string): { upstream: string; headers: StreamHeaders } {
  const at = tagged.indexOf(`${STREAM_HEADERS_PARAM}=`);
  if (at < 0) return { upstream: tagged, headers: {} };
  const separator = tagged[at - 1];
  const raw = tagged.slice(at + STREAM_HEADERS_PARAM.length + 1).split('&')[0] ?? '';
  const rest = tagged.slice(at + STREAM_HEADERS_PARAM.length + 1 + raw.length).replace(/^&/, '');
  const upstream =
    tagged.slice(0, at - 1) + (rest ? (separator === '?' ? `?${rest}` : `&${rest}`) : '');
  try {
    const value = JSON.parse(fromB64url(raw)) as StreamHeaders;
    const headers: StreamHeaders = {};
    if (typeof value.referer === 'string') headers.referer = value.referer;
    if (typeof value.origin === 'string') headers.origin = value.origin;
    if (typeof value.userAgent === 'string') headers.userAgent = value.userAgent;
    return { upstream, headers };
  } catch {
    return { upstream, headers: {} };
  }
}

/** Splits a tagged URL back into the real upstream URL and its relay. */
export function readRelay(tagged: string): { upstream: string; relay: RelayConfig | null } {
  const at = tagged.indexOf(`${RELAY_PARAM}=`);
  if (at < 0) return { upstream: tagged, relay: null };
  const separator = tagged[at - 1];
  const raw = tagged.slice(at + RELAY_PARAM.length + 1).split('&')[0] ?? '';
  const rest = tagged.slice(at + RELAY_PARAM.length + 1 + raw.length).replace(/^&/, '');
  const upstream =
    tagged.slice(0, at - 1) + (rest ? (separator === '?' ? `?${rest}` : `&${rest}`) : '');
  try {
    const parsed = JSON.parse(fromB64url(raw)) as RelayConfig;
    if (typeof parsed.base === 'string' && typeof parsed.token === 'string') {
      return { upstream, relay: parsed };
    }
  } catch {
    /* malformed marker — fall back to the shared relay */
  }
  return { upstream, relay: null };
}

/** The provider address as entered, without a trailing slash or a pasted "/player_api.php". */
function apiBase(source: Source): string {
  return source.base_url.trim().replace(/\/+$/, '').replace(/\/player_api\.php$/i, '');
}

/**
 * Where a provider serves its streams (/live, /movie, /series, /timeshift).
 *
 * Classic Xtream panels serve both the API and the streams from the server root. Some panels
 * keep player_api.php under a path (e.g. https://host/api/public) but still serve the streams
 * from the root. The right root is learned once per provider (see learnStreamBase); until then,
 * and for every provider entered without a path, it is simply the address as entered.
 */
const streamRoots = new Map<string, string>();
const rootKey = (source: Source) => `${source.id}|${apiBase(source)}`;
function streamBase(source: Source): string {
  return streamRoots.get(rootKey(source)) ?? apiBase(source);
}

/**
 * For providers entered with a path: checks whether streams answer under that path; if they
 * give 404 there but answer at the server root, the root is used from then on. Generic — no
 * provider is named — and providers entered without a path are never probed.
 */
export async function learnStreamBase(
  source: Source,
  sampleId: string | number,
  kind: 'live' | 'movie' | 'series' = 'live',
  ext = 'm3u8',
): Promise<void> {
  const key = rootKey(source);
  if (streamRoots.has(key)) return;
  const base = apiBase(source);
  let origin = '';
  try {
    const u = new URL(base);
    if (u.pathname === '' || u.pathname === '/') {
      streamRoots.set(key, base);
      return;
    }
    origin = u.origin;
  } catch {
    return;
  }
  const status = async (root: string) => {
    try {
      const res = await fetch(`${root}/${kind}/${source.username}/${source.password}/${sampleId}.${ext}`, {
        redirect: 'manual',
        headers: { 'User-Agent': 'VLC/3.0.20 LibVLC/3.0.20' },
      });
      try {
        await res.body?.cancel();
      } catch {
        /* nothing to release */
      }
      return res.status;
    } catch {
      return 0;
    }
  };
  const answers = (code: number) => code >= 200 && code < 400;
  const atBase = await status(base);
  if (answers(atBase)) {
    streamRoots.set(key, base);
    return;
  }
  if (atBase === 404 && answers(await status(origin))) streamRoots.set(key, origin);
}

function apiUrl(source: Source, params: Record<string, string>): string {
  const base = apiBase(source);
  const qs = new URLSearchParams({
    username: source.username,
    password: source.password,
    ...params,
  });
  return `${base}/player_api.php?${qs.toString()}`;
}

/**
 * Calls player_api.php server-side. Never called from the browser.
 *
 * Some panels reject a server IP intermittently (403/429/5xx) while the same
 * request succeeds through the relay, so the direct call is retried once and
 * then repeated through the relay before we report a failure.
 */
export async function playerApi<T>(source: Source, params: Record<string, string>): Promise<T> {
  const target = apiUrl(source, params);
  const attempts: Array<() => Promise<Response>> = [
    () => fetch(target, { headers: { 'User-Agent': 'AndamTV/1.0', Accept: 'application/json' } }),
    () => fetch(target, { headers: { 'User-Agent': 'VLC/3.0.20 LibVLC/3.0.20', Accept: '*/*' } }),
    () =>
      fetch(relayUrl(target, relayConfig(source)), {
        headers: {
          ...relayHeaders(relayConfig(source)),
          'User-Agent': 'AndamTV/1.0',
          Accept: 'application/json',
        },
        redirect: 'follow',
      }),
  ];

  let lastStatus = 0;
  let lastError = '';
  for (let i = 0; i < attempts.length; i += 1) {
    let res: Response;
    try {
      res = await attempts[i]!();
    } catch (err) {
      lastError = err instanceof Error ? err.message : 'network error';
      continue;
    }
    if (!res.ok) {
      lastStatus = res.status;
      try {
        await res.body?.cancel();
      } catch {
        /* nothing to drain */
      }
      if (i < attempts.length - 1) await new Promise((r) => setTimeout(r, 400));
      continue;
    }
    const text = await res.text();
    try {
      return JSON.parse(text) as T;
    } catch {
      lastError = 'provider returned a malformed response';
    }
  }
  throw new Error(
    lastStatus ? `provider responded ${lastStatus}` : lastError || 'provider unreachable',
  );
}


export function liveStreamUrl(source: Source, streamId: string | number, ext = 'm3u8'): string {
  const base = streamBase(source);
  return `${base}/live/${source.username}/${source.password}/${streamId}.${ext}`;
}

export function vodStreamUrl(source: Source, streamId: string | number, ext = 'mp4'): string {
  const base = streamBase(source);
  return `${base}/movie/${source.username}/${source.password}/${streamId}.${ext}`;
}

export function seriesStreamUrl(source: Source, episodeId: string | number, ext = 'mp4'): string {
  const base = streamBase(source);
  return `${base}/series/${source.username}/${source.password}/${episodeId}.${ext}`;
}

/** {base}/timeshift/{u}/{p}/{duration}/{yyyy-MM-dd:HH-mm}/{stream_id}.m3u8 */
export function timeshiftUrl(
  source: Source,
  streamId: string | number,
  durationMinutes: number,
  start: string,
): string {
  const base = streamBase(source);
  return `${base}/timeshift/${source.username}/${source.password}/${durationMinutes}/${start}/${streamId}.m3u8`;
}
