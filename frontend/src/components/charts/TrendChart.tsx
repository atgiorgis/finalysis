import { CartesianGrid, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import type { DashboardDetails } from '../../api'
import { formatMoney, formatMonth } from '../../format'

type Cell = DashboardDetails['trend'][number]

interface Point {
  label: string
  value: number | null
  cell: Cell
}

/** Point heights for drawing only (§6.2); a month without data has no point. */
function value(cell: Cell): number | null {
  return cell.coverage === 'NONE' ? null : Number(cell.amount)
}

/** A complete month is a filled point; a partial one hollow, as it is left out of the average. */
function Dot(props: { cx?: number; cy?: number; payload?: Point }) {
  const { cx, cy, payload } = props
  if (cx === undefined || cy === undefined || !payload || payload.value === null) {
    return null
  }
  const partial = payload.cell.coverage === 'PARTIAL'
  return (
    <circle
      cx={cx}
      cy={cy}
      r={5}
      stroke="var(--series-spending)"
      strokeWidth={2}
      fill={partial ? 'var(--surface)' : 'var(--series-spending)'}
    />
  )
}

function TooltipBody({ active, payload }: { active?: boolean; payload?: { payload: Point }[] }) {
  if (!active || !payload?.length) {
    return null
  }
  const cell = payload[0].payload.cell
  return (
    <div className="chart-tooltip">
      <strong>
        {formatMonth(cell.month)}
        {cell.coverage === 'PARTIAL' ? ' (partial)' : ''}
      </strong>
      <div>{cell.coverage === 'NONE' ? 'No data' : formatMoney(cell.amount)}</div>
    </div>
  )
}

/**
 * "6-month trend" (UI-01): the monthly totals as a line, the trailing 3-month average as a dashed line, partial months
 * hollow. The values are listed as text under the chart, so it reads without hover.
 */
export default function TrendChart({ trend, average }: { trend: Cell[]; average: string | null }) {
  const points: Point[] = trend.map((cell) => ({ label: formatMonth(cell.month, 'short'), value: value(cell), cell }))
  const averageLine = average === null ? null : Number(average)

  return (
    <figure className="trend">
      <div aria-hidden="true">
        <ResponsiveContainer width="100%" height={160}>
          <LineChart data={points} margin={{ top: 8, right: 8, bottom: 0, left: 8 }}>
            <CartesianGrid vertical={false} stroke="var(--border)" />
            <XAxis
              dataKey="label"
              interval={0}
              padding={{ left: 16, right: 16 }}
              tickLine={false}
              axisLine={false}
              tick={{ fill: 'var(--text-muted)', fontSize: 12 }}
            />
            <YAxis hide domain={[0, 'auto']} />
            <Tooltip content={<TooltipBody />} />
            {averageLine !== null && (
              <ReferenceLine y={averageLine} stroke="var(--text-muted)" strokeDasharray="5 4" strokeWidth={2} />
            )}
            <Line
              dataKey="value"
              stroke="var(--series-spending)"
              strokeWidth={2}
              dot={<Dot />}
              activeDot={false}
              connectNulls={false}
              isAnimationActive={false}
            />
          </LineChart>
        </ResponsiveContainer>
      </div>
      <ol className="trend-values" aria-label="6-month trend">
        {trend.map((cell) => (
          <li key={cell.month}>
            <span>
              {formatMonth(cell.month, 'short')}
              {cell.coverage === 'PARTIAL' ? '*' : ''}
            </span>
            <span>{cell.coverage === 'NONE' ? '—' : formatMoney(cell.amount)}</span>
          </li>
        ))}
      </ol>
      <figcaption className="muted">
        — monthly total{average !== null && <> · - - 3-month average</>}
        {trend.some((c) => c.coverage === 'PARTIAL') && ' · * partial month: hollow point, left out of averages'}
      </figcaption>
    </figure>
  )
}
