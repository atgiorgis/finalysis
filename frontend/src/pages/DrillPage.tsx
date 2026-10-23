import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { reads, type Category, type DrillDownResponse, type TxnBucket } from '../api'
import { formatDate, formatMoney, formatMonth } from '../format'
import { useShellData } from '../shell/ShellData'
import { useTxnSheet } from '../shell/TxnSheet'

/** The drill-down's own filters (`GET /api/analytics/transactions`); nothing else in the URL is sent. */
export const DRILL_PARAMS = [
  'month',
  'from',
  'to',
  'bucket',
  'measure',
  'accountId',
  'categoryId',
  'merchant',
  'noMerchant',
] as const

const PAGE_SIZE = 50

const BUCKET_TEXT: Record<TxnBucket, string> = {
  EXPENSE: 'Spending',
  REFUND: 'Refunds',
  INCOME: 'Income',
  UNCATEGORIZED: 'Uncategorized',
  LINKED_TRANSFER: 'Transfers between your accounts',
  UNLINKED_TRANSFER: 'Transfers not matched yet',
}

function describe(
  params: Record<string, string>,
  categories: Category[],
  accounts: ReturnType<typeof useShellData>['accounts'],
): string[] {
  const parts: string[] = []
  if (params.month) {
    parts.push(formatMonth(params.month))
  } else if (params.from || params.to) {
    parts.push(`${formatMonth(params.from ?? params.to)} to ${formatMonth(params.to ?? params.from)}`)
  }
  if (params.bucket) {
    parts.push(BUCKET_TEXT[params.bucket as TxnBucket] ?? params.bucket)
  }
  if (params.measure) {
    parts.push(params.measure === 'SPENT' ? 'Money out' : params.measure === 'RECEIVED' ? 'Money in' : params.measure)
  }
  if (params.categoryId) {
    parts.push(categories.find((c) => String(c.id) === params.categoryId)?.name ?? `Category ${params.categoryId}`)
  }
  if (params.accountId) {
    parts.push(accounts.find((a) => String(a.id) === params.accountId)?.name ?? `Account ${params.accountId}`)
  }
  if (params.merchant) {
    parts.push(params.merchant)
  }
  if (params.noMerchant === 'true') {
    parts.push('No merchant')
  }
  return parts
}

/**
 * The rows behind any total (UI-04): what a chat citation of a total opens (docs/plans/phase-7.md §3.5). The URL holds
 * the drill-down's exact parameters, an optional `label` naming the total and an optional `expect`, the figure the
 * rows must add up to; the ✓ shows only when the rows' total is that same string.
 */
export default function DrillPage() {
  const [search] = useSearchParams()
  const { accounts } = useShellData()
  const { open } = useTxnSheet()
  const [categories, setCategories] = useState<Category[]>([])
  const [pages, setPages] = useState<DrillDownResponse[]>([])
  const [error, setError] = useState<string | null>(null)

  const params: Record<string, string> = {}
  for (const name of DRILL_PARAMS) {
    const value = search.get(name)
    if (value) {
      params[name] = value
    }
  }
  const key = JSON.stringify(params)
  const label = search.get('label')
  const expect = search.get('expect')

  useEffect(() => {
    reads.categories().then(setCategories).catch(() => setCategories([]))
  }, [])

  useEffect(() => {
    let live = true
    setPages([])
    setError(null)
    reads
      .drillDown({ ...(JSON.parse(key) as Record<string, string>), page: 0, size: PAGE_SIZE })
      .then((page) => live && setPages([page]))
      .catch((e: Error) => live && setError(e.message))
    return () => {
      live = false
    }
  }, [key])

  const more = () =>
    reads
      .drillDown({ ...params, page: pages.length, size: PAGE_SIZE })
      .then((page) => setPages((current) => [...current, page]))
      .catch((e: Error) => setError(e.message))

  const first = pages[0]
  const items = pages.flatMap((p) => p.items)
  const parts = describe(params, categories, accounts)

  return (
    <section className="page">
      <h1>{label ?? 'Transactions behind a total'}</h1>
      {parts.length > 0 && (
        <ul className="chips" aria-label="Filters">
          {parts.map((part) => (
            <li key={part} className="chip">
              {part}
            </li>
          ))}
        </ul>
      )}
      {error && <p className="error">Couldn’t load the transactions: {error}</p>}
      {!error && !first && <p className="muted">Loading…</p>}
      {first && (
        <section className="card" aria-label="Rows">
          <p className="details-total">{formatMoney(first.total.net)}</p>
          {first.total.moneyOut !== '0.00' && first.total.moneyIn !== '0.00' && (
            <p className="muted">
              {formatMoney(first.total.moneyOut, { sign: 'magnitude' })} out · {formatMoney(first.total.moneyIn)} in
            </p>
          )}
          {items.length === 0 ? (
            <p>No transactions.</p>
          ) : (
            <ul className="txn-list">
              {items.map((t) => (
                <li key={t.id}>
                  <button type="button" className="txn-row row-button" onClick={() => open(t.id)}>
                    <span className="txn-date">{formatDate(t.txnDate)}</span>
                    <span className="txn-merchant">{t.merchant ?? '(no merchant)'}</span>
                    <span className="txn-amount">{formatMoney(t.amount)}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
          {pages.length < first.totalPages && (
            <button type="button" className="link-button" onClick={more}>
              Show more
            </button>
          )}
          <p
            className={expect === null || expect === first.total.net ? 'adds-up' : 'adds-up adds-up-not'}
            role="status"
          >
            {first.total.count === 1 ? '1 transaction' : `${first.total.count} transactions`} · add up to{' '}
            {formatMoney(first.total.net)}
            {expect !== null &&
              (expect === first.total.net ? (
                <>
                  {' '}
                  <span aria-label="checked">✓</span>
                </>
              ) : (
                `, not ${formatMoney(expect)}`
              ))}
          </p>
        </section>
      )}
    </section>
  )
}
