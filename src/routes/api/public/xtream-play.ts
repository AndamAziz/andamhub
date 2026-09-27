import { createFileRoute } from '@tanstack/react-router';
import { openUrl, sealUrl } from '@/lib/xtream-crypto';
import { readRelay, relayHeaders, relayUrl, tagWithRelay, type RelayConfig } from '@/lib/xtream';

/**
 * Playback proxy.
 *
 * Takes an opaque token (from /api/public/xtream), decrypts it into the real
 * provider URL, and streams the bytes through the Andam relay. HLS manifests
 * are rewritten so every segment/variant also arrives as an opaque token, so
 * the browser never sees provider credentials or the relay token.
 *
 * HTTP Range requests are forwarded untouched so VOD seeking keeps working.
 */

const SAFE_HEADERS = [
  'content-type',
  'content-range',
  'content-disposition',
  'last-modified',
  'etag',
];


function isManifest(url: string, contentType: string | null): boolean {
  if (/\.m3u8(\?|$)/i.test(url)) return true;
  const ct = (contentType ?? '').toLowerCase();
  return ct.includes('mpegurl') || ct.includes('vnd.apple.mpegurl');
}
/**
 * Reads a manifest without waiting for the connection to close.
 *
 * Some upstreams (and the relay in front of them) treat a `.m3u8` request as a
 * long-lived stream: they keep the socket open and re-send the playlist over and
 * over. `res.text()` then never resolves and the player spins forever, so read
 * incrementally, stop at the first complete playlist, and cap size/time.
 */
async function readManifest(res: Response): Promise<string> {
  const MAX_BYTES = 2_000_000;
  const MAX_MS = 8000;
  const reader = res.body?.getReader();
  if (!reader) return '';
  const decoder = new TextDecoder();
  const started = Date.now();
  let text = '';
  try {
    for (;;) {
      if (Date.now() - started > MAX_MS) break;
      const { done, value } = await Promise.race([
        reader.read(),
        new Promise<{ done: true; value: undefined }>((r) =>
          setTimeout(() => r({ done: true, value: undefined }), Math.max(0, MAX_MS - (Date.now() - started))),
        ),
      ]);
      if (done) break;
      text += decoder.decode(value, { stream: true });
      // A repeated `#EXTM3U` header means the upstream restarted the playlist.
      const repeat = text.indexOf('#EXTM3U', text.indexOf('#EXTM3U') + 1);
      if (repeat > 0) {
        text = text.slice(0, repeat);
        break;
      }
      if (text.includes('#EXT-X-ENDLIST') || text.length > MAX_BYTES) break;
    }
  } finally {
    try {
      await reader.cancel();
    } catch {
      /* upstream already gone */
    }
  }
  return text;
}


/**
 * Fetch provider bytes through the configured relay only.
 *
 * A dead upstream (expired provider account, blackholed host) makes the relay
 * hold the connection open with no response at all, which used to leave the
 * player spinning until the browser gave up. Bail out after 15s instead — the
 * caller turns that into a clean error the UI can show.
 */
async function fetchRelay(
  url: string,
  request: Request,
  relay: RelayConfig | null,
): Promise<Response> {
  const headers = new Headers(relayHeaders(relay));
  headers.set('User-Agent', 'AndamTV/1.0');
  const range = request.headers.get('range');
  if (range) headers.set('Range', range);
  // The 15s budget covers *answering*, not streaming. `AbortSignal.timeout`
  // kept aborting the response body mid-flight, so a live MPEG-TS channel died
  // exactly 15 seconds in and the picture froze. Cancel the timer as soon as
  // the headers arrive and let the body run for as long as the viewer watches.
  const ac = new AbortController();
  const guard = setTimeout(() => ac.abort(), 15_000);
  try {
    return await fetch(relayUrl(url, relay), {
      headers,
      redirect: 'follow',
      signal: ac.signal,
    });
  } finally {
    clearTimeout(guard);
  }
}

