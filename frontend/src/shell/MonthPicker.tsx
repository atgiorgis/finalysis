import { formatMonth } from '../format'
import { useMonth, useShellData } from './ShellData'

/** The month every tab shows (UI-01). Partial months are marked, since their figures can still grow. */
export default function MonthPicker() {
  const { months } = useShellData()
  const { month, setMonth } = useMonth()

  if (months.length === 0 || !month) {
    return null
  }
  return (
    <label className="month-picker">
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
  )
}
