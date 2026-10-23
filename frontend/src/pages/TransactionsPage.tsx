import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { reads, type ReviewQueueResponse, type Transaction, type TransactionPage } from '../api'
import { formatDate, formatMoney, formatMonth } from '../format'
import { monthDays } from '../months'
import { useDataVersion, useMonth, useShellData } from '../shell/ShellData'
import { useTxnSheet } from '../shell/TxnSheet'

const PAGE_SIZE = 50

const REVIEW_REASONS: Record<string, string> = {
  LOW_CONFIDENCE: 'The AI wasn’t sure',
  INVALID_ANSWER: 'The AI’s answers were invalid',
}

/** Review kinds this page doesn't resolve: they wait for the upload page (UI-02, Phase 8). */
const ELSEWHERE: { key: keyof ReviewQueueResponse['counts']; one: string; many: string }[] = [
  { key: 'statements', one: 'statement needs review', many: 'statements need review' },
  { key: 'unparsedUploads', one: 'upload wasn’t recognized', many: 'uploads weren’t recognized' },
  { key: 'transferCandidates', one: 'transfer match needs a decision', many: 'transfer matches need a decision' },
  { key: 'competingCandidates', one: 'transfer match competes with another', many: 'transfer matches compete' },
  { key: 'possibleMatches', one: 'possible transfer match', many: 'possible transfer matches' },
]

type Load<T> = { state: 'loading' } | { state: 'error'; message: string } | { state: 'done'; value: T }

/** Pages of a list, the first replaced when its query changes, Show more appending the next. */
function usePages(query: Record<string, string | number | boolean | undefined> | null, version: number) {
  const [pages, setPages] = useState<TransactionPage[]>([])
  const [error, setError] = useState<string | null>(null)
  const key = JSON.stringify(query)

  useEffect(() => {
    if (query === null) {
      return
    }
    let live = true
    setPages([])
    setError(null)
    reads
      .transactions({ ...query, page: 0, size: PAGE_SIZE })
      .then((page) => live && setPages([page]))
      .catch((e: Error) => live && setError(e.message))
    return () => {
      live = false
    }
    // The query is compared by value, through its key.
  }, [key, version])

  const more = () =>
    reads
      .transactions({ ...query, page: pages.length, size: PAGE_SIZE })
      .then((page) => setPages((current) => [...current, page]))
      .catch((e: Error) => setError(e.message))

  const items = pages.flatMap((p) => p.items)
  const total = pages[0]?.total ?? null
  return { items, total, error, more, hasMore: total !== null && items.length < total, loaded: pages.length > 0 }
}

function TxnRows({ items, showReason = false }: { items: Transaction[]; showReason?: boolean }) {
  const { open } = useTxnSheet()
  return (
    <ul className="txn-list">
      {items.map((t) => (
        <li key={t.id}>
          <button type="button" className="txn-row txn-row-wide row-button" onClick={() => open(t.id)}>
            <span className="txn-date">{formatDate(t.txnDate)}</span>
            <span className="txn-merchant">
              {t.merchant ?? t.rawDescription}
              <span className="txn-meta muted">
                {t.accountName}
                {' · '}
                {t.category ? t.category.name : 'Uncategorized'}
                {t.isTransfer && ' · transfer'}
                {showReason && t.aiReviewReason && ` · ${REVIEW_REASONS[t.aiReviewReason] ?? t.aiReviewReason}`}
                {showReason && t.transferExcluded && ' · marked not a transfer'}
              </span>
            </span>
            <span className="txn-amount">{formatMoney(t.amount)}</span>
          </button>
        </li>
      ))}
    </ul>
  )
}

function countText(total: number | null, what: string): string {
  return total === null ? '' : total === 1 ? `1 ${what}` : `${total} ${what}s`
}

/** All transactions of the month, filtered by category and account (UI-02's list part; docs/plans/phase-7.md §2.4). */
function AllTransactions({ month }: { month: string }) {
  const [params, setParams] = useSearchParams()
  const { accounts, categories } = useShellData()
  const { version } = useDataVersion()
  const category = params.get('category') ?? ''
  const accountId = params.get('accountId') ?? ''
  const { first, last } = monthDays(month)
  const query = {
    from: first,
    to: last,
    categoryId: /^\d+$/.test(category) ? category : undefined,
    uncategorized: category === 'none' ? true : undefined,
    accountId: /^\d+$/.test(accountId) ? accountId : undefined,
  }
  const list = usePages(query as Record<string, string | boolean | undefined>, version)

  const set = (name: string, value: string) =>
    setParams((current) => {
      const updated = new URLSearchParams(current)
      if (value) {
        updated.set(name, value)
      } else {
        updated.delete(name)
      }
      return updated
    })

  return (
    <>
      <div className="filters">
        <label>
          Category
          <select value={category} onChange={(e) => set('category', e.target.value)}>
            <option value="">All categories</option>
            <option value="none">Uncategorized</option>
            {categories.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          Account
          <select value={accountId} onChange={(e) => set('accountId', e.target.value)}>
            <option value="">All accounts</option>
            {accounts.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name}
              </option>
            ))}
          </select>
        </label>
      </div>
      {list.error && <p className="error">Couldn’t load the transactions: {list.error}</p>}
      {!list.error && !list.loaded && <p className="muted">Loading…</p>}
      {list.loaded && (
        <section className="card" aria-label="Transactions list">
          <p className="muted">
            {countText(list.total, 'transaction')} in {formatMonth(month)}
          </p>
          {list.items.length === 0 ? <p>No transactions match.</p> : <TxnRows items={list.items} />}
          {list.hasMore && (
            <button type="button" className="link-button" onClick={() => void list.more()}>
              Show more
            </button>
          )}
        </section>
      )}
    </>
  )
}

