import type { Account, Coverage, DashboardResponse, MonthFlag } from '../api'
import { formatPercent } from '../format'

const COVERAGE_TEXT: Record<Coverage, string> = {
  COMPLETE: 'Complete month',
  PARTIAL: 'Partial month: figures can still grow',
  NONE: 'No statements for this month',
}

export function flagText(flag: MonthFlag, accounts: Account[]): string {
  const name = accounts.find((a) => a.id === flag.accountId)?.name ?? 'An account'
  switch (flag.type) {
    case 'RECONCILIATION_FAILED':
      return `${name}: a statement didn’t reconcile${flag.reviewedAt ? ' (reviewed)' : ''}`
    case 'BALANCE_UNVERIFIED':
      return `${name}: the statement balances couldn’t be checked`
    case 'PARTIAL_COVERAGE':
      return `${name}: statements don’t cover the whole month`
  }
}

/** Data quality for the month (ANA-08): categorized share, items waiting for review, coverage and flags. */
export default function DataQualityTile({
  dashboard,
  accounts,
}: {
  dashboard: DashboardResponse
  accounts: Account[]
}) {
  const totals = dashboard.totals
  if (!totals) {
    return null
  }
  const quality = totals.dataQuality
  return (
    <section className="card data-quality" aria-labelledby="dq-title">
      <h2 id="dq-title">Data quality</h2>
      <p className="dq-headline">
        <strong>{formatPercent(dashboard.categorizedPercent)}</strong> categorized
        {' · '}
        {quality.reviewPendingCount === 1 ? '1 waiting for review' : `${quality.reviewPendingCount} waiting for review`}
      </p>
      <p className={`badge badge-${totals.coverage.toLowerCase()}`}>{COVERAGE_TEXT[totals.coverage]}</p>
      {quality.flags.length > 0 && (
        <ul className="dq-flags">
          {quality.flags.map((flag, i) => (
            <li key={`${flag.type}-${flag.accountId}-${i}`}>{flagText(flag, accounts)}</li>
          ))}
        </ul>
      )}
    </section>
  )
}
