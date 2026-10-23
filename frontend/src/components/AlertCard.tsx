import { useState, type FormEvent } from 'react'
import { ApiProblem, dismissals, type Account, type Alert } from '../api'
import { formatDate, formatMoney, formatMonth } from '../format'
import { useTxnSheet } from '../shell/TxnSheet'
import { flagText } from './DataQualityTile'
import { ALERT_LABELS, SETTING_LABELS, alertFinding, alertSubject, quantityText } from './alertText'

/** The basis (ANA-04): the figure judged, against what, over which days. It fires when observed ≥ threshold. */
function Basis({ alert }: { alert: Alert }) {
  const { observed, baseline, threshold, periodStart, periodEnd, settings } = alert.basis
  return (
    <div className="alert-basis">
      <h3>Why this alert</h3>
      <dl className="facts">
        <dt>Observed</dt>
        <dd>{quantityText(observed)}</dd>
        {baseline && (
          <div className="facts-row">
            <dt>Baseline</dt>
            <dd>{quantityText(baseline)}</dd>
          </div>
        )}
        <dt>Threshold</dt>
        <dd>{quantityText(threshold)} (it fires at this or more)</dd>
        <dt>Period</dt>
        <dd>
          {formatDate(periodStart, true)}
          {periodEnd !== periodStart && ` to ${formatDate(periodEnd, true)}`}
        </dd>
        {settings.map((s) => (
          <div key={s.name} className="facts-row">
            <dt>{SETTING_LABELS[s.name] ?? s.name}</dt>
            <dd>{quantityText(s.value)}</dd>
          </div>
        ))}
      </dl>
    </div>
  )
}

/**
 * One alert (ANA-04): what it found, its basis, the transactions it is about (each opens its sheet), the data flags of
 * the months it used, and dismiss or restore.
 */
export default function AlertCard({
  alert,
  accounts,
  focused = false,
  onChanged,
}: {
  alert: Alert
  accounts: Account[]
  focused?: boolean
  onChanged: () => void
}) {
  const { open } = useTxnSheet()
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const id = `alert-${alert.key}`

  async function act(action: () => Promise<unknown>) {
    setBusy(true)
    setError(null)
    try {
      await action()
      onChanged()
    } catch (e) {
      setError(e instanceof ApiProblem || e instanceof Error ? e.message : 'The request failed.')
    } finally {
      setBusy(false)
    }
  }

  const dismiss = (e: FormEvent) => {
    e.preventDefault()
    void act(() => dismissals.dismiss(alert.key, note.trim() || null))
  }

  return (
    <article
      className={`card alert-card${alert.dismissed ? ' alert-dismissed' : ''}${focused ? ' alert-focused' : ''}`}
      aria-labelledby={id}
      tabIndex={focused ? -1 : undefined}
      ref={(el) => {
        if (el && focused) {
          el.focus()
        }
      }}
    >
      <p className="muted alert-kind">
        {ALERT_LABELS[alert.type]} · {formatMonth(alert.month)}
        {alert.dismissed && ' · dismissed'}
      </p>
      <h2 id={id}>{alertSubject(alert) || ALERT_LABELS[alert.type]}</h2>
      <p>{alertFinding(alert)}</p>
      <Basis alert={alert} />
      {alert.txns.length > 0 && (
        <>
          <h3>{alert.txns.length === 1 ? 'The transaction' : 'The transactions'}</h3>
          <ul className="txn-list">
            {alert.txns.map((t) => (
              <li key={t.id}>
                <button type="button" className="txn-row row-button" onClick={() => open(t.id)}>
                  <span className="txn-date">{formatDate(t.txnDate)}</span>
                  <span className="txn-merchant">{t.merchant ?? '(no merchant)'}</span>
                  <span className="txn-amount">{formatMoney(t.amount)}</span>
                </button>
              </li>
            ))}
          </ul>
        </>
      )}
      {alert.dataFlags.length > 0 && (
        <div className="alert-flags">
          <p className="muted">Its figures use months with a flag, so they may be off:</p>
          <ul>
            {alert.dataFlags.map((f, i) => (
              <li key={`${f.type}-${f.accountId}-${f.statementId ?? i}`}>{flagText(f, accounts)}</li>
            ))}
          </ul>
        </div>
      )}
      {alert.dismissed ? (
        <div className="alert-actions">
          <p className="muted">
            Dismissed{alert.dismissedAt && ` on ${formatDate(alert.dismissedAt.slice(0, 10), true)}`}
            {alert.note && `: “${alert.note}”`}
          </p>
          <button type="button" className="button-secondary" disabled={busy} onClick={() => void act(() => dismissals.restore(alert.key))}>
            Restore
          </button>
        </div>
      ) : (
        <form className="alert-actions" onSubmit={dismiss}>
          <label>
            Note (optional)
            <input value={note} onChange={(e) => setNote(e.target.value)} maxLength={200} disabled={busy} />
          </label>
          <button type="submit" className="button-secondary" disabled={busy}>
            Dismiss
          </button>
        </form>
      )}
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
    </article>
  )
}
