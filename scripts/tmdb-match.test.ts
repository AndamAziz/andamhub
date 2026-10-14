// Offline checks of the TMDB matcher with hand-made search results (no network, no key):
//   bun test scripts/tmdb-match.test.ts
// The real-data run is scripts/tmdb-check.ts.
import { describe, expect, test } from 'bun:test';
import { cleanTitle, findTmdb, MIN_CONFIDENCE, type SearchHit } from '../src/lib/tmdb.server';

type Fetcher = Parameters<typeof findTmdb>[4];
/** A fake TMDB: answers by media type, honouring the year filter like the real API. */
const fake = (db: { movie?: SearchHit[]; tv?: SearchHit[] }): { f: Fetcher; calls: string[] } => {
  const calls: string[] = [];
  const f = (async (path: string, q: Record<string, string> = {}) => {
    calls.push(`${path}?${new URLSearchParams(q)}`);
    const list = (path.includes('/movie') ? db.movie : db.tv) ?? [];
    const y = q['year'] ?? q['first_air_date_year'];
    return { results: y ? list.filter((h) => (h.release_date || h.first_air_date || '').startsWith(y)) : list };
  }) as unknown as Fetcher;
  return { f, calls };
};

const ceaseFire85: SearchHit = { id: 1, title: 'Cease Fire', original_title: 'Cease Fire', original_language: 'en', release_date: '1985-09-01', popularity: 40, vote_count: 80 };
const ceaseFire06: SearchHit = { id: 44074, title: 'Cease Fire', original_title: 'آتش بس', original_language: 'fa', release_date: '2006-01-01', popularity: 2, vote_count: 5 };
const lizardShort: SearchHit = { id: 2, title: 'The Lizard', original_title: 'Le Lézard', original_language: 'fr', release_date: '2016-01-01', popularity: 5, vote_count: 3 };
const lizard72: SearchHit = { id: 3, title: 'The Lizard', original_title: '蜥蜴', original_language: 'zh', release_date: '1972-01-01', popularity: 4, vote_count: 3 };
const lizard04: SearchHit = { id: 75404, title: 'The Lizard', original_title: 'مارمولک', original_language: 'fa', release_date: '2004-01-01', popularity: 3, vote_count: 9 };

describe('year in the name', () => {
  test('parsed and removed', () => {
    expect(cleanTitle('Cease Fire (2006)')).toEqual({ title: 'Cease Fire', year: '2006' });
    expect(cleanTitle('The Lizard [2004]')).toEqual({ title: 'The Lizard', year: '2004' });
    expect(cleanTitle('The Lizard 2004')).toEqual({ title: 'The Lizard', year: '2004' });
    expect(cleanTitle('Setayesh - S01E01').title).toBe('Setayesh');
  });
  test('the year picks the Persian film even though the US one is far more popular', async () => {
    const { f } = fake({ movie: [ceaseFire85, ceaseFire06] });
    expect((await findTmdb('movie', 'Cease Fire', '2006', '', f)).match?.id).toBe(44074);
    const lz = fake({ movie: [lizardShort, lizard72, lizard04] });
    expect((await findTmdb('movie', 'The Lizard', '2004', '', lz.f)).match?.id).toBe(75404);
  });
});

describe('no year', () => {
  test('Persian group prefers original_language fa over a more popular US film', async () => {
    const { f } = fake({ movie: [ceaseFire85, ceaseFire06] });
    expect((await findTmdb('movie', 'Cease Fire', '', 'Persian Movies', f)).match?.id).toBe(44074);
  });
  test('Persian script is matched through the original title', async () => {
    const { f } = fake({ movie: [ceaseFire85, ceaseFire06] });
    const r = await findTmdb('movie', 'آتش بس', '', '', f);
    expect(r.match?.id).toBe(44074);
  });
  test('Persian script: TMDB found it by translation, original is fa → accepted; unrelated language is not', async () => {
    const fa: SearchHit = { id: 553972, title: 'Let Me Live', original_title: 'Bezar Zendegi Konam', original_language: 'fa', release_date: '1986-01-01' };
    const en: SearchHit = { id: 9, title: 'Let Me Live', original_title: 'Let Me Live', original_language: 'en', release_date: '1939-01-01' };
    expect((await findTmdb('movie', 'بگذار زندگی کنم', '', '', fake({ movie: [fa] }).f)).match?.id).toBe(553972);
    expect((await findTmdb('movie', 'بگذار زندگی کنم', '', '', fake({ movie: [en] }).f)).match).toBeNull();
  });
});

describe('low confidence', () => {
  test('an unrelated result is never shown', async () => {
    const other: SearchHit = { id: 7, title: 'Totally Different', original_title: 'Totally Different', original_language: 'en', release_date: '2010-01-01', popularity: 90, vote_count: 9000 };
    const { f } = fake({ movie: [other] });
    const r = await findTmdb('movie', 'Marmoulak', '', '', f);
    expect(r.match).toBeNull();
    expect(r.failed).toBe(false);
  });
  test('wrong year only (exact name, 20 years off) stays under the bar', async () => {
    const { f } = fake({ movie: [ceaseFire85] });
    const r = await findTmdb('movie', 'Cease Fire', '2006', '', f);
    expect(r.match === null || r.match.score >= MIN_CONFIDENCE).toBe(true);
    expect(r.match?.strong ?? false).toBe(false);
  });
  test('TMDB errors are reported as failed, not as a final "no match"', async () => {
    const r = await findTmdb('movie', 'Anything', '', '', (async () => null) as unknown as Fetcher);
    expect(r).toEqual({ match: null, failed: true });
  });
});

describe('retries', () => {
  test('without the year when the year finds nothing', async () => {
    const wrongYear = { ...ceaseFire06, release_date: '2007-01-01' };
    const { f, calls } = fake({ movie: [wrongYear] });
    expect((await findTmdb('movie', 'Cease Fire', '2006', 'Persian', f)).match?.id).toBe(44074);
    expect(calls.length).toBe(2);
    expect(calls[0]).toContain('year=2006');
    expect(calls[1]).not.toContain('year=');
  });
  test('series use first_air_date_year, and the other media type is the last resort (exact only)', async () => {
    const show: SearchHit = { id: 94146, name: 'Setayesh', original_name: 'Setayesh', original_language: 'fa', first_air_date: '2020-01-01' };
    const a = fake({ tv: [show] });
    expect((await findTmdb('series', 'Setayesh', '2020', '', a.f)).match?.id).toBe(94146);
    expect(a.calls[0]).toContain('first_air_date_year=2020');
    const b = fake({ tv: [show] }); // listed as a movie but TMDB only has the series
    const r = await findTmdb('movie', 'Setayesh', '', '', b.f);
    expect(r.match).toMatchObject({ id: 94146, type: 'series' });
  });
});
