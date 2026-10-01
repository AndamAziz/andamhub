/**
 * Reads every row of a query. The database answers at most 1000 rows per request, so
 * lists (users, codes, redemptions…) are fetched page by page until the last page —
 * nothing is ever cut off, however large the list grows.
 */
type Page<T> = PromiseLike<{ data: T[] | null; error: { message: string } | null }>;

export async function allRows<T>(page: (from: number, to: number) => Page<T>, size = 1000): Promise<T[]> {
  const out: T[] = [];
  for (let from = 0; ; from += size) {
    const { data, error } = await page(from, from + size - 1);
    if (error) throw new Error(error.message);
    const rows = data ?? [];
    out.push(...rows);
    if (rows.length < size) break;
  }
  return out;
}

/** Splits a long id list so `.in(...)` filters stay within URL limits. */
export function chunks<T>(list: T[], size = 150): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < list.length; i += size) out.push(list.slice(i, i + size));
  return out;
}
