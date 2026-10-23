import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { ApiProblem, reads, type Alert, type AlertListResponse, type AlertType } from '../api'
import AlertCard from '../components/AlertCard'
import { ALERT_LABELS } from '../components/alertText'
import { formatMonth } from '../format'
import { addMonths } from '../months'
import { useDataVersion, useMonth, useShellData } from '../shell/ShellData'

/** The alerts endpoint's default: three months, ending at the month picked. */
const ALERT_MONTHS = 3
const TYPES = Object.keys(ALERT_LABELS) as AlertType[]

type Load<T> = { state: 'loading' } | { state: 'error'; message: string } | { state: 'done'; value: T }

/**
 * The alert `?key=` names, when the list doesn't hold it (another period, or dismissed while hidden); a key that no
 * longer matches is said plainly. What the Overview's summary and, in step 6, a chat citation open.
 */
function useLinkedAlert(key: string | null, list: Load<AlertListResponse>, version: number): Load<Alert> | null {
  const [linked, setLinked] = useState<Load<Alert> | null>(null)
  const inList = list.state === 'done' && list.value.items.some((a) => a.key === key)

  useEffect(() => {
    if (!key || inList || list.state !== 'done') {
      setLinked(null)
      return
    }
    let live = true
    setLinked({ state: 'loading' })
    reads
      .alert(key)
      .then((value) => live && setLinked({ state: 'done', value }))
      .catch((e: Error) =>
        live &&
        setLinked({
          state: 'error',
          message: e instanceof ApiProblem && e.status === 404 ? 'This alert no longer applies.' : e.message,
        }),
      )
    return () => {
      live = false
    }
  }, [key, inList, list.state, version])

  return linked
}

/** Alerts (ANA-04; docs/plans/phase-7.md §2.5): the three months to the month picked, by type, with their basis. */
export default function AlertsPage() {
  const [params, setParams] = useSearchParams()
  const { month } = useMonth()
  const { accounts } = useShellData()
  const { version, dataChanged } = useDataVersion()
  const typeParam = params.get('type')
  const type = TYPES.includes(typeParam as AlertType) ? (typeParam as AlertType) : null
  const includeDismissed = params.get('dismissed') === 'show'
  const key = params.get('key')
  const [list, setList] = useState<Load<AlertListResponse>>({ state: 'loading' })
  const linked = useLinkedAlert(key, list, version)

  useEffect(() => {
    if (!month) {
      return
    }
    let live = true
    setList({ state: 'loading' })
    reads
      .alerts({ from: addMonths(month, -(ALERT_MONTHS - 1)), to: month, type: type ?? undefined, includeDismissed })
      .then((value) => live && setList({ state: 'done', value }))
      .catch((e: Error) => live && setList({ state: 'error', message: e.message }))
    return () => {
      live = false
    }
  }, [month, type, includeDismissed, version])

  const set = (name: string, value: string | null) =>
    setParams((current) => {
      const updated = new URLSearchParams(current)
      if (value) {
        updated.set(name, value)
      } else {
        updated.delete(name)
      }
      return updated
    })

  if (!month) {
    return (
      <section className="page">
        <h1>Alerts</h1>
        <p className="card">No statements yet.</p>
      </section>
    )
  }

  return (
    <section className="page">
      <h1>Alerts</h1>
      <p className="muted">
        {formatMonth(addMonths(month, -(ALERT_MONTHS - 1)))} to {formatMonth(month)} · rule-based checks, no AI
      </p>
      <div className="filters">
        <label>
          Type
          <select value={type ?? ''} onChange={(e) => set('type', e.target.value || null)}>
            <option value="">Every type</option>
            {TYPES.map((t) => (
              <option key={t} value={t}>
                {ALERT_LABELS[t]}
              </option>
            ))}
          </select>
        </label>
        <label className="checkbox">
          <input
            type="checkbox"
            checked={includeDismissed}
            onChange={(e) => set('dismissed', e.target.checked ? 'show' : null)}
          />
          Show dismissed alerts
        </label>
      </div>
      {linked?.state === 'error' && (
        <p className="card" role="status">
          {linked.message}
        </p>
      )}
      {linked?.state === 'done' && (
        <AlertCard alert={linked.value} accounts={accounts} focused onChanged={dataChanged} />
      )}
      {list.state === 'loading' && <p className="muted">Loading…</p>}
      {list.state === 'error' && <p className="error">Couldn’t load the alerts: {list.message}</p>}
      {list.state === 'done' && (
        <>
          <ul className="chips" aria-label="Alerts by type">
            {TYPES.filter((t) => (list.value.counts[t] ?? 0) > 0).map((t) => (
              <li key={t} className="chip">
                {ALERT_LABELS[t]}: {list.value.counts[t]}
              </li>
            ))}
          </ul>
          {list.value.items.length === 0 ? (
            <p className="card">No alerts in these months.</p>
          ) : (
            list.value.items.map((alert) => (
              <AlertCard
                key={alert.key}
                alert={alert}
                accounts={accounts}
                focused={alert.key === key}
                onChanged={dataChanged}
              />
            ))
          )}
          {list.value.truncated && (
            <p className="muted">More alerts matched than are listed; choose a type to see them.</p>
          )}
        </>
      )}
    </section>
  )
}
