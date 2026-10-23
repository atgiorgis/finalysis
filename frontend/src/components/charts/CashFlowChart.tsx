import { Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import type { DashboardResponse, YearMonth } from '../../api'
import { formatMoney, formatMonth } from '../../format'

type CashFlowMonth = DashboardResponse['cashFlow'][number]

/**
 * Bar heights for drawing only: the API's strings are turned into numbers here and nowhere else, never shown or summed
 * (docs/plans/phase-7.md §6.2). Spending is drawn by its size, upwards, beside income.
 */
function height(value: string): number {
  return Math.abs(Number(value))
}

interface Point {
  month: YearMonth
  label: string
  income: number
  spending: number
  source: CashFlowMonth
}

function TooltipBody({ active, payload }: { active?: boolean; payload?: { payload: Point }[] }) {
  if (!active || !payload?.length) {
    return null
  }
  const m = payload[0].payload.source
  return (
    <div className="chart-tooltip">
      <strong>
        {formatMonth(m.month)}
        {m.coverage === 'PARTIAL' ? ' (partial)' : ''}
      </strong>
      <div>Income {formatMoney(m.income)}</div>
      <div>Spending {formatMoney(m.spending, { sign: 'magnitude' })}</div>
      <div>Net {formatMoney(m.net)}</div>
    </div>
  )
}

/**
 * "Cash flow by month" (UI-01): income against spending for six months, partial months striped, the month shown
 * marked. A bar or a month's button selects that month. Each month's net sits under the chart as text, so the chart
 * reads without hover.
 */
export default function CashFlowChart({
  months,
  selected,
  onSelect,
}: {
  months: CashFlowMonth[]
  selected: YearMonth
  onSelect: (month: YearMonth) => void
}) {
  const points: Point[] = months.map((m) => ({
    month: m.month,
    label: formatMonth(m.month, 'short'),
    income: height(m.income),
    spending: height(m.spending),
    source: m,
  }))
  const pick = (point: { payload?: Point }) => {
    if (point.payload && point.payload.source.coverage !== 'NONE') {
      onSelect(point.payload.month)
    }
  }
  const fill = (series: 'income' | 'spending', m: CashFlowMonth) =>
    m.coverage === 'PARTIAL' ? `url(#stripe-${series})` : `var(--series-${series})`

  return (
    <figure className="cash-flow">
      <div className="cash-flow-plot" aria-hidden="true">
        <ResponsiveContainer width="100%" height={200}>
          <BarChart data={points} barGap={2} margin={{ top: 8, right: 0, bottom: 0, left: 0 }}>
            <defs>
              {(['income', 'spending'] as const).map((series) => (
                <pattern
                  key={series}
                  id={`stripe-${series}`}
                  width="6"
                  height="6"
                  patternUnits="userSpaceOnUse"
                  patternTransform="rotate(45)"
                >
                  <rect width="6" height="6" fill="var(--surface)" />
                  <rect width="3" height="6" fill={`var(--series-${series})`} />
                </pattern>
              ))}
            </defs>
            <CartesianGrid vertical={false} stroke="var(--border)" />
            <XAxis
              dataKey="label"
              interval={0}
              tickLine={false}
              axisLine={false}
              tick={{ fill: 'var(--text-muted)', fontSize: 12 }}
            />
            <YAxis hide />
            <Tooltip content={<TooltipBody />} cursor={{ fill: 'var(--surface-muted)' }} />
            <Bar dataKey="income" radius={[4, 4, 0, 0]} onClick={pick} cursor="pointer" isAnimationActive={false}>
              {points.map((p) => (
                <Cell key={p.month} fill={fill('income', p.source)} />
              ))}
            </Bar>
            <Bar dataKey="spending" radius={[4, 4, 0, 0]} onClick={pick} cursor="pointer" isAnimationActive={false}>
              {points.map((p) => (
                <Cell key={p.month} fill={fill('spending', p.source)} />
              ))}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>
      <ol className="cash-flow-months" aria-label="Months">
        {months.map((m) => (
          <li key={m.month}>
            <button
              type="button"
              className="cash-flow-month"
              aria-pressed={m.month === selected}
              disabled={m.coverage === 'NONE'}
              onClick={() => onSelect(m.month)}
              aria-label={`${formatMonth(m.month)}${m.coverage === 'PARTIAL' ? ' (partial)' : ''}: income ${formatMoney(
                m.income,
              )}, spending ${formatMoney(m.spending, { sign: 'magnitude' })}, net ${formatMoney(m.net)}`}
            >
              <span className="cash-flow-label">
                {formatMonth(m.month, 'short')}
                {m.coverage === 'PARTIAL' ? '*' : ''}
              </span>
              <span className="cash-flow-net">{m.coverage === 'NONE' ? '—' : formatMoney(m.net)}</span>
            </button>
          </li>
        ))}
      </ol>
      <figcaption className="muted">
        <span className="legend-swatch legend-income" aria-hidden="true" /> Income{' '}
        <span className="legend-swatch legend-spending" aria-hidden="true" /> Spending{' '}
        <span className="legend-swatch legend-partial" aria-hidden="true" /> Partial month (*). Net under each month.
        Transfers between your accounts are excluded.
      </figcaption>
    </figure>
  )
}
