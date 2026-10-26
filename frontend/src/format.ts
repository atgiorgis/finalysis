/**
 * Display formatting for values the API returns as strings (money, percents, months, dates).
 *
 * String handling only: a value is never parsed into a number, so formatting can't drift or do arithmetic
 * (docs/plans/phase-7.md §4.3). Nothing rounds except `formatCost`, which rounds an estimated cost to the cent on its
 * digits. A value that isn't in the expected shape is shown as it came.
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
 * An estimated AI cost (`"0.029515"`, six decimals) to the cent, half up: `$0.03`. The one rounding in this file, for
 * costs only: they are estimates from list prices, labelled so, and the exact value stays in the API. Done on the
 * digits, never through a binary number. A cost above zero but under half a cent shows as `under $0.01`, so it never
 * reads as free. Null shows as a dash.
 */
export function formatCost(value: string | null | undefined): string {
  if (value === null || value === undefined) {
    return EMPTY
  }
  const match = DECIMAL.exec(value)
  if (!match || match[1]) {
    return value
  }
  const [, , whole, fraction = ''] = match
  const digits = (fraction + '00').slice(0, 2)
  const roundUp = fraction.length > 2 && fraction[2] >= '5'
  let cents = (whole + digits).replace(/^0+(?=\d)/, '')
  if (roundUp) {
    cents = increment(cents)
  }
  cents = cents.padStart(3, '0')
  const rounded = `${cents.slice(0, -2)}.${cents.slice(-2)}`
  if (/^0+\.00$/.test(rounded) && !isZero(whole, fraction || undefined)) {
    return 'under $0.01'
  }
  return formatMoney(rounded)
}

/** A digit string plus one, carrying: `"099"` → `"100"`. */
function increment(digits: string): string {
  const chars = digits.split('')
  for (let i = chars.length - 1; i >= 0; i--) {
    if (chars[i] !== '9') {
      chars[i] = String.fromCharCode(chars[i].charCodeAt(0) + 1)
      return chars.join('')
    }
    chars[i] = '0'
  }
  return `1${chars.join('')}`
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

/** A statement's period: `May 1, 2026 to May 27, 2026`, as the transaction sheet writes it. */
export function formatPeriod(start: string | null | undefined, end: string | null | undefined): string {
  return `${formatDate(start, true)} to ${formatDate(end, true)}`
}

/** A count with thousands separators, by string handling: `48213` → `48,213`. */
export function formatCount(value: number | string): string {
  const digits = String(value)
  return /^\d+$/.test(digits) ? group(digits) : digits
}

/** A file's size as the browser reports it, in bytes: `48,213 bytes`, `1 byte`. No rounding into KB or MB. */
export function formatBytes(size: number): string {
  return size === 1 ? '1 byte' : `${formatCount(size)} bytes`
}
