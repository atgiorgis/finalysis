import type { DashboardResponse } from '../api'
import { formatMoney, formatMonth, formatPercent } from '../format'

/** A run of the sentence; `figure` runs are the API's values, shown strong (`net` in the income colour). */
export type LeadPart = { text: string; figure?: 'net' | 'plain' }

const ZERO = /^-?0+(\.0+)?$/

/**
 * The Overview's lead (step 7c), joined from the API's strings only: the month's net, its savings rate, and the
 * change in points from last month, never a figure worked out here.
 *
 * "You kept $1,351.17 of what came in. That's a 27.6% savings rate, up 18.9 points from August." A negative net reads
 * "You spent $212.40 more than came in." (the API's net with its minus dropped). Without last month's figure the
 * sentence ends after the rate. Null without a rate (no income: the KPI card says so) and on a partial month, where the
 * page shows the partial-month note instead.
 */
export function leadSentence(dashboard: DashboardResponse): LeadPart[] | null {
  const totals = dashboard.totals
  if (!totals || totals.coverage !== 'COMPLETE' || totals.savingsRatePercent === null) {
    return null
  }
  const spentMore = totals.net.startsWith('-') && !ZERO.test(totals.net)
  const parts: LeadPart[] = spentMore
    ? [
        { text: 'You spent ' },
        { text: formatMoney(totals.net, { sign: 'magnitude' }), figure: 'net' },
        { text: ' more than came in.' },
      ]
    : [{ text: 'You kept ' }, { text: formatMoney(totals.net), figure: 'net' }, { text: ' of what came in.' }]

  parts.push({ text: ' That’s a ' }, { text: formatPercent(totals.savingsRatePercent), figure: 'plain' })
  const vs = dashboard.vsLastMonth
  const points = vs?.savingsRatePoints ?? null
  if (!vs || points === null) {
    parts.push({ text: ' savings rate.' })
    return parts
  }
  const from = formatMonth(vs.previousMonth, 'name')
  if (ZERO.test(points)) {
    parts.push({ text: ` savings rate, unchanged from ${from}.` })
    return parts
  }
  parts.push(
    { text: ` savings rate, ${points.startsWith('-') ? 'down' : 'up'} ` },
    { text: `${formatPercent(points, { sign: 'magnitude' }).replace(/%$/, '')} points`, figure: 'plain' },
    { text: ` from ${from}.` },
  )
  return parts
}