/**
 * Resolves provider redirects so manifest URIs get the right base.
 *
 * Many Xtream/playlist links (`/live/user/pass/123.ts`) answer 30x and hand the
 * real playlist off to another host. The relay follows redirects internally and
 * does not always report the final URL, so relative variant/segment URIs in the
 * returned manifest would be resolved against the *original* path and 404.
 * Peek at the redirect chain ourselves; if the provider refuses us directly we
 * simply keep the original URL and let the relay handle it.
 */
async function resolveRedirects(url: string): Promise<string> {
  let current = url;
  for (let hop = 0; hop < 4; hop++) {
    try {
      const res = await fetch(current, {
        method: 'GET',
        redirect: 'manual',
        headers: { 'User-Agent': 'VLC/3.0.20 LibVLC/3.0.20' },
        signal: AbortSignal.timeout(6000),
      });
      try {
        await res.body?.cancel();
      } catch {
        /* nothing to drain */
      }
      const location = res.headers.get('location');
      if (res.status >= 300 && res.status < 400 && location) {
        current = new URL(location, current).toString();
        continue;
      }
      return current;
    } catch {
      return current;
    }
  }
  return current;
}

/** Providers often mislabel segments (text/css, text/html); fix by extension. */
function segmentContentType(url: string, upstreamType: string | null): string | null {
  const ct = (upstreamType ?? '').toLowerCase();
  const path = url.split('?')[0] ?? '';
  if (/\.ts$/i.test(path)) return 'video/mp2t';
  if (/\.m4s$/i.test(path) || /\.mp4$/i.test(path)) return 'video/mp4';
  if (/\.aac$/i.test(path)) return 'audio/aac';
  if (!ct || ct.startsWith('text/')) return 'application/octet-stream';
  return null;
}


/** A finite media chunk (as opposed to a manifest or an endless live stream). */
function isSegment(url: string): boolean {
  const path = (url.split('?')[0] ?? '').toLowerCase();
  return /\.(ts|m4s|mp4|aac|vtt|key)$/.test(path);
}

/**
 * Fetch one finite segment straight from its host.
 *
 * Public CDN playlists (Akamai and friends) hand out closed, content-length'd
 * segments. The relay in front of them does not always forward that end: it
 * keeps the socket open and streams on forever, so a 10-second, 3 MB chunk
 * arrived as hundreds of megabytes that never finished and the picture never
 * started. Those hosts need no relay at all, so try them directly first and
 * fall back to the relay whenever the host refuses us (IP-bound providers).
 */
async function fetchDirectSegment(url: string, request: Request): Promise<Response | null> {
  const headers = new Headers({ 'User-Agent': 'VLC/3.0.20 LibVLC/3.0.20' });
  const range = request.headers.get('range');
  if (range) headers.set('Range', range);
  const ac = new AbortController();
  const guard = setTimeout(() => ac.abort(), 8000);
  try {
    const res = await fetch(url, { headers, redirect: 'follow', signal: ac.signal });
    clearTimeout(guard);
    if (res.ok || res.status === 206) return res;
    try {
      await res.body?.cancel();
    } catch {
      /* nothing to drain */
    }
    return null;
  } catch {
    clearTimeout(guard);
    return null;
  }
}

async function fetchUpstream(
  upstream: string,
  request: Request,
  relay: RelayConfig | null,
  fromManifest = false,
): Promise<Response> {
  // Only chunks we pulled out of a manifest are known-finite. A first-hop
  // `.ts` link is an endless live stream and stays on the relay.
  if (fromManifest && isSegment(upstream)) {
    const direct = await fetchDirectSegment(upstream, request);
    if (direct) return direct;
  }
  let res = await fetchRelay(upstream, request, relay);
  // 403/411/5xx from the relay are usually transient — retry once.
  if (!res.ok && (res.status === 403 || res.status === 411 || res.status >= 500)) {
    try {
      await res.body?.cancel();
    } catch {
      /* nothing to drain */
    }
    await new Promise((r) => setTimeout(r, 350));
    res = await fetchRelay(upstream, request, relay);
  }
  return res;
}

