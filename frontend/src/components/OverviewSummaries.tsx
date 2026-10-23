import { Link } from 'react-router-dom'
import type { AlertType, DashboardResponse, YearMonth } from '../api'
import { seriesParam } from '../series'
import { ALERT_LABELS, alertSubject, quantityText } from './alertText'
import { formatDate, formatMoney, formatMonth } from '../format'

const withMonth = (path: string, month: YearMonth) => `${path}?month=${encodeURIComponent(month)}`

/** "Transfers between your accounts: Excluded", income and refunds, the lines the donut leaves out. */
export function AtAGlance({ dashboard, onOpenIncome }: { dashboard: DashboardResponse; onOpenIncome?: () => void }) {
  const totals = dashboard.totals
  if (!totals) {
    return null
  }
  return (
    <section className="card" aria-labelledby="glance-title">
      <h2 id="glance-title">{formatMonth(totals.month)} at a glance</h2>
      <dl className="facts">
        <dt>Income</dt>
        <dd>
          {onOpenIncome ? (
            <button
              type="button"
              className="link-button"
              onClick={onOpenIncome}
              aria-label={`Income ${formatMoney(totals.income)}: details`}
            >
              {formatMoney(totals.income)}
            </button>
          ) : (
            formatMoney(totals.income)
          )}
        </dd>
        <dt>Spending</dt>
        <dd>{formatMoney(totals.spending, { sign: 'magnitude' })}</dd>
        {totals.refunds.count > 0 && (
          <div className="facts-row">
            <dt>Refunds &amp; rebates (already in spending)</dt>
            <dd>{formatMoney(totals.refunds.net, { sign: 'change' })}</dd>
          </div>
        )}
        <dt>Transfers between your accounts</dt>
        <dd>
          Excluded
          {totals.linkedTransfers.count > 0 && ` (${formatMoney(totals.linkedTransfers.moneyIn)} moved)`}
        </dd>
        {totals.unlinkedTransfers.count > 0 && (
          <div className="facts-row">
            <dt>Transfers not matched yet</dt>
            <dd>
              Not counted (
              {totals.unlinkedTransfers.count === 1 ? '1 transaction' : `${totals.unlinkedTransfers.count} transactions`})
            </dd>
          </div>
        )}
      </dl>
    </section>
  )
}

/** The alerts of the three months to the month shown: counts per type and the newest three, with their basis. */
export function AlertsSummary({ dashboard }: { dashboard: DashboardResponse }) {
  const month = dashboard.month
  if (!month) {
    return null
  }
  const counts = Object.entries(dashboard.alerts.counts).filter(([, n]) => (n ?? 0) > 0) as [AlertType, number][]
  const range = dashboard.alerts.range
  return (
    <section className="card" aria-labelledby="alerts-title">
      <h2 id="alerts-title">Alerts</h2>
      {range && (
        <p className="muted">
          {formatMonth(range.from, 'short')} to {formatMonth(range.to, 'short')} · rule-based
        </p>
      )}
      {counts.length === 0 ? (
        <p>No alerts.</p>
      ) : (
        <>
          <ul className="chips" aria-label="Alerts by type">
            {counts.map(([type, n]) => (
              <li key={type} className="chip">
                {ALERT_LABELS[type]}: {n}
              </li>
            ))}
          </ul>
          <ul className="summary-list">
            {dashboard.alerts.newest.map((alert) => (
              <li key={alert.key}>
                <Link to={`/alerts?month=${encodeURIComponent(month)}&key=${encodeURIComponent(alert.key)}`}>
                  {ALERT_LABELS[alert.type]}
                  {alertSubject(alert) && `: ${alertSubject(alert)}`}
                </Link>
                <span className="muted">
                  {' '}
                  {formatMonth(alert.month, 'short')} · {quantityText(alert.basis.observed)} against{' '}
                  {quantityText(alert.basis.threshold)}
                </span>
              </li>
            ))}
          </ul>
        </>
      )}
      <Link to={withMonth('/alerts', month)}>All alerts</Link>
    </section>
  )
}

/** The active recurring series (ANA-07): the first six, and how many there are. */
export function RecurringSummary({ dashboard }: { dashboard: DashboardResponse }) {
  const month = dashboard.month
  if (!month) {
    return null
  }
  const { active, truncated, items } = dashboard.recurring
  return (
    <section className="card" aria-labelledby="recurring-title">
      <h2 id="recurring-title">Recurring charges</h2>
      <p className="muted">
        {active === 1 ? '1 active series' : `${active}${truncated ? '+' : ''} active series`} · detected from
        repeating amounts
      </p>
      {items.length > 0 && (
        <table className="summary-table">
          <thead>
            <tr>
              <th scope="col">Merchant</th>
              <th scope="col" className="num">Typical</th>
              <th scope="col" className="num">Next</th>
            </tr>
          </thead>
          <tbody>
            {items.map((r) => (
              <tr key={`${r.accountId}-${r.merchant}`}>
                <td>
                  <Link to={`/recurring?month=${encodeURIComponent(month)}&series=${encodeURIComponent(seriesParam(r))}`}>
                    {r.merchant}
                  </Link>
                  {r.category && <span className="muted"> · {r.category.name}</span>}
                </td>
                <td className="num">{formatMoney(r.typicalAmount, { sign: 'magnitude' })}</td>
                <td className="num">{formatDate(r.nextExpectedDate)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <Link to={withMonth('/recurring', month)}>All recurring charges</Link>
    </section>
  )
}
