/**
 * Opaque stream tokens.
 *
 * Provider stream URLs contain the Xtream username/password, so they must never
 * reach the browser. Every playable URL is encrypted (AES-GCM) with a
 * server-only secret and handed to the client as an opaque token; the playback
 * route decrypts it again and proxies the bytes through the Andam relay.
 */

const enc = new TextEncoder();
const dec = new TextDecoder();

// Keep the finished key, never a shared promise: a Worker request cannot wait on a promise
// another request created (handle_cross_request_promise_resolution).
let key: CryptoKey | null = null;

async function getKey(): Promise<CryptoKey> {
  if (key) return key;
  const secret = process.env['STREAM_TOKEN_SECRET'];
  if (!secret) throw new Error('STREAM_TOKEN_SECRET is not configured');
  const raw = await crypto.subtle.digest('SHA-256', enc.encode(secret));
  key = await crypto.subtle.importKey('raw', raw, { name: 'AES-GCM' }, false, ['encrypt', 'decrypt']);
  return key;
}

function toBase64Url(bytes: Uint8Array): string {
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function fromBase64Url(value: string): Uint8Array {
  const padded = value.replace(/-/g, '+').replace(/_/g, '/');
  const bin = atob(padded + '='.repeat((4 - (padded.length % 4)) % 4));
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/** Encrypts an upstream provider URL into an opaque, expiring token. */
export async function sealUrl(url: string, ttlSeconds = 60 * 60 * 12): Promise<string> {
  const key = await getKey();
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const payload = JSON.stringify({ u: url, x: Date.now() + ttlSeconds * 1000 });
  const cipher = new Uint8Array(
    await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, enc.encode(payload)),
  );
  const out = new Uint8Array(iv.length + cipher.length);
  out.set(iv, 0);
  out.set(cipher, iv.length);
  return toBase64Url(out);
}

/** Decrypts a token back into the upstream provider URL, or null when invalid/expired. */
export async function openUrl(token: string): Promise<string | null> {
  try {
    const key = await getKey();
    const bytes = fromBase64Url(token);
    const iv = bytes.slice(0, 12);
    const cipher = bytes.slice(12);
    const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, key, cipher);
    const data = JSON.parse(dec.decode(plain)) as { u?: string; x?: number };
    if (!data.u || typeof data.x !== 'number' || data.x < Date.now()) return null;
    return data.u;
  } catch {
    return null;
  }
}
