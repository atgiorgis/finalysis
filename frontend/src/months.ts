/**
 * Calendar helpers for `yyyy-MM` months: stepping and listing months, and a month's first and last day. Calendar
 * arithmetic only, never money (the screens' no-arithmetic rule is about API figures, docs/plans/phase-7.md §6.2).
 */

const YEAR_MONTH = /^(\d{4})-(\d{2})$/

function parts(month: string): [number, number] {
  const match = YEAR_MONTH.exec(month)
  if (!match) {
    throw new Error(`not a yyyy-MM month: ${month}`)
  }
  return [Number(match[1]), Number(match[2])]
}

function format(year: number, month: number): string {
  return `${String(year).padStart(4, '0')}-${String(month).padStart(2, '0')}`
}

export function isYearMonth(value: string | null | undefined): value is string {
  const match = value ? YEAR_MONTH.exec(value) : null
  return !!match && Number(match[2]) >= 1 && Number(match[2]) <= 12
}

/** `month` moved by `count` months (negative goes back). */
export function addMonths(month: string, count: number): string {
  const [year, m] = parts(month)
  const index = year * 12 + (m - 1) + count
  return format(Math.floor(index / 12), (index % 12) + 1)
}

/** Every month from `from` to `to`, both included; empty when `from` is after `to`. */
export function monthsBetween(from: string, to: string): string[] {
  const out: string[] = []
  for (let month = from; month <= to; month = addMonths(month, 1)) {
    out.push(month)
  }
  return out
}

/** The month's first and last day, `yyyy-MM-dd`, for filters that take dates. */
export function monthDays(month: string): { first: string; last: string } {
  const [year, m] = parts(month)
  const last = new Date(Date.UTC(year, m, 0)).getUTCDate()
  return { first: `${month}-01`, last: `${month}-${String(last).padStart(2, '0')}` }
}
