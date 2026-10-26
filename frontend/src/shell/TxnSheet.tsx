import { useCallback, useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { reads, type CategorySource, type Transaction } from '../api'
import { AI_NAME } from '../corgi'
import { formatDate, formatMoney } from '../format'
import { useDataVersion, useShellData } from './ShellData'
import TxnActions from './TxnActions'

const SOURCE_TEXT: Record<CategorySource, string> = {
  RULE: 'set by a rule',
  AI: `suggested by ${AI_NAME}`,
  USER: 'set by you',
}

/** Opens and closes the transaction sheet on whatever page is showing: `?txn=<id>` (docs/plans/phase-7.md §4.4). */
export function useTxnSheet(): { open: (id: number) => void; close: () => void; id: string | null } {
  const [params, setParams] = useSearchParams()
  const id = params.get('txn')
  const open = useCallback(
    (txn: number) =>
      setParams((current) => {
        const updated = new URLSearchParams(current)
        updated.set('txn', String(txn))
        return updated
      }),
    [setParams],
  )
  const close = useCallback(
    () =>
      setParams((current) => {
        const updated = new URLSearchParams(current)
        updated.delete('txn')
        return updated
      }),
    [setParams],
  )
  return { open, close, id: id && /^\d{1,18}$/.test(id) ? id : null }
}

function Facts({ txn }: { txn: Transaction }) {
  const category = txn.category
    ? `${txn.category.name}${txn.categorySource ? ` (${SOURCE_TEXT[txn.categorySource]})` : ''}`
    : 'Uncategorized'
  return (
    <dl className="facts">
      <dt>Date</dt>
      <dd>
        {formatDate(txn.txnDate, true)}
        {txn.postDate && txn.postDate !== txn.txnDate && ` · posted ${formatDate(txn.postDate, true)}`}
      </dd>
      <dt>Account</dt>
      <dd>{txn.accountName}</dd>
      <dt>Category</dt>
      <dd>{category}</dd>
      {(txn.isTransfer || txn.transferExcluded) && (
        <div className="facts-row">
          <dt>Transfer</dt>
          <dd>
            {txn.isTransfer
              ? 'Between your own accounts: not counted as spending or income'
              : 'Marked not a transfer'}
          </dd>
        </div>
      )}
      <dt>Description</dt>
      <dd>{txn.rawDescription}</dd>
      <dt>Statement</dt>
      <dd>
        {formatDate(txn.statement.periodStart, true)} to {formatDate(txn.statement.periodEnd, true)}
      </dd>
    </dl>
  )
}

/**
 * A transaction and the statement line it came from (UI-04): a dialog over the page on desktop, a bottom sheet on a
 * phone (UI-07), with the corrections it allows (step 4, `TxnActions`).
 */
export default function TxnSheet() {
  const { id, close } = useTxnSheet()
  const { categories } = useShellData()
  const { dataChanged } = useDataVersion()
  const [txn, setTxn] = useState<Transaction | null>(null)
  const [error, setError] = useState<string | null>(null)
  const heading = useRef<HTMLHeadingElement>(null)

  useEffect(() => {
    if (!id) {
      return
    }
    let live = true
    setTxn(null)
    setError(null)
    reads
      .transaction(id)
      .then((t) => live && setTxn(t))
      .catch((e: Error) => live && setError(e.message))
    return () => {
      live = false
    }
  }, [id])

  useEffect(() => {
    if (id) {
      heading.current?.focus()
    }
  }, [id, txn?.id])

  /** After a write: show the transaction as it now is, and let every page re-read. */
  const changed = (updated?: Transaction) => {
    dataChanged()
    if (updated) {
      setTxn(updated)
    } else if (id) {
      reads
        .transaction(id)
        .then(setTxn)
        .catch((e: Error) => setError(e.message))
    }
  }

  useEffect(() => {
    if (!id) {
      return
    }
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && close()
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [id, close])

  if (!id) {
    return null
  }
  return (
    <div className="sheet-backdrop" onClick={close}>
      <div
        className="sheet"
        role="dialog"
        aria-modal="true"
        aria-labelledby="txn-sheet-title"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="details-head">
          <p className="muted details-crumb">Transaction</p>
          <button type="button" className="link-button" onClick={close}>
            Close
          </button>
        </div>
        <h2 id="txn-sheet-title" ref={heading} tabIndex={-1}>
          {txn ? (txn.merchant ?? txn.rawDescription) : error ? 'Transaction not found' : 'Loading…'}
        </h2>
        {error && <p className="error">Couldn’t load this transaction: {error}</p>}
        {txn && (
          <>
            <p className="details-total">{formatMoney(txn.amount)}</p>
            <Facts txn={txn} />
            <h3>Correct it</h3>
            <TxnActions key={txn.id} txn={txn} categories={categories} onChanged={changed} />
            <h3>Statement line</h3>
            {txn.sourceLine ? (
              <figure className="source-line">
                <figcaption className="muted">
                  {txn.sourceLine.number !== null ? `Line ${txn.sourceLine.number}` : 'Line'} of the statement file,
                  as printed
                </figcaption>
                <pre>{txn.sourceLine.text ?? '(the line’s text wasn’t kept)'}</pre>
              </figure>
            ) : (
              <p className="muted">This import didn’t keep the statement line.</p>
            )}
          </>
        )}
      </div>
    </div>
  )
}
