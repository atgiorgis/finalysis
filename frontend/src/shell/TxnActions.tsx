import { useState, type FormEvent } from 'react'
import {
  ApiProblem,
  corrections,
  type Category,
  type CategoryKind,
  type CorrectionResult,
  type Transaction,
  type UndoResult,
} from '../api'

const GROUPS: { kind: CategoryKind; label: string }[] = [
  { kind: 'EXPENSE', label: 'Spending' },
  { kind: 'INCOME', label: 'Income' },
  { kind: 'TRANSFER', label: 'Transfers' },
]

function plural(n: number, one: string, many: string): string {
  return n === 1 ? `1 ${one}` : `${n} ${many}`
}

/** What a correction did, in a sentence or two (CAT-04's response). */
export function correctionMessage(result: CorrectionResult): string {
  const name = result.txn.category?.name ?? 'that category'
  if (result.ruleAction === 'NONE') {
    return `Saved as ${name}, for this transaction only.`
  }
  const parts = [`Saved as ${name}.`]
  const merchant = result.txn.merchant ?? 'this merchant'
  parts.push(
    result.otherTxnsUpdated === 0
      ? `Future ${merchant} transactions will follow.`
      : `${plural(result.otherTxnsUpdated, `other ${merchant} transaction`, `other ${merchant} transactions`)} changed too, and future ones will follow.`,
  )
  if (result.otherTxnsKeptUser > 0) {
    parts.push(`${plural(result.otherTxnsKeptUser, 'other kept the category you set', 'others kept the categories you set')}.`)
  }
  if (result.otherTxnsKeptOtherRule > 0) {
    parts.push(`${plural(result.otherTxnsKeptOtherRule, 'other follows', 'others follow')} another rule of yours.`)
  }
  return parts.join(' ')
}

/** What an undo did. */
export function undoMessage(result: UndoResult): string {
  const now = result.txn.category ? `now ${result.txn.category.name}` : 'now uncategorized'
  const rule =
    result.ruleAction === 'REMOVED'
      ? ` Its merchant rule was removed${
          result.otherTxnsUpdated > 0
            ? `, and ${plural(result.otherTxnsUpdated, 'other transaction', 'other transactions')} changed back`
            : ''
        }.`
      : ''
  return `Your category was removed and the rules ran again: ${now}.${rule}`
}

/**
 * The corrections a transaction allows (docs/plans/phase-7.md §2.4, Phase 4's API): set its category (by default for
 * the merchant's other transactions too), undo a category you set, mark it not a transfer or undo that. With alert
 * dismissal, the only writes in the UI. Every result is said in words; a refused request shows the API's own reason.
 */
export default function TxnActions({
  txn,
  categories,
  onChanged,
}: {
  txn: Transaction
  categories: Category[]
  /** After a write: the transaction as it now is (when the response has it), so the sheet and the pages re-read. */
  onChanged: (txn?: Transaction) => void
}) {
  const [categoryId, setCategoryId] = useState(txn.category ? String(txn.category.id) : '')
  const [applyToMerchant, setApplyToMerchant] = useState(true)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  async function run<T>(action: () => Promise<T>, done: (result: T) => { text: string; txn?: Transaction }) {
    setBusy(true)
    setError(null)
    setMessage(null)
    try {
      const result = done(await action())
      setMessage(result.text)
      onChanged(result.txn)
    } catch (e) {
      setError(e instanceof ApiProblem || e instanceof Error ? e.message : 'The request failed.')
    } finally {
      setBusy(false)
    }
  }

  const save = (e: FormEvent) => {
    e.preventDefault()
    const chosen = categories.find((c) => String(c.id) === categoryId)
    if (!chosen) {
      return
    }
    void run(
      () => corrections.setCategory(txn.id, chosen.id, applyToMerchant),
      (result) => ({ text: correctionMessage(result), txn: result.txn }),
    )
  }

  const transferish = txn.isTransfer || txn.category?.kind === 'TRANSFER'
  const unchanged = categoryId === (txn.category ? String(txn.category.id) : '')

  return (
    <section className="txn-actions" aria-label="Corrections">
      <form onSubmit={save} className="txn-category-form">
        <label>
          Category
          <select value={categoryId} onChange={(e) => setCategoryId(e.target.value)} disabled={busy}>
            {!txn.category && <option value="">Choose a category</option>}
            {GROUPS.map((group) => (
              <optgroup key={group.kind} label={group.label}>
                {categories
                  .filter((c) => c.kind === group.kind)
                  .map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.name}
                    </option>
                  ))}
              </optgroup>
            ))}
          </select>
        </label>
        <label className="checkbox">
          <input
            type="checkbox"
            checked={applyToMerchant}
            onChange={(e) => setApplyToMerchant(e.target.checked)}
            disabled={busy}
          />
          Also for {txn.merchant ?? 'this merchant'}’s other transactions
        </label>
        <button type="submit" className="button-primary" disabled={busy || unchanged || !categoryId}>
          Save category
        </button>
      </form>
      <div className="txn-action-buttons">
        {txn.categorySource === 'USER' && (
          <button
            type="button"
            className="button-secondary"
            disabled={busy}
            onClick={() =>
              void run(
                () => corrections.undoCategory(txn.id),
                (result) => ({ text: undoMessage(result), txn: result.txn }),
              )
            }
          >
            Undo my category
          </button>
        )}
        {transferish && !txn.transferExcluded && (
          <button
            type="button"
            className="button-secondary"
            disabled={busy}
            onClick={() =>
              void run(
                () => corrections.notTransfer(txn.id),
                (result) => ({
                  text: `Marked not a transfer${
                    result.removedLinkId !== null ? ', and its transfer link was removed' : ''
                  }. It now counts as spending or income, and needs a category if it has none.`,
                }),
              )
            }
          >
            Not a transfer
          </button>
        )}
        {txn.transferExcluded && (
          <button
            type="button"
            className="button-secondary"
            disabled={busy}
            onClick={() =>
              void run(
                () => corrections.undoNotTransfer(txn.id),
                () => ({ text: 'It can be matched as a transfer again at the next detection run.' }),
              )
            }
          >
            Undo “not a transfer”
          </button>
        )}
      </div>
      {busy && <p className="muted">Saving…</p>}
      {message && (
        <p className="txn-action-result" role="status">
          {message}
        </p>
      )}
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
    </section>
  )
}
