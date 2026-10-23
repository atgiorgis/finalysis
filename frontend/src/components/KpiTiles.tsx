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

function Tile({
  label,
  value,
  change,
  changeTone,
  note,
}: {
  label: string
  value: string
  change?: string
  changeTone?: Tone
  note?: string
}) {
  return (
    <div className="kpi-tile">
      <dt className="kpi-label">{label}</dt>
      <dd className="kpi-value">{value}</dd>
      {change && <dd className={`kpi-change kpi-${changeTone ?? 'neutral'}`}>{change}</dd>}
      {note && <dd className="kpi-note muted">{note}</dd>}
    </div>
  )
}

/**
 * The month at a glance (UI-01): spending, income, net and savings rate, each against last month, as the dashboard
 * returns them. "vs Sep" names last month, marked when it was partial.
 */
export default function KpiTiles({ dashboard }: { dashboard: DashboardResponse }) {
  const totals = dashboard.totals
  if (!totals) {
    return null
  }
  const vs = dashboard.vsLastMonth
  const previousPartial = vs
    ? dashboard.cashFlow.find((m) => m.month === vs.previousMonth)?.coverage === 'PARTIAL'
    : false
  const versus = vs ? ` vs ${formatMonth(vs.previousMonth, 'short')}${previousPartial ? ' (partial)' : ''}` : ''
  const noComparison = vs ? undefined : 'Nothing to compare with last month'

  return (
    <div>
      {vs && totals.coverage === 'PARTIAL' && (
        <p className="kpi-caveat">
          {formatMonth(totals.month)} is partial: its figures can still grow, so the changes compare part of a month
          with {previousPartial ? 'part of' : 'all of'} {formatMonth(vs.previousMonth)}.
        </p>
      )}
      <dl className="kpi-tiles" aria-label={`${formatMonth(totals.month)} at a glance`}>
        <Tile
          label="Spending"
          value={formatMoney(totals.spending, { sign: 'magnitude' })}
          change={vs ? changeText(vs.spending) + versus : undefined}
          changeTone={vs ? tone(vs.spending.difference, false) : undefined}
          note={noComparison}
        />
        <Tile
          label="Income"
          value={formatMoney(totals.income)}
          change={vs ? changeText(vs.income) + versus : undefined}
          changeTone={vs ? tone(vs.income.difference, true) : undefined}
          note={noComparison}
        />
        <Tile
          label="Net"
          value={formatMoney(totals.net)}
          change={vs ? changeText(vs.net) + versus : undefined}
          changeTone={vs ? tone(vs.net.difference, true) : undefined}
          note={noComparison}
        />
        <Tile
          label="Savings rate"
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
    </div>
  )
}
