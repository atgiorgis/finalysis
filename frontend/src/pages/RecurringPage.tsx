import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { reads, type RecurringChargesResponse } from '../api'
import { formatDate, formatMoney } from '../format'
import { seriesDrill, seriesParam } from '../series'
import { useDataVersion, useShellData } from '../shell/ShellData'

type Status = 'ACTIVE' | 'ENDED' | 'ALL'
const STATUSES: { value: Status; label: string }[] = [
  { value: 'ACTIVE', label: 'Active' },
  { value: 'ENDED', label: 'Ended' },
  { value: 'ALL', label: 'All' },
]

/** The most series the endpoint lists (ANA-07). */
const LIMIT = 100

/** Recurring charges (ANA-07; docs/plans/phase-7.md §2.6): each series with its status, amounts and next date. */
export default function RecurringPage() {
  const [params, setParams] = useSearchParams()
  const { accounts } = useShellData()
  const { version } = useDataVersion()
  const statusParam = params.get('status') as Status | null
  const status: Status = STATUSES.some((s) => s.value === statusParam) ? (statusParam as Status) : 'ALL'
  const highlighted = params.get('series')
  const [load, setLoad] = useState<
    { state: 'loading' } | { state: 'error'; message: string } | { state: 'done'; value: RecurringChargesResponse }
  >({ state: 'loading' })

  useEffect(() => {
    let live = true
    setLoad({ state: 'loading' })
    reads
      .recurring({ status, limit: LIMIT })
      .then((value) => live && setLoad({ state: 'done', value }))
      .catch((e: Error) => live && setLoad({ state: 'error', message: e.message }))
    return () => {
      live = false
    }
  }, [status, version])

  const show = (next: Status) =>
    setParams((current) => {
      const updated = new URLSearchParams(current)
      if (next === 'ALL') {
        updated.delete('status')
      } else {
        updated.set('status', next)
      }
      return updated
    })

  return (
    <section className="page">
      <h1>Recurring charges</h1>
      <p className="muted">Detected from monthly charges of the same merchant on the same account.</p>
      <div className="segmented" role="group" aria-label="Status">
        {STATUSES.map((s) => (
          <button key={s.value} type="button" aria-pressed={status === s.value} onClick={() => show(s.value)}>
            {s.label}
          </button>
        ))}
      </div>
      {load.state === 'loading' && <p className="muted">Loading…</p>}
      {load.state === 'error' && <p className="error">Couldn’t load the recurring charges: {load.message}</p>}
      {load.state === 'done' &&
        (load.value.items.length === 0 ? (
          <p className="card">No recurring charges.</p>
        ) : (
          <ul className="series-list" aria-label="Recurring charges">
            {load.value.items.map((r) => {
              const id = seriesParam(r)
              return (
                <li
                  key={id}
                  className={`card series${id === highlighted ? ' series-highlighted' : ''}`}
                  aria-current={id === highlighted ? 'true' : undefined}
                >
                  <div className="series-head">
                    <h2>{r.merchant}</h2>
                    <span className={`badge badge-${r.status === 'ACTIVE' ? 'complete' : 'none'}`}>
                      {r.status === 'ACTIVE' ? 'Active' : 'Ended'}
                    </span>
                  </div>
                  <p className="muted">
                    {accounts.find((a) => a.id === r.accountId)?.name ?? 'An account'}
                    {r.category && ` · ${r.category.name}`} · monthly
                  </p>
                  <dl className="facts">
                    <dt>Typical</dt>
                    <dd>{formatMoney(r.typicalAmount, { sign: 'magnitude' })}</dd>
                    <dt>Last</dt>
                    <dd>
                      {formatMoney(r.lastAmount, { sign: 'magnitude' })} on {formatDate(r.lastDate, true)}
                      {!r.fixedPrice && ' · the amount varies'}
                    </dd>
                    <dt>{r.status === 'ACTIVE' ? 'Next expected' : 'Ended after'}</dt>
                    <dd>
                      {r.status === 'ACTIVE'
                        ? `${formatDate(r.nextExpectedDate, true)} (a projection)`
                        : formatDate(r.lastDate, true)}
                    </dd>
                    <dt>Charges</dt>
                    <dd>
                      {r.occurrences} since {formatDate(r.firstDate, true)}
                    </dd>
                  </dl>
                  <Link to={seriesDrill(r)}>See its charges</Link>
                </li>
              )
            })}
          </ul>
        ))}
      {load.state === 'done' && load.value.truncated && (
        <p className="muted">More series than the {LIMIT} listed.</p>
      )}
    </section>
  )
}
