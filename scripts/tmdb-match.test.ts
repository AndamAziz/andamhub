// Offline checks of the TMDB matcher against a fake TMDB (no network, no key):
//   bun test scripts/tmdb-match.test.ts
// The real-data run is scripts/tmdb-check.ts.
import { describe, expect, test } from 'bun:test';
import { cleanTitle, findTmdb } from '../src/lib/tmdb.server';
import { extractTmdbId, norm, queryVariants, unify } from '../src/lib/tmdb-match';
import { makeFakeTmdb } from './tmdb-fake';

const find = async (type: 'movie' | 'series', raw: string, group = '') => {
  const c = cleanTitle(raw);
  if (c.tmdb) return { id: c.tmdb.id, via: 'id' as const, calls: 0 };
  const log: string[] = [];
  const r = await findTmdb(type, c.title, c.year, group, makeFakeTmdb(log));
  return { id: r.match?.id ?? 0, via: 'search' as const, calls: log.length, match: r.match };
};

describe('explicit TMDB ids', () => {
  test.each([
    ['Cease Fire tmdb:44074', 44074, undefined, 'Cease Fire'],
    ['Cease Fire tmdbid=44074', 44074, undefined, 'Cease Fire'],
    ['Cease Fire {tmdb-44074}', 44074, undefined, 'Cease Fire'],
    ['Cease Fire [tmdb=44074]', 44074, undefined, 'Cease Fire'],
    ['Cease Fire tmdb-id="44074"', 44074, undefined, 'Cease Fire'],
    ['Setayesh tmdb:tv/94146', 94146, 'series', 'Setayesh'],
    ['Cease Fire (2006) {TMDB-44074}', 44074, undefined, 'Cease Fire'],
  ])('%s', (raw, id, type, title) => {
    const e = extractTmdbId(raw);
    expect(e.explicit?.id).toBe(id);
    expect(e.explicit?.type).toBe(type);
    const c = cleanTitle(raw);
    expect(c.tmdb?.id).toBe(id);
    expect(c.title).toBe(title);
  });
  test('no id → null, digits in a title are kept', () => {
    expect(extractTmdbId('Blade Runner 2049').explicit).toBeNull();
    expect(cleanTitle('Blade Runner 2049').title).toBe('Blade Runner 2049');
  });
});

describe('cleaning and normalising', () => {
  test('quality tags, brackets, year', () => {
    expect(cleanTitle('The Lizard (2004) 1080p WEB-DL [HD]')).toMatchObject({ title: 'The Lizard', year: '2004' });
    expect(cleanTitle('The Lizard [2004]')).toMatchObject({ title: 'The Lizard', year: '2004' });
    expect(cleanTitle('The Lizard 2004')).toMatchObject({ title: 'The Lizard', year: '2004' });
    expect(cleanTitle('Setayesh - S01E01').title).toBe('Setayesh');
    expect(cleanTitle('Setayesh S01E01 Dubbed').title).toBe('Setayesh');
  });
  test('Persian / Arabic letters and digits are unified', () => {
    expect(norm('مارمولك')).toBe(norm('مارمولک'));
    expect(norm('آتش بس')).toBe(norm('اتش بس'));
    expect(norm('بي‌خانمان')).toBe(norm('بیخانمان'));
    expect(unify('فیلم ۲۰۰۴ ٢٠٠٤')).toBe('فیلم 2004 2004');
    expect(cleanTitle('مارمولک (۲۰۰۴)')).toMatchObject({ title: 'مارمولک', year: '2004' });
  });
  test('variants: article, hyphen, joined, transliteration', () => {
    const v = queryVariants('The Night-Bus');
    expect(v).toContain('Night-Bus');
    expect(v).toContain('Night Bus');
    expect(queryVariants('Marmoulak').map((x) => x.toLowerCase())).toEqual(expect.arrayContaining(['marmulak', 'marmoolak', 'marmolak']));
  });
});

describe('movies', () => {
  test.each([
    ['مارمولک', 75404],
    ['Marmoulak', 75404],
    ['The Lizard (2004)', 75404],
    ['آتش بس', 44074],
    ['Cease Fire (2006)', 44074],
    ['بگذار زندگی کنم', 553972],
    ['Let Me Live (1986)', 553972],
    ['به من نگاه کن', 1045181],
    ['اتوبوس شب', 363946],
    ['Night Bus (2007)', 363946],
    ['Wedding Dinner', 871708],
    ['Cease Fire tmdb:44074', 44074],
  ])('%s → %d', async (raw, id) => {
    expect((await find('movie', raw)).id).toBe(id);
  });
  test('"The Lizard" alone is not the 2016 French short nor the 1972 Chinese film', async () => {
    const r = await find('movie', 'The Lizard');
    expect(r.id).toBe(75404);
  });
  test('"Marmoulak" is found through the alternative title, with a reason', async () => {
    const r = await find('movie', 'Marmoulak');
    expect(r.match?.reason).toContain('exact alternative title');
  });
  test('a misspelled Latin name still finds it through a spelling variant', async () => {
    expect((await find('movie', 'Marmulak')).id).toBe(75404);
  });
});

describe('series', () => {
  test.each([
    ['Setayesh', 94146],
    ['Prophet Joseph', 70802],
    ['Lighter Than Darkness', 58247],
    ['Homeless', 228743],
    ['Bachelors', 293151],
    ['The Return of Iljimae', 31598],
    ['Setayesh - S01E01', 94146],
  ])('%s → %d', async (raw, id) => {
    expect((await find('series', raw)).id).toBe(id);
  });
});

describe('no wrong film', () => {
  test('nothing similar → no match', async () => {
    expect((await find('movie', 'Zxqwv Unknown Title')).id).toBe(0);
  });
  test('a wrong-year exact title does not beat the right one, and fails alone in a Persian group', async () => {
    const r = await find('movie', 'The Lizard King (1999)');
    expect(r.match?.id ?? 0).not.toBe(75404);
  });
  test('TMDB errors → failed, not a final "no match"', async () => {
    const r = await findTmdb('movie', 'Anything', '', '', (async () => null) as never);
    expect(r).toEqual({ match: null, failed: true });
  });
  test('the other media type is the last resort, exact only', async () => {
    const r = await findTmdb('movie', 'Setayesh', '', '', makeFakeTmdb());
    expect(r.match).toMatchObject({ id: 94146, type: 'series' });
  });
  test('year → no year retry (a wrong provider year still finds the film)', async () => {
    expect((await find('movie', 'Cease Fire (2010)', 'Persian')).id).toBe(44074);
  });
});
