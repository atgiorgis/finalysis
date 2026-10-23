import { Cell, Pie, PieChart, ResponsiveContainer } from 'recharts'
import type { DashboardSlice } from '../../api'
import { formatMoney, formatPercent, formatSpent } from '../../format'

/** A slice's name as the legend shows it. */
export function sliceName(slice: DashboardSlice): string {
  switch (slice.kind) {
    case 'CATEGORY':
      return slice.category?.name ?? 'Category'
    case 'OTHER':
      return slice.members.length === 1 ? 'Other (1 category)' : `Other (${slice.members.length} categories)`
    case 'UNCATEGORIZED':
      return 'Uncategorized'
  }
}

/** A slice's color: by rank among the category slices, then the two greys (theme.css explains the palette). */
export function sliceColor(slice: DashboardSlice, rank: number): string {
  if (slice.kind === 'OTHER') {
    return 'var(--slice-other)'
  }
  if (slice.kind === 'UNCATEGORIZED') {
    return 'var(--slice-uncat)'
  }
  return `var(--slice-${rank + 1})`
}

/**
 * Slice sizes for drawing only (§6.2): an amount's size, never shown or summed. Other can be money in when refunds
 * outweigh its charges; it then has no arc, and its legend row still shows the figure.
 */
function arc(amount: string): number {
  const value = Number(amount)
  return value < 0 ? -value : 0
}

/**
 * "Where the money went" (UI-01): the donut and its legend, every row naming its slice, share and amount as the API
 * returns them; the month's spending in the centre.
 */
export default function SpendingDonut({
  spending,
  slices,
  selected,
  onSelect,
}: {
  spending: string
  slices: DashboardSlice[]
  /** The slice whose panel is open, if any. */
  selected?: DashboardSlice | null
  onSelect?: (slice: DashboardSlice) => void
}) {
  let rank = 0
  const rows = slices.map((slice) => {
    const color = sliceColor(slice, rank)
    if (slice.kind === 'CATEGORY') {
      rank++
    }
    return { slice, color, name: sliceName(slice), value: arc(slice.amount) }
  })

  return (
    <div className="donut">
      <div className="donut-plot">
        <div aria-hidden="true">
          <ResponsiveContainer width="100%" height={200}>
            <PieChart>
              <Pie
                data={rows}
                dataKey="value"
                nameKey="name"
                innerRadius="62%"
                outerRadius="95%"
                startAngle={90}
                endAngle={-270}
                stroke="var(--surface)"
                strokeWidth={2}
                isAnimationActive={false}
                onClick={(_, index) => onSelect?.(rows[index].slice)}
                cursor={onSelect ? 'pointer' : undefined}
              >
                {rows.map((row) => (
                  <Cell key={row.name} fill={row.color} />
                ))}
              </Pie>
            </PieChart>
          </ResponsiveContainer>
        </div>
        <p className="donut-centre">
          <strong>{formatMoney(spending, { sign: 'magnitude' })}</strong>
          <span className="muted">spent</span>
        </p>
      </div>
      <ul className="donut-legend" aria-label="Spending by category">
        {rows.map((row) => (
          <li key={row.name}>
            <button
              type="button"
              className="donut-legend-row"
              aria-pressed={selected === row.slice}
              onClick={() => onSelect?.(row.slice)}
              disabled={!onSelect}
            >
              <span className="legend-swatch" style={{ background: row.color }} aria-hidden="true" />
              <span className="donut-legend-name">{row.name}</span>
              <span className="donut-legend-share">{formatPercent(row.slice.spendingSharePercent)}</span>
              <span className="donut-legend-amount">{formatSpent(row.slice.amount)}</span>
              {row.slice.kind === 'OTHER' && (
                <span className="donut-legend-members muted">
                  {row.slice.members.map((m) => m.category.name).join(', ')}
                </span>
              )}
            </button>
          </li>
        ))}
      </ul>
    </div>
  )
}
