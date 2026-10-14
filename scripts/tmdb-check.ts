/**
 * Runs the detail-page logic (mediaDetails: name cleaning → scored TMDB search → artwork choice)
 * against real TMDB data and prints what each playlist name resolves to.
 *
 *   TMDB_API_KEY=… bun scripts/tmdb-check.ts
 *
 * A tiny local server stands in for the Xtream provider (it answers `{}`), so only the
 * list name reaches the matcher, exactly like a provider whose own info has no tmdb id.
 * Also checks that "Clear cache" (refresh) makes the next call fetch again.
 */
import { mediaDetails, clearDetailsCache, type MediaDetails } from '../src/lib/tmdb.server';

const server = Bun.serve({ port: 0, fetch: () => Response.json({}) });
const source = {
  id: 'check',
  slug: 'check',
  name: 'check',
  base_url: `http://127.0.0.1:${server.port}`,
  username: 'u',
  password: 'p',
} as const;

const CASES: Array<[string, 'movie' | 'series', number, string?]> = [
  ['آتش بس', 'movie', 44074],
  ['Marmoulak', 'movie', 75404],
  ['بگذار زندگی کنم', 'movie', 553972],
  ['به من نگاه کن', 'movie', 1045181],
  ['اتوبوس شب', 'movie', 363946],
  ['Wedding Dinner', 'movie', 871708],
  ['Cease Fire (2006)', 'movie', 44074],
  ['The Lizard (2004)', 'movie', 75404],
  ['Setayesh', 'series', 94146],
  ['Prophet Joseph', 'series', 70802],
  ['Lighter Than Darkness', 'series', 58247],
  ['Homeless', 'series', 228743],
  ['Bachelors', 'series', 293151],
  ['The Return of Iljimae', 'series', 31598],
];

let bad = 0;
const row = (...c: string[]) => console.log(c.join(' | '));
row('name', 'want', 'got id', 'year', 'poster', 'backdrop', 'ok');
let n = 0;
for (const [name, type, want, group] of CASES) {
  const d: MediaDetails = await mediaDetails(source, type, String(++n), 'en', { name, group });
  const ok = d.tmdbId === want;
  if (!ok) bad++;
  row(
    name,
    `${type} ${want}`,
    d.tmdbId ? String(d.tmdbId) : 'no match',
    d.year || '-',
    d.poster ? d.poster.replace('https://image.tmdb.org/t/p/', '') : '-',
    d.backdrop ? d.backdrop.replace('https://image.tmdb.org/t/p/', '') : '-',
    ok ? 'OK' : 'FAIL',
  );
}

// Clear cache: the same call is answered from memory, and fetched again after clearing / refresh.
const hits = { n: 0 };
const realFetch = globalThis.fetch;
globalThis.fetch = ((...a: Parameters<typeof fetch>) => {
  if (String(a[0]).includes('api.themoviedb.org')) hits.n++;
  return realFetch(...a);
}) as typeof fetch;
await mediaDetails(source, 'movie', '1', 'en', { name: 'آتش بس' });
const cached = hits.n;
clearDetailsCache();
await mediaDetails(source, 'movie', '1', 'en', { name: 'آتش بس' });
const afterClear = hits.n;
await mediaDetails(source, 'movie', '1', 'en', { name: 'آتش بس', refreshedAt: Date.now() });
console.log(`clear cache: cached call made ${cached} TMDB requests, after clear ${afterClear - cached}, refresh ${hits.n - afterClear}`);
if (cached !== 0 || afterClear === 0 || hits.n === afterClear) bad++;

server.stop();
console.log(bad ? `${bad} FAILED` : 'ALL OK');
process.exit(bad ? 1 : 0);
