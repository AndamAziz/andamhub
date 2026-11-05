import { createFileRoute } from '@tanstack/react-router';

/**
 * Channel logos and posters, fetched for the viewer when the image does not load from its own
 * host (the apps and the website try the original link first and come here only on error).
 *
 *   /api/public/img?u=<image url>
 *
 * Logos come from thousands of hosts. The usual reasons one does not show:
 *   - the host answers 429 when many are asked at once (Wikimedia) → retried here, served from
 *     Cloudflare's cache afterwards;
 *   - a Wikimedia thumbnail size is refused (400/429) → the original file is used instead;
 *   - the host refuses other sites or unknown clients (403) → asked like a browser, no Referer;
 *   - plain http on the https website (blocked as mixed content) → served over https here;
 *   - the link is an image *page* (ibb.co/…, a page with og:image) → its image is taken.
 * Only images (image/*) up to 6 MB are passed on. Dead links answer 404 (cached for an hour).
 */

const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36';
const MAX_BYTES = 6 * 1024 * 1024;
const OK_TTL = 7 * 24 * 3600;
const MISS_TTL = 3600;
/** Rate-limited (429) misses are asked again sooner. */
const BUSY_TTL = 300;
/** Wikimedia asks automated clients to name themselves (and throttles anonymous browser UAs). */
const WIKI_UA = 'AndamImageCache/1.0 (+https://ip.andam.uk; logos for the Andam TV guide)';
const isWiki = (host: string) => /(^|\.)(wikimedia|wikipedia)\.org$/i.test(host);
let lastBusy = false;

/** Public http(s) hosts only (no local / private addresses). */
function allowed(raw: string): URL | null {
  let u: URL;
  try {
    u = new URL(raw);
  } catch {
    return null;
  }
  if (u.protocol !== 'http:' && u.protocol !== 'https:') return null;
  const h = u.hostname.toLowerCase();
  if (
    h === 'localhost' ||
    h.endsWith('.local') ||
    h.endsWith('.internal') ||
    /^(127|10|0)\./.test(h) ||
    /^192\.168\./.test(h) ||
    /^172\.(1[6-9]|2\d|3[01])\./.test(h) ||
    /^169\.254\./.test(h) ||
    h.includes(':') // IPv6 literals
  )
    return null;
  return u;
}

async function get(url: string, tries = 2): Promise<Response | null> {
  let host = '';
  try {
    host = new URL(url).hostname;
  } catch {
    return null;
  }
  for (let i = 0; i < tries; i++) {
    const ac = new AbortController();
    const guard = setTimeout(() => ac.abort(), 12_000);
    try {
      const res = await fetch(url, {
        headers: {
          'User-Agent': isWiki(host) ? WIKI_UA : UA,
          Accept: 'image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8',
          'Accept-Language': 'en',
        },
        redirect: 'follow',
        signal: ac.signal,
      });
      if (res.status === 429) lastBusy = true;
      if ((res.status === 429 || res.status >= 500) && i + 1 < tries) {
        try {
          await res.body?.cancel();
        } catch {
          /* nothing to drain */
        }
        const wait = Math.min(3, Number(res.headers.get('retry-after')) || 0.7);
        await new Promise((r) => setTimeout(r, wait * 1000));
        continue;
      }
      return res;
    } catch {
      if (i + 1 >= tries) return null;
    } finally {
      clearTimeout(guard);
    }
  }
  return null;
}

/** Wikimedia thumbnail → the original file (…/thumb/a/ab/Name.svg/960px-Name.svg.png → …/a/ab/Name.svg). */
function wikimediaOriginal(u: URL): string | null {
  if (u.hostname !== 'upload.wikimedia.org') return null;
  const m = u.pathname.match(/^(\/[^/]+\/[^/]+)\/thumb\/(.+)\/[^/]+$/);
  return m ? `${u.origin}${m[1]}/${m[2]}` : null;
}

const isImage = (ct: string) => /^image\//i.test(ct) || /svg/i.test(ct);

