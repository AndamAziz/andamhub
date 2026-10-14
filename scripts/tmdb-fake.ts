/** A small fake TMDB (search + details + alternative titles) for the offline matcher tests. */
import type { SearchHit } from '../src/lib/tmdb-match';

type Row = SearchHit & { kind: 'movie' | 'series'; aliases?: string[]; overview?: string };
const m = (id: number, title: string, original: string, lang: string, date: string, pop: number, votes: number, aliases: string[] = []): Row => ({
  kind: 'movie', id, title, original_title: original, original_language: lang, release_date: date, popularity: pop, vote_count: votes, aliases,
});
const t = (id: number, name: string, original: string, lang: string, date: string, pop: number, votes: number, aliases: string[] = []): Row => ({
  kind: 'series', id, name, original_name: original, original_language: lang, first_air_date: date, popularity: pop, vote_count: votes, aliases,
});

export const DB: Row[] = [
  m(75404, 'The Lizard', 'مارمولک', 'fa', '2004-01-01', 3, 40, ['Marmoulak', 'Marmoolak']),
  m(9001, 'The Lizard', 'Le Lézard', 'fr', '2016-01-01', 6, 4),
  m(9002, 'The Lizard', '蜥蜴', 'zh', '1972-01-01', 4, 5),
  m(44074, 'Cease Fire', 'آتش بس', 'fa', '2006-01-01', 2, 12, ['Atash Bas']),
  m(9003, 'Cease Fire', 'Cease Fire', 'en', '1985-09-01', 40, 80),
  m(553972, 'Let Me Live', 'بگذار زندگی کنم', 'fa', '1986-01-01', 1, 3, ['Bezar Zendegi Konam']),
  m(9004, 'Let Me Live', 'Let Me Live', 'en', '1939-01-01', 8, 30),
  m(1045181, 'Look at Me', 'به من نگاه کن', 'fa', '2002-01-01', 1, 2),
  m(9005, 'Look at Me', 'Regarde-moi', 'fr', '2004-01-01', 20, 300),
  m(363946, 'Night Bus', 'اتوبوس شب', 'fa', '2007-01-01', 2, 8, ['Otobus-e Shab']),
  m(9006, 'Night Bus', 'Night Bus', 'en', '1991-01-01', 15, 60),
  m(871708, 'Wedding Dinner', 'شام عروسی', 'fa', '2006-01-01', 1, 2),
  m(9007, 'Wedding Dinner', 'Wedding Dinner', 'en', '2019-01-01', 12, 70),
  m(9008, 'The Lizard King', 'The Lizard King', 'en', '2010-01-01', 30, 200),
  t(94146, 'Setayesh', 'ستایش', 'fa', '2020-01-01', 2, 5),
  t(70802, 'Prophet Joseph', 'یوسف پیامبر', 'fa', '2008-01-01', 5, 40),
  t(58247, 'Lighter Than Darkness', 'سبک‌تر از تاریکی', 'fa', '2013-01-01', 2, 6),
  t(228743, 'Homeless', 'بی‌خانمان', 'fa', '2023-01-01', 2, 3),
  t(9009, 'Homeless', 'Homeless', 'en', '2012-01-01', 25, 80),
  t(293151, 'Bachelors', 'مجردها', 'fa', '2024-01-01', 2, 3),
  t(9010, 'Bachelors', 'Bachelors', 'en', '2005-01-01', 18, 50),
  t(31598, 'The Return of Iljimae', '일지매', 'ko', '2009-01-01', 6, 30),
];

// Like TMDB: letters are matched as written (آ and ا are different), only case is ignored.
const words = (s: string) => s.replace(/\u200c/g, ' ').toLowerCase().split(/[^\p{L}\p{N}]+/u).filter(Boolean);

/** Like TMDB search: every query word is in one of the title's / original title's / alias' words. */
export function makeFakeTmdb(log: string[] = []) {
  return (async (path: string, q: Record<string, string> = {}) => {
    log.push(`${path}?${new URLSearchParams(q).toString()}`);
    const sm = /^\/search\/(movie|tv)$/.exec(path);
    if (sm) {
      const kind = sm[1] === 'movie' ? 'movie' : 'series';
      const qw = words(q['query'] ?? '');
      const year = q['year'] ?? q['first_air_date_year'];
      const res = DB.filter((r) => r.kind === kind)
        .filter((r) => {
          const names = [r.title, r.name, r.original_title, r.original_name, ...(q['language'] === 'fa-IR' ? [] : [])].filter(Boolean) as string[];
          // aliases are searchable too (as on TMDB), the first words as well
          const all = [...names, ...(r.aliases ?? [])].map(words);
          return qw.length > 0 && all.some((ws) => qw.every((w) => ws.includes(w)));
        })
        .filter((r) => !year || (r.release_date || r.first_air_date || '').startsWith(year))
        .sort((a, b) => (b.popularity ?? 0) - (a.popularity ?? 0));
      return { results: res.map(({ kind: _k, aliases: _a, overview: _o, ...hit }) => hit) };
    }
    const dm = /^\/(movie|tv)\/(\d+)$/.exec(path);
    if (dm) {
      const row = DB.find((r) => r.id === Number(dm[2]) && r.kind === (dm[1] === 'movie' ? 'movie' : 'series'));
      if (!row) return null; // 404
      const alt = (row.aliases ?? []).map((title) => ({ title }));
      return {
        id: row.id, title: row.title, name: row.name, original_title: row.original_title, original_name: row.original_name,
        release_date: row.release_date, first_air_date: row.first_air_date, overview: '', genres: [], vote_average: 6,
        poster_path: row.id % 2 ? `/p${row.id}.jpg` : null,
        backdrop_path: row.id % 3 ? `/b${row.id}.jpg` : null,
        images: { posters: [{ file_path: `/ip${row.id}.jpg` }], backdrops: [] },
        alternative_titles: dm[1] === 'movie' ? { titles: alt } : { results: alt },
        translations: { translations: [{ iso_639_1: 'en', data: { overview: `en story ${row.id}` } }] },
        credits: { cast: [], crew: [] }, videos: { results: [] },
      };
    }
    return null;
  }) as never;
}