/**
 * On-demand transcode (`&tc=1`).
 *
 * Some channels are broadcast with a codec the browser cannot decode (H.265
 * video, AC3/E-AC3 audio). The relay host runs `relay/transcode.php`, which
 * copies the video and re-encodes the audio to AAC in an MPEG-TS stream. This
 * path is only taken when the player has already failed or stalled on the
 * normal stream, so healthy channels never pay for it.
 */
function transcodeEndpoint(relay: RelayConfig | null): string | null {
  const explicit = process.env['TRANSCODE_URL'];
  if (explicit) return explicit;
  const base = relay?.base;
  if (!base) return null;
  return base.replace(/\/proxy\/?$/i, '') + '/transcode.php';
}

/** The audio fixer runs on the relay host (pm2 `andam-tc`, port 9311), reached through the relay. */
const LOCAL_TRANSCODER = 'http://127.0.0.1:9311/transcode.php';

async function fetchTranscoded(
  upstream: string,
  _relay: RelayConfig | null,
): Promise<Response | null> {
  const endpoint = process.env['TRANSCODE_URL'] || LOCAL_TRANSCODER;
  const direct = `${endpoint}${endpoint.includes('?') ? '&' : '?'}stream=${encodeURIComponent(upstream)}`;
  // Loopback addresses only exist on the relay host, so ask the shared relay to fetch them.
  const viaRelay = /^https?:\/\/(127\.0\.0\.1|localhost)[:/]/i.test(endpoint);
  const target = viaRelay ? relayUrl(direct, null) : direct;
  const ac = new AbortController();
  // ffmpeg needs a moment to open the source; once it answers the stream must
  // keep running, so the guard only covers the handshake.
  const guard = setTimeout(() => ac.abort(), 25_000);
  try {
    const res = await fetch(target, {
      headers: { ...relayHeaders(null), 'User-Agent': 'AndamTV/1.0' },
      redirect: 'follow',
      signal: ac.signal,
    });
    clearTimeout(guard);
    if (!res.ok || !res.body) {
      try {
        await res.body?.cancel();
      } catch {
        /* nothing to drain */
      }
      console.error('[xtream-play] transcoder responded', res.status);
      return null;
    }
    return res;
  } catch (err) {
    clearTimeout(guard);
    console.error('[xtream-play] transcoder error', err);
    return null;
  }
}



/**
 * Rewrites manifest URIs to *relative* playback URLs. Absolute URLs built from
 * the incoming request origin are wrong behind the preview/published proxy
 * (the server sees http://localhost:8080), which made the browser request a
 * dead origin and left the player spinning forever.
 *
 * Child URLs keep the provider's relay marker so segments travel the same relay
 * as the manifest they came from.
 */
async function rewriteManifest(
  text: string,
  upstream: string,
  relay: RelayConfig | null,
): Promise<string> {
  const base = new URL(upstream);
  const absolute = (ref: string) => new URL(ref, base).toString();
  // `s=1` marks a URL we generated from an already-resolved manifest, so the
  // handler can skip the redirect probe for it.
  const token = async (ref: string) =>
    `/api/public/xtream-play?s=1&t=${encodeURIComponent(await sealUrl(tagWithRelay(absolute(ref), relay)))}`;

  const lines = text.split(/\r?\n/);
  const out: string[] = [];
  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed) {
      out.push(line);
      continue;
    }
    if (trimmed.startsWith('#')) {
      // Rewrite URI="..." attributes (keys, media, i-frame playlists).
      const uriMatch = trimmed.match(/URI="([^"]+)"/);
      if (uriMatch && uriMatch[1]) {
        out.push(trimmed.replace(/URI="([^"]+)"/, `URI="${await token(uriMatch[1])}"`));
      } else {
        out.push(line);
      }
      continue;
    }
    out.push(await token(trimmed));
  }
  return out.join('\n');
}