async function readImage(res: Response): Promise<{ body: ArrayBuffer; type: string } | null> {
  const type = (res.headers.get('content-type') ?? '').split(';')[0]!.trim();
  const len = Number(res.headers.get('content-length') ?? 0);
  if (!res.ok || !isImage(type) || len > MAX_BYTES) {
    try {
      await res.body?.cancel();
    } catch {
      /* nothing to drain */
    }
    return null;
  }
  const body = await res.arrayBuffer();
  if (body.byteLength === 0 || body.byteLength > MAX_BYTES) return null;
  return { body, type };
}

/** An image page (ibb.co, blogs…): the picture named by og:image / twitter:image. */
async function imageFromPage(res: Response, base: string): Promise<string | null> {
  const type = res.headers.get('content-type') ?? '';
  if (!res.ok || !/html/i.test(type)) return null;
  const html = (await res.text()).slice(0, 200_000);
  const m =
    html.match(/<meta[^>]+(?:property|name)=["'](?:og:image|twitter:image)(?::src)?["'][^>]+content=["']([^"']+)["']/i) ??
    html.match(/<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["'](?:og:image|twitter:image)["']/i);
  if (!m?.[1]) return null;
  try {
    return new URL(m[1].replace(/&amp;/g, '&'), base).toString();
  } catch {
    return null;
  }
}

async function fetchImage(target: URL): Promise<{ body: ArrayBuffer; type: string } | null> {
  const first = await get(target.toString());
  if (first) {
    const ct = first.headers.get('content-type') ?? '';
    if (first.ok && /html/i.test(ct)) {
      const inner = await imageFromPage(first, target.toString());
      const innerUrl = inner && allowed(inner);
      if (innerUrl) {
        const r = await get(innerUrl.toString());
        const img = r && (await readImage(r));
        if (img) return img;
      }
    } else {
      const img = await readImage(first);
      if (img) return img;
    }
  }
  // Wikimedia refuses some thumbnail sizes (and rate-limits them): use the original file.
  const original = wikimediaOriginal(target);
  if (original) {
    const r = await get(original);
    const img = r && (await readImage(r));
    if (img) return img;
  }
  // A plain-http host that also answers on https (or the other way round).
  if (target.protocol === 'http:') {
    const r = await get(target.toString().replace(/^http:/, 'https:'), 1);
    const img = r && (await readImage(r));
    if (img) return img;
  }
  return null;
}

type EdgeCache = { match(req: Request): Promise<Response | undefined>; put(req: Request, res: Response): Promise<void> };
const edgeCache = (): EdgeCache | null => {
  const c = (globalThis as unknown as { caches?: { default?: EdgeCache } }).caches;
  return c?.default ?? null;
};

export const Route = createFileRoute('/api/public/img')({
  server: {
    handlers: {
      GET: async ({ request }) => {
        const url = new URL(request.url);
        const target = allowed(url.searchParams.get('u') ?? '');
        if (!target) return new Response('Bad image link', { status: 400 });

        const cache = edgeCache();
        const key = new Request(`${url.origin}/api/public/img?u=${encodeURIComponent(target.toString())}`);
        if (cache) {
          const hit = await cache.match(key).catch(() => undefined);
          if (hit) return hit;
        }

        lastBusy = false;
        const img = await fetchImage(target);
        const res = img
          ? new Response(img.body, {
              status: 200,
              headers: {
                'Content-Type': img.type,
                'Cache-Control': `public, max-age=${OK_TTL}`,
                'Access-Control-Allow-Origin': '*',
                'X-Content-Type-Options': 'nosniff',
                // SVGs are shown as images only, never run as documents.
                'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'; sandbox",
              },
            })
          : new Response('Image not found', {
              status: 404,
              headers: {
                'Cache-Control': `public, max-age=${lastBusy ? BUSY_TTL : MISS_TTL}`,
                'Access-Control-Allow-Origin': '*',
              },
            });
        if (cache) await cache.put(key, res.clone()).catch(() => {});
        return res;
      },
    },
  },
});
