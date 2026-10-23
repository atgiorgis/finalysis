import type { Alert, AlertType, Quantity } from '../api'
import { formatDate, formatMoney, formatPercent } from '../format'

/** Alerts in words (ANA-04), from each alert's own figures: shared by the Overview's summary and the Alerts tab. */

export const ALERT_LABELS: Record<AlertType, string> = {
  CATEGORY_SPIKE: 'Spending spike',
  LARGE_TRANSACTION: 'Large transaction',
  POSSIBLE_DUPLICATE: 'Possible duplicate',
  RECURRING_PRICE_INCREASE: 'Price increase',
  NEW_RECURRING: 'New recurring charge',
}

/** A basis value with its unit, as returned. */
export function quantityText(q: Quantity | null): string {
  if (!q) {
    return '—'
  }
  switch (q.unit) {
    case 'MONEY':
      return formatMoney(q.value, { sign: 'magnitude' })
    case 'PERCENT':
      return formatPercent(q.value)
    case 'RATIO':
      return `${q.value}×`
    case 'DAYS':
      return q.value === '1' ? '1 day' : `${q.value} days`
    default:
      return q.value
  }
}

/** The configured settings an alert's basis names, in words; an unknown one shows its own name. */
export const SETTING_LABELS: Record<string, string> = {
  'min-increase-percent': 'Minimum increase',
  'min-increase-amount': 'Minimum increase in dollars',
  'min-baseline-months': 'Complete months the average needs',
  'min-amount': 'Minimum amount',
  'times-median': 'Times the median',
  'history-days': 'History looked at',
  'min-history': 'Earlier charges needed',
  'stable-count': 'Equal charges before',
  'window-days': 'Within',
  'min-occurrences': 'Charges needed',
  'min-prior-days': 'Data needed before it',
}

const DUPLICATE_REASONS: Record<string, string> = {
  SAME_MERCHANT_AMOUNT_DATE: 'same merchant, amount and day',
  SAME_EXTERNAL_ID: 'same bank reference',
}

type Details = Record<string, unknown>
const text = (d: Details, key: string) => (typeof d[key] === 'string' ? (d[key] as string) : null)

/** The subject of an alert: a large transaction's merchant, else its category, else its merchant. */
export function alertSubject(alert: Alert): string {
  const details = alert.details as Details
  if (alert.type === 'LARGE_TRANSACTION') {
    return (details.txn as { merchant?: string | null } | undefined)?.merchant ?? ''
  }
  const category = (details.category as { name?: string } | undefined)?.name
  return category ?? text(details, 'merchant') ?? ''
}

/** What the check found, in a sentence, from the alert's own figures. */
export function alertFinding(alert: Alert): string {
  const d = alert.details as Details
  switch (alert.type) {
    case 'CATEGORY_SPIKE': {
      const baseline = d.baseline as { amount: string | null; monthsUsed: number } | undefined
      return (
        `${formatMoney(text(d, 'spent'), { sign: 'magnitude' })} spent, ` +
        `${formatMoney(text(d, 'increaseAmount'), { sign: 'magnitude' })} (${formatPercent(text(d, 'increasePercent'), {
          sign: 'change',
        })}) above the ${baseline?.monthsUsed ?? 3}-month average of ${formatMoney(baseline?.amount ?? null)}.`
      )
    }
    case 'LARGE_TRANSACTION': {
      const txn = d.txn as { merchant: string | null; amount: string; txnDate: string } | undefined
      return (
        `${txn?.merchant ?? 'A charge'} ${formatMoney(txn?.amount ?? null, { sign: 'magnitude' })} on ` +
        `${formatDate(txn?.txnDate)}: ${text(d, 'timesMedian')}× the median charge of ${formatMoney(text(d, 'median'))}` +
        ` over ${String(d.historyCount ?? '—')} earlier charges.`
      )
    }
    case 'POSSIBLE_DUPLICATE': {
      const reasons = ((d.reasons as string[] | undefined) ?? []).map((r) => DUPLICATE_REASONS[r] ?? r).join('; ')
      return (
        `${alert.basis.observed.value} charges of ${formatMoney(text(d, 'amount'), { sign: 'magnitude' })}` +
        `${reasons ? ` (${reasons})` : ''}.`
      )
    }
    case 'RECURRING_PRICE_INCREASE':
      return (
        `From ${formatMoney(text(d, 'previousAmount'))} to ${formatMoney(text(d, 'newAmount'))}: ` +
        `${formatMoney(text(d, 'increaseAmount'), { sign: 'change' })} (${formatPercent(text(d, 'increasePercent'), {
          sign: 'change',
        })}).`
      )
    case 'NEW_RECURRING':
      return (
        `${formatMoney(text(d, 'typicalAmount'))} a month, ${String(d.occurrences ?? '—')} charges since ` +
        `${formatDate(text(d, 'firstDate'), true)}.`
      )
  }
}

