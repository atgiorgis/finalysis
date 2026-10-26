import type { DashboardChange, DashboardResponse } from '../api'
import { formatMoney, formatMonth, formatPercent } from '../format'

type Tone = 'good' | 'bad' | 'neutral'

/** Whether a change is good news: more income or net is; more spending isn't. From the sign of the API's string. */
function tone(difference: string | null, moreIsGood: boolean): Tone {
  if (!difference || /^-?0+(\.0+)?$/.test(difference)) {
    return 'neutral'
  }
  const up = !difference.startsWith('-')
  return up === moreIsGood ? 'good' : 'bad'
}

function changeText(change: DashboardChange): string {
  const percent = change.changePercent === null ? '' : ` (${formatPercent(change.changePercent, { sign: 'change' })})`
  return `${formatMoney(change.difference, { sign: 'change' })}${percent}`
}

type Edge = 'spend' | 'income' | 'accent' | 'rate'

function Tile({
  label,
  value,
  edge,
  change,
  changeTone,
  note,
}: {
  label: string
  value: string
  edge: Edge
  change?: string
  changeTone?: Tone
  note?: string
}) {
  return (
    <div className={`kpi-tile kpi-edge-${edge}`}>
      <dt className="kpi-label">{label}</dt>
      <dd className="kpi-value">{value}</dd>
      {change && <dd className={`kpi-change kpi-${changeTone ?? 'neutral'}`}>{change}</dd>}
      {note && <dd className="kpi-note muted">{note}</dd>}
    </div>
  )
}

/** Whether last month, as the cash flow returns it, was partial. */
function previousWasPartial(dashboard: DashboardResponse): boolean {
  const vs = dashboard.vsLastMonth
  return vs ? dashboard.cashFlow.find((m) => m.month === vs.previousMonth)?.coverage === 'PARTIAL' : false
}

/**
 * The partial-month note, shown in place of the Overview's lead sentence: the month's figures can still grow, and the
 * changes compare part of a month with last month. Null for a complete month.
 */
export function partialNote(dashboard: DashboardResponse): string | null {
  const totals = dashboard.totals
  if (!totals || totals.coverage !== 'PARTIAL') {
    return null
  }
  const vs = dashboard.vsLastMonth
  if (!vs) {
    return `${formatMonth(totals.month)} is partial: its figures can still grow.`
  }
  return (
    `${formatMonth(totals.month)} is partial: its figures can still grow, so the changes compare part of a month ` +
    `with ${previousWasPartial(dashboard) ? 'part of' : 'all of'} ${formatMonth(vs.previousMonth)}.`
  )
}

/**
 * The month at a glance (UI-01): spending, income, net and savings rate, each against last month, as the dashboard
 * returns them. "vs Sep" names last month, marked when it was partial. Each card has its colour on the left edge.
 */
export default function KpiTiles({ dashboard }: { dashboard: DashboardResponse }) {
  const totals = dashboard.totals
  if (!totals) {
    return null
  }
  const vs = dashboard.vsLastMonth
  const versus = vs
    ? ` vs ${formatMonth(vs.previousMonth, 'short')}${previousWasPartial(dashboard) ? ' (partial)' : ''}`
    : ''
  const noComparison = vs ? undefined : 'Nothing to compare with last month'

  return (
    <dl className="kpi-tiles" aria-label={`${formatMonth(totals.month)} at a glance`}>
      <Tile
        label="Spending"
        edge="spend"
        value={formatMoney(totals.spending, { sign: 'magnitude' })}
        change={vs ? changeText(vs.spending) + versus : undefined}
        changeTone={vs ? tone(vs.spending.difference, false) : undefined}
        note={noComparison}
      />
      <Tile
        label="Income"
        edge="income"
        value={formatMoney(totals.income)}
        change={vs ? changeText(vs.income) + versus : undefined}
        changeTone={vs ? tone(vs.income.difference, true) : undefined}
        note={noComparison}
      />
      <Tile
        label="Net"
        edge="accent"
        value={formatMoney(totals.net)}
        change={vs ? changeText(vs.net) + versus : undefined}
        changeTone={vs ? tone(vs.net.difference, true) : undefined}
        note={noComparison}
      />
      <Tile
        label="Savings rate"
        edge="rate"
        value={formatPercent(totals.savingsRatePercent)}
        change={
          vs && vs.savingsRatePoints !== null
            ? `${formatPercent(vs.savingsRatePoints, { sign: 'change' }).replace('%', ' pts')}${versus}`
            : undefined
        }
        changeTone={vs ? tone(vs.savingsRatePoints, true) : undefined}
        note={totals.savingsRatePercent === null ? 'No income this month' : noComparison}
      />
    </dl>
  )
}
