/**
 * Short-lived in-memory cache for server reads that every request repeats (who the viewer is,
 * the provider list, admin overrides, the cached playlist). A worker instance keeps it between
 * requests, so starting a channel or switching sections no longer waits for the same database
 * rows again and again. Values live for seconds to minutes; admin changes show up after the TTL.
 *
 * Only finished values are shared. Cloudflare Workers cannot let one request wait on a promise
 * another request created (with `handle_cross_request_promise_resolution` it fails with "This
 * promise was created by a request that has since ended"), so a read still in flight is never
 * handed to a second request: that request reads for itself. A failed read is not kept.
 */
type Entry = { at: number; value: unknown };

export function memoStore(max = 64) {
  const store = new Map<string, Entry>();
  const put = (key: string, value: unknown) => {
    store.delete(key);
    store.set(key, { at: Date.now(), value });
    // Oldest first: Map keeps insertion order.
    while (store.size > max) {
      const oldest = store.keys().next().value;
      if (oldest === undefined) break;
      store.delete(oldest);
    }
  };
  return {
    async get<T>(key: string, ttlMs: number, load: () => Promise<T>): Promise<T> {
      const hit = store.get(key);
      if (hit && Date.now() - hit.at < ttlMs) return hit.value as T;
      const value = await load();
      put(key, value);
      return value;
    },
    set<T>(key: string, value: T) {
      put(key, value);
    },
    delete(key: string) {
      store.delete(key);
    },
  };
}
