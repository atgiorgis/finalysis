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
    <section className="card overview-glance" aria-labelledby="glance-title">
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

/** The colour on an alert card's top edge, by type (mockup v2). */
const ALERT_TONE: Record<AlertType, string> = {
  CATEGORY_SPIKE: 's5',
  LARGE_TRANSACTION: 's3',
  POSSIBLE_DUPLICATE: 'spend',
  RECURRING_PRICE_INCREASE: 's5',
  NEW_RECURRING: 's6',
}

/**
 * The alerts of the three months to the month shown: counts per type, and the newest three as cards with their basis,
 * each card's top edge coloured by its type.
 */
export function AlertsSummary({ dashboard }: { dashboard: DashboardResponse }) {
  const month = dashboard.month
  if (!month) {
    return null
  }
  const counts = Object.entries(dashboard.alerts.counts).filter(([, n]) => (n ?? 0) > 0) as [AlertType, number][]
  const range = dashboard.alerts.range
  return (
    <section className="card overview-alerts" aria-labelledby="alerts-title">
      <div className="card-head">
        <h2 id="alerts-title">Alerts</h2>
        <Link to={withMonth('/alerts', month)} className="card-head-link">
          All alerts →
        </Link>
      </div>
      {range && (
        <p className="muted card-sub">
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
          <ul className="alert-tiles" aria-label="Newest alerts">
            {dashboard.alerts.newest.map((alert) => (
              <li key={alert.key} className={`alert-tile alert-tone-${ALERT_TONE[alert.type]}`}>
                <span className="alert-tile-kind" aria-hidden="true">
                  {ALERT_LABELS[alert.type]}
                </span>
                <Link
                  className="alert-tile-title"
                  to={`/alerts?month=${encodeURIComponent(month)}&key=${encodeURIComponent(alert.key)}`}
                  aria-label={`${ALERT_LABELS[alert.type]}${alertSubject(alert) ? `: ${alertSubject(alert)}` : ''}`}
                >
                  {alertSubject(alert) || ALERT_LABELS[alert.type]}
                </Link>
                <span className="alert-tile-basis muted">
                  {formatMonth(alert.month, 'short')} · {quantityText(alert.basis.observed)} against{' '}
                  {quantityText(alert.basis.threshold)}
                </span>
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  )
}

/** The active recurring series (ANA-07): how many, and the first six with their typical amount. */
export function RecurringSummary({ dashboard }: { dashboard: DashboardResponse }) {
  const month = dashboard.month
  if (!month) {
    return null
  }
  const { active, truncated, items } = dashboard.recurring
  return (
    <section className="card overview-recurring" aria-labelledby="recurring-title">
      <div className="card-head">
        <h2 id="recurring-title">Recurring charges</h2>
        <Link to={withMonth('/recurring', month)} className="card-head-link">
          {active}
          {truncated ? '+' : ''} active →
        </Link>
      </div>
      <p className="muted card-sub">Detected from repeating amounts</p>
      {items.length > 0 && (
        <ul className="recurring-list" aria-label="Active recurring charges">
          {items.map((r) => (
            <li key={`${r.accountId}-${r.merchant}`}>
              <span className="recurring-name">
                <Link to={`/recurring?month=${encodeURIComponent(month)}&series=${encodeURIComponent(seriesParam(r))}`}>
                  {r.merchant}
                </Link>
                <span className="muted">
                  {r.category ? `${r.category.name} · ` : ''}next {formatDate(r.nextExpectedDate)}
                </span>
              </span>
              <span className="recurring-amount">{formatMoney(r.typicalAmount, { sign: 'magnitude' })}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
