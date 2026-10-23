import type { RecurringCharge } from './api'

/** A series' identity in the URL: `?series=<accountId>:<merchant>`, what a chat citation of a series names. */
export function seriesParam(charge: Pick<RecurringCharge, 'accountId' | 'merchant'>): string {
  return `${charge.accountId}:${charge.merchant}`
}

/**
 * The charges of a series: the drill-down over its account and merchant, from its first to its last charge's month,
 * money out only. Built from the series' own fields.
 */
export function seriesDrill(charge: RecurringCharge): string {
  const params = new URLSearchParams({
    from: charge.firstDate.slice(0, 7),
    to: charge.lastDate.slice(0, 7),
    measure: 'SPENT',
    accountId: String(charge.accountId),
    merchant: charge.merchant,
    label: `${charge.merchant}: its charges`,
  })
  return `/drill?${params}`
}