/**
 * What waits for you (ING-07, CAT-02, CAT-08): transactions without a category, and Transfer-category ones with no
 * match. Each opens its sheet, where it is corrected. Every month, not only the one picked.
 */
function Review({ queue }: { queue: Load<ReviewQueueResponse> }) {
  const { version } = useDataVersion()
  const { open } = useTxnSheet()
  const uncategorized = usePages({ uncategorized: true }, version)

  const suggested =
    queue.state === 'done' ? queue.value.items.filter((i) => i.kind === 'SUGGESTED_ONE_SIDED_TRANSFER') : []
  const elsewhere =
    queue.state === 'done' ? ELSEWHERE.filter((e) => queue.value.counts[e.key] > 0) : []

  return (
    <>
      <section className="card" aria-labelledby="review-uncategorized">
        <h2 id="review-uncategorized">Needs a category</h2>
        {uncategorized.error && <p className="error">Couldn’t load them: {uncategorized.error}</p>}
        {uncategorized.loaded &&
          (uncategorized.items.length === 0 ? (
            <p>Every transaction has a category.</p>
          ) : (
            <>
              <p className="muted">{countText(uncategorized.total, 'transaction')}, every month</p>
              <TxnRows items={uncategorized.items} showReason />
            </>
          ))}
        {uncategorized.hasMore && (
          <button type="button" className="link-button" onClick={() => void uncategorized.more()}>
            Show more
          </button>
        )}
      </section>
      <section className="card" aria-labelledby="review-transfers">
        <h2 id="review-transfers">Transfers without a match</h2>
        {queue.state === 'error' && <p className="error">Couldn’t load the review queue: {queue.message}</p>}
        {queue.state === 'done' &&
          (suggested.length === 0 ? (
            <p>None.</p>
          ) : (
            <>
              <p className="muted">
                In a transfer category, but no matching transaction in another account. If it isn’t a transfer, mark
                it so; if it is one with an account you don’t import, it can stay.
              </p>
              <ul className="txn-list">
                {suggested.map((item) => {
                  const txn = item.txn as {
                    id: number
                    txnDate: string
                    amount: string
                    rawDescription: string
                    accountName: string
                  }
                  return (
                    <li key={item.key as string}>
                      <button type="button" className="txn-row txn-row-wide row-button" onClick={() => open(txn.id)}>
                        <span className="txn-date">{formatDate(txn.txnDate)}</span>
                        <span className="txn-merchant">
                          {txn.rawDescription}
                          <span className="txn-meta muted">{txn.accountName}</span>
                        </span>
                        <span className="txn-amount">{formatMoney(txn.amount)}</span>
                      </button>
                    </li>
                  )
                })}
              </ul>
            </>
          ))}
      </section>
      {elsewhere.length > 0 && queue.state === 'done' && (
        <section className="card" aria-labelledby="review-elsewhere">
          <h2 id="review-elsewhere">Also in the review queue</h2>
          <ul>
            {elsewhere.map((e) => (
              <li key={e.key}>
                {queue.value.counts[e.key] === 1 ? `1 ${e.one}` : `${queue.value.counts[e.key]} ${e.many}`}
              </li>
            ))}
          </ul>
          <p className="muted">These are resolved through the API until the upload page (Phase 8).</p>
        </section>
      )}
    </>
  )
}

/**
 * Transactions (docs/plans/phase-7.md §2.4): every transaction of the month with filters, and the review queue's
 * transactions. A row opens its sheet, where its category and transfer state are corrected.
 */
export default function TransactionsPage() {
  const [params, setParams] = useSearchParams()
  const { month } = useMonth()
  const { version } = useDataVersion()
  const view = params.get('view') === 'review' ? 'review' : 'all'
  const [queue, setQueue] = useState<Load<ReviewQueueResponse>>({ state: 'loading' })

  useEffect(() => {
    let live = true
    reads
      .reviewQueue()
      .then((value) => live && setQueue({ state: 'done', value }))
      .catch((e: Error) => live && setQueue({ state: 'error', message: e.message }))
    return () => {
      live = false
    }
  }, [version])

  const show = (next: 'all' | 'review') =>
    setParams((current) => {
      const updated = new URLSearchParams(current)
      if (next === 'review') {
        updated.set('view', 'review')
      } else {
        updated.delete('view')
      }
      return updated
    })

  return (
    <section className="page">
      <h1>Transactions</h1>
      <div className="segmented" role="group" aria-label="Show">
        <button type="button" aria-pressed={view === 'all'} onClick={() => show('all')}>
          All
        </button>
        <button type="button" aria-pressed={view === 'review'} onClick={() => show('review')}>
          To review
        </button>
      </div>
      {view === 'all' && month && <AllTransactions month={month} />}
      {view === 'all' && !month && <p className="card">No statements yet.</p>}
      {view === 'review' && <Review queue={queue} />}
    </section>
  )
}
