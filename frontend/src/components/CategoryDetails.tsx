import { useEffect, useRef, useState } from 'react'
import { reads, type DashboardDetails, type DashboardResponse, type DrillDownResponse } from '../api'
import { detailsTarget, shownFigure, shownName, type Detail } from '../detailParam'
import { formatDate, formatMoney, formatMonth, formatPercent, formatSpent } from '../format'
import TrendChart from './charts/TrendChart'

const PAGE_SIZE = 50

/** Income as it is; a spending figure as its size, money in with a plus. */
function figure(value: string, kind: DashboardDetails['kind']): string {
  return kind === 'INCOME' ? formatMoney(value) : formatSpent(value)
}

function againstAverage(details: DashboardDetails): string {
  const { amount, differenceFromAverage, differencePercent, monthsUsed, monthsWanted } = details.average
  if (amount === null || differenceFromAverage === null) {
    return 'No complete month among the three before to compare with.'
  }
  const percent = differencePercent === null ? '' : ` (${formatPercent(differencePercent, { sign: 'change' })})`
  const over = monthsUsed < monthsWanted ? ` of ${monthsUsed} complete month${monthsUsed === 1 ? '' : 's'}` : ''
  const where = /^-?0+(\.0+)?$/.test(differenceFromAverage)
    ? 'the same as'
    : differenceFromAverage.startsWith('-')
      ? `${formatMoney(differenceFromAverage, { sign: 'magnitude' })}${percent} below`
      : `${formatMoney(differenceFromAverage)}${percent} above`
  return `${where} the 3-month average${over} (${formatMoney(amount)})`
}

/** The rows behind the panel's total, paged, with the check that they add up to it (UI-04). */
function Transactions({ details, shown }: { details: DashboardDetails; shown: string | null }) {
  const [pages, setPages] = useState<DrillDownResponse[]>([])
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let live = true
    setPages([])
    setError(null)
    reads
      .drillDown({ ...details.drillDown.params, page: 0, size: PAGE_SIZE })
      .then((page) => live && setPages([page]))
      .catch((e: Error) => live && setError(e.message))
    return () => {
      live = false
    }
  }, [details])

  const more = () => {
    reads
      .drillDown({ ...details.drillDown.params, page: pages.length, size: PAGE_SIZE })
      .then((page) => setPages((current) => [...current, page]))
      .catch((e: Error) => setError(e.message))
  }

  if (error) {
    return <p className="error">Couldn’t load the transactions: {error}</p>
  }
  if (pages.length === 0) {
    return <p className="muted">Loading…</p>
  }
  const first = pages[0]
  const items = pages.flatMap((p) => p.items)
  // Compared as the API's strings: the rows' total, the panel's total and the figure the Overview showed.
  const addsUp = first.total.net === details.total && (shown === null || shown === details.total)
  const count = first.total.count

  return (
    <>
      {items.length === 0 ? (
        <p>No transactions in this category this month.</p>
      ) : (
        <ul className="txn-list">
          {items.map((t) => (
            <li key={t.id} className="txn-row">
              <span className="txn-date">{formatDate(t.txnDate)}</span>
              <span className="txn-merchant">
                {t.merchant ?? '(no merchant)'}
                {t.bucket === 'REFUND' && <span className="tag">refund</span>}
              </span>
              <span className="txn-amount">{formatMoney(t.amount)}</span>
            </li>
          ))}
        </ul>
      )}
      {pages.length < first.totalPages && (
        <button type="button" className="link-button" onClick={more}>
          Show more
        </button>
      )}
      <p className={addsUp ? 'adds-up' : 'adds-up adds-up-not'} role="status">
        {count === 1 ? '1 transaction' : `${count} transactions`} ·{' '}
        {addsUp ? (
          <>
            add up to {figure(details.total, details.kind)} <span aria-label="checked">✓</span>
          </>
        ) : (
          <>
            add up to {figure(first.total.net, details.kind)}, not{' '}
            {figure(shown !== null && shown !== details.total ? shown : details.total, details.kind)}
          </>
        )}
      </p>
    </>
  )
}

