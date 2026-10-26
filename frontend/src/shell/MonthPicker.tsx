import { formatMonth } from '../format'
import { useMonth, useShellData } from './ShellData'

/**
 * The month every tab shows (UI-01): ‹ and › step through the months with data, the list picks any of them. Partial
 * months are marked, since their figures can still grow.
 */
export default function MonthPicker() {
  const { months } = useShellData()
  const { month, setMonth } = useMonth()

  if (months.length === 0 || !month) {
    return null
  }
  const index = months.findIndex((m) => m.month === month)
  const previous = index > 0 ? months[index - 1] : null
  const next = index >= 0 && index < months.length - 1 ? months[index + 1] : null
  return (
    <div className="month-picker">
      <button
        type="button"
        className="icon-button"
        aria-label="Previous month"
        disabled={!previous}
        onClick={() => previous && setMonth(previous.month)}
      >
        ‹
      </button>
      <label>
        <span className="visually-hidden">Month</span>
        <select value={month} onChange={(e) => setMonth(e.target.value)}>
          {[...months].reverse().map((m) => (
            <option key={m.month} value={m.month}>
              {formatMonth(m.month)}
              {m.coverage === 'PARTIAL' ? ' (partial)' : ''}
            </option>
          ))}
        </select>
      </label>
      <button
        type="button"
        className="icon-button"
        aria-label="Next month"
        disabled={!next}
        onClick={() => next && setMonth(next.month)}
      >
        ›
      </button>
    </div>
  )
}
