/**
 * Display formatting for values the API returns as strings (money, percents, months, dates).
 *
 * String handling only: a value is never parsed into a number, so formatting can't round, drift or do arithmetic
 * (docs/plans/phase-7.md §4.3). A value that isn't in the expected shape is shown as it came.
 */

const MINUS = '−'
const EMPTY = '—'

const DECIMAL = /^(-?)(\d+)(?:\.(\d+))?$/
const YEAR_MONTH = /^(\d{4})-(\d{2})$/
const DATE = /^(\d{4})-(\d{2})-(\d{2})$/

const MONTHS = [
  'January',
  'February',
  'March',
  'April',
  'May',
  'June',
  'July',
  'August',
  'September',
  'October',
  'November',
  'December',
]

export interface SignOptions {
  /**
   * `signed` (default): a minus for negative values only. `magnitude`: no sign at all (a spending figure shown as how
   * much went out). `change`: a plus for positive values too, for differences.
   */
  sign?: 'signed' | 'magnitude' | 'change'
}

function group(digits: string): string {
  return digits.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

function isZero(whole: string, fraction: string | undefined): boolean {
  return /^0+$/.test(whole) && (fraction === undefined || /^0+$/.test(fraction))
}

function signOf(minus: string, whole: string, fraction: string | undefined, options: SignOptions): string {
  const mode = options.sign ?? 'signed'
  if (mode === 'magnitude' || isZero(whole, fraction)) {
    return ''
  }
  if (minus) {
    return MINUS
  }
  return mode === 'change' ? '+' : ''
}

/** `"-1850.00"` → `−$1,850.00`; the decimals are kept as returned. Null shows as a dash. */
export function formatMoney(value: string | null | undefined, options: SignOptions = {}): string {
  if (value === null || value === undefined) {
    return EMPTY
  }
  const match = DECIMAL.exec(value)
  if (!match) {
    return value
  }
  const [, minus, whole, fraction] = match
  return `${signOf(minus, whole, fraction, options)}$${group(whole)}${fraction === undefined ? '' : `.${fraction}`}`
}

/**
 * A spending figure, signed as stored: money out as its size (`"-74.09"` → `$74.09`), money in with a plus (`"85.00"` →
 * `+$85.00`, a category whose refunds outweigh its charges), so money in is never read as spent.
 */
export function formatSpent(value: string | null | undefined): string {
  return value && !value.startsWith('-') ? formatMoney(value, { sign: 'change' }) : formatMoney(value, { sign: 'magnitude' })
}

/** `"93.8"` → `93.8%`. Null shows as a dash. */
export function formatPercent(value: string | null | undefined, options: SignOptions = {}): string {
  if (value === null || value === undefined) {
    return EMPTY
  }
  const match = DECIMAL.exec(value)
  if (!match) {
    return value
  }
  const [, minus, whole, fraction] = match
  return `${signOf(minus, whole, fraction, options)}${group(whole)}${fraction === undefined ? '' : `.${fraction}`}%`
}

/** `"2026-10"` → `October 2026` (`long`), `October` (`name`) or `Oct` (`short`). */
export function formatMonth(value: string | null | undefined, style: 'long' | 'name' | 'short' = 'long'): string {
  if (!value) {
    return EMPTY
  }
  const match = YEAR_MONTH.exec(value)
  const index = match ? Number(match[2]) - 1 : -1
  if (!match || index < 0 || index > 11) {
    return value
  }
  if (style === 'long') {
    return `${MONTHS[index]} ${match[1]}`
  }
  return style === 'name' ? MONTHS[index] : MONTHS[index].slice(0, 3)
}

/** `"2026-10-04"` → `Oct 4`, or `Oct 4, 2026` with the year. */
export function formatDate(value: string | null | undefined, withYear = false): string {
  if (!value) {
    return EMPTY
  }
  const match = DATE.exec(value)
  const index = match ? Number(match[2]) - 1 : -1
  if (!match || index < 0 || index > 11) {
    return value
  }
  const day = match[3].replace(/^0/, '')
  return `${MONTHS[index].slice(0, 3)} ${day}${withYear ? `, ${match[1]}` : ''}`
}