export const Route = createFileRoute('/api/public/xtream-play')({
  server: {
    handlers: {
      GET: async ({ request }) => {
        const url = new URL(request.url);
        const token = url.searchParams.get('t');
        if (!token) return new Response('Missing token', { status: 400 });

        const sealed = await openUrl(token);
        if (!sealed) return new Response('Link expired', { status: 410 });

        // Providers may carry their own relay host/token; the marker travels
        // inside the sealed link and never reaches the provider itself.
        const { upstream: target, relay } = readRelay(sealed);

        // Only the first hop (the link the UI hands us) may still redirect.
        const upstream =
          url.searchParams.get('s') === '1' ? target : await resolveRedirects(target);

        // Repair path: the player asks for a transcode only after the plain
        // stream stalled or the decoder refused it. If the transcoder is not
        // reachable we silently continue with the normal relay path.
        if (url.searchParams.get('tc') === '1') {
          const tc = await fetchTranscoded(upstream, relay);
          if (tc) {
            return new Response(tc.body, {
              status: 200,
              headers: {
                'Content-Type': 'video/mp2t',
                'Cache-Control': 'no-store',
                'Access-Control-Allow-Origin': '*',
              },
            });
          }
        }

        let res: Response;
        try {
          res = await fetchUpstream(
            upstream,
            request,
            relay,
            url.searchParams.get('s') === '1',
          );
        } catch (err) {
          const timedOut = err instanceof Error && /timeout|abort/i.test(err.name + err.message);
          console.error('[xtream-play] relay error', err);
          return new Response(
            timedOut ? 'Stream timed out (provider not responding)' : 'Stream unavailable',
            { status: timedOut ? 504 : 502, headers: { 'Access-Control-Allow-Origin': '*' } },
          );
        }

        if (!res.ok) {
          console.error('[xtream-play] relay responded', res.status, res.statusText);
          return new Response(
            res.status === 404 ? 'Stream not found' : `Stream unavailable (relay ${res.status})`,
            { status: res.status === 404 ? 404 : 502, headers: { 'Access-Control-Allow-Origin': '*' } },
          );
        }

        // Allowlist only: upstream headers such as x-final-url echo the provider
        // URL (with credentials) and must never reach the browser.
        const headers = new Headers();
        for (const key of SAFE_HEADERS) {
          const value = res.headers.get(key);
          if (value) headers.set(key, value);
        }
        // Some providers answer a suffix range (`bytes=-N`, which browsers use to
        // find the moov atom of non-faststart MP4s) with a malformed
        // `Content-Range: bytes -N/total`. Chrome/Safari reject that and the
        // movie/episode never starts, so normalise it into a real byte range.
        const cr = headers.get('content-range');
        if (cr && !/^bytes \d+-\d+\/\d+$/.test(cr.trim())) {
          const total = Number((cr.match(/\/(\d+)\s*$/) ?? [])[1] ?? NaN);
          const req = (request.headers.get('range') ?? '').match(/bytes=(\d*)-(\d*)/);
          if (Number.isFinite(total) && req) {
            const suffix = !req[1] && req[2] ? Number(req[2]) : NaN;
            const start = Number.isFinite(suffix)
              ? Math.max(0, total - suffix)
              : Number(req[1] || 0);
            const end = Number.isFinite(suffix) ? total - 1 : Number(req[2] || total - 1);
            headers.set('Content-Range', `bytes ${start}-${end}/${total}`);
          } else {
            headers.delete('Content-Range');
          }
        }
        headers.set('Cache-Control', 'no-store');
        headers.set('Access-Control-Allow-Origin', '*');


        if (isManifest(upstream, res.headers.get('content-type'))) {
          const text = await readManifest(res);
          // The relay may follow redirects; resolve relative URIs against the
          // URL the manifest actually came from when the relay reports it.
          const finalUrl = res.headers.get('x-final-url') || upstream;
          const body = await rewriteManifest(text, finalUrl, relay);
          headers.set('Content-Type', 'application/vnd.apple.mpegurl');
          return new Response(body, { status: 200, headers });
        }

        headers.set('Accept-Ranges', 'bytes');
        const fixedType = segmentContentType(upstream, res.headers.get('content-type'));
        if (fixedType) headers.set('Content-Type', fixedType);
        return new Response(res.body, { status: res.status, headers });

      },
    },
  },
});