function OtherMembers({
  dashboard,
  onOpen,
}: {
  dashboard: DashboardResponse
  onOpen: (detail: Detail) => void
}) {
  const other = dashboard.spendingSlices.rows.find((s) => s.kind === 'OTHER')
  if (!other) {
    return <p>No Other slice this month.</p>
  }
  return (
    <>
      <p className="details-total">{formatSpent(other.amount)}</p>
      <p className="muted">
        {formatPercent(other.spendingSharePercent)} of spending · each category opens its own panel
      </p>
      <ul className="details-members">
        {other.members.map((m) => (
          <li key={m.category.id}>
            <button
              type="button"
              className="row-button"
              onClick={() => onOpen({ kind: 'category', categoryId: m.category.id })}
            >
              <span>{m.category.name}</span>
              <span className="muted">{formatPercent(m.spendingSharePercent)}</span>
              <span>{formatSpent(m.amount)}</span>
            </button>
          </li>
        ))}
      </ul>
    </>
  )
}

/**
 * The details panel (UI-01, UI-04; docs/plans/phase-7.md §2.3): one total of the Overview with its share, its month
 * against the 3-month average, a 6-month trend, the top merchants and the transactions behind it, which must add up
 * to the figure the Overview showed.
 */
export default function CategoryDetails({
  dashboard,
  detail,
  onOpen,
  onClose,
}: {
  dashboard: DashboardResponse
  detail: Detail
  onOpen: (detail: Detail) => void
  onClose: () => void
}) {
  const month = dashboard.month as string
  const [details, setDetails] = useState<DashboardDetails | null>(null)
  const [error, setError] = useState<string | null>(null)
  const heading = useRef<HTMLHeadingElement>(null)
  const target = detailsTarget(detail)
  const targetKey = JSON.stringify(target)
  const shown = shownFigure(dashboard, detail)
  const name = details?.category?.name ?? shownName(dashboard, detail)

  useEffect(() => {
    heading.current?.focus()
  }, [targetKey, month])

  useEffect(() => {
    let live = true
    setDetails(null)
    setError(null)
    const parsed = targetKey === 'null' ? null : (JSON.parse(targetKey) as NonNullable<typeof target>)
    if (parsed) {
      reads
        .details(month, parsed)
        .then((d) => live && setDetails(d))
        .catch((e: Error) => live && setError(e.message))
    }
    return () => {
      live = false
    }
  }, [month, targetKey])

  return (
    <section className="card details" aria-labelledby="details-title">
      <div className="details-head">
        <p className="muted details-crumb">
          {formatMonth(month)} › {name}
        </p>
        <button type="button" className="link-button" onClick={onClose}>
          Close
        </button>
      </div>
      <h2 id="details-title" ref={heading} tabIndex={-1}>
        {name}
      </h2>
      {detail.kind === 'other' && <OtherMembers dashboard={dashboard} onOpen={onOpen} />}
      {error && <p className="error">Couldn’t load the details: {error}</p>}
      {target && !error && !details && <p className="muted">Loading…</p>}
      {details && (
        <>
          <p className="details-total">{figure(details.total, details.kind)}</p>
          <p className="muted">
            {details.spendingSharePercent !== null && `${formatPercent(details.spendingSharePercent)} of spending · `}
            {againstAverage(details)}
          </p>
          <h3>6-month trend</h3>
          <TrendChart trend={details.trend} average={details.average.amount} />
          {details.topMerchants && (
            <>
              <h3>Top merchants</h3>
              {details.topMerchants.items.length === 0 ? (
                <p className="muted">No merchants this month.</p>
              ) : (
                <ul className="details-merchants" aria-label="Top merchants">
                  {details.topMerchants.items.map((m) => (
                    <li key={m.merchant}>
                      <span>{m.merchant}</span>
                      <span>{formatMoney(m.spent, { sign: 'magnitude' })}</span>
                    </li>
                  ))}
                  {details.topMerchants.othersCount > 0 && (
                    <li className="muted">
                      <span>Others ({details.topMerchants.othersCount})</span>
                      <span>{formatMoney(details.topMerchants.othersSpent, { sign: 'magnitude' })}</span>
                    </li>
                  )}
                </ul>
              )}
            </>
          )}
          <h3>Transactions</h3>
          <Transactions details={details} shown={shown} />
        </>
      )}
    </section>
  )
}
