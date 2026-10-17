/**
 * Short-lived in-memory cache for server reads that every request repeats (who the viewer is,
 * the provider list, admin overrides, the cached playlist). A worker instance keeps it between
 * requests, so starting a channel or switching sections no longer waits for the same database
 * rows again and again. Values live for seconds to minutes; admin changes show up after the TTL.
 *
 * The promise itself is cached, so requests that arrive together share one read; a failed read
 * is forgotten at once.
 */
type Entry = { at: number; value: Promise<unknown> };

export function memoStore(max = 64) {
  const store = new Map<string, Entry>();
  return {
    get<T>(key: string, ttlMs: number, load: () => Promise<T>): Promise<T> {
      const hit = store.get(key);
      if (hit && Date.now() - hit.at < ttlMs) return hit.value as Promise<T>;
      const value = load();
      store.set(key, { at: Date.now(), value });
      value.catch(() => {
        if (store.get(key)?.value === value) store.delete(key);
      });
      // Oldest first: Map keeps insertion order.
      while (store.size > max) {
        const oldest = store.keys().next().value;
        if (oldest === undefined) break;
        store.delete(oldest);
      }
      return value;
    },
    set<T>(key: string, value: T) {
      store.set(key, { at: Date.now(), value: Promise.resolve(value) });
    },
    delete(key: string) {
      store.delete(key);
    },
  };
}
