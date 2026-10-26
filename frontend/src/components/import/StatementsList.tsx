import { useState } from 'react'
import { Link } from 'react-router-dom'
import { imports, type Account, type StatementDeleteResult, type StatementListItem } from '../../api'
import { formatDate, formatPeriod } from '../../format'
import { useDataVersion } from '../../shell/ShellData'
import { BALANCE_TEXT, STATUS_TEXT, accountLabel, plural } from './importText'

/** What a delete removes, said before it happens: the statement and the rows it imported (ING-07). */
export function deleteQuestion(period: string, account: string, transactionCount: number | null): string {
  const rows =
    transactionCount === null ? 'the transactions it imported' : `the ${plural(transactionCount, 'transaction')} it imported`
  return `Delete the statement ${period} of ${account}? This removes ${rows}, and any transfer links on them. The file can be imported again afterwards.`
}

export function deletedText(result: StatementDeleteResult): string {
  return `Statement deleted: ${plural(result.transactionsDeleted, 'transaction')} and ${plural(result.transferLinksRemoved, 'transfer link')} removed.`
}

/**
 * Asks before `DELETE /api/statements/{id}`, then says what the response removed. After it, every page reads again,
 * the shell's accounts and months included.
 */
export function ConfirmDelete({
  statementId,
  question,
  onDone,
  onCancel,
}: {
  statementId: number
  question: string
  onDone: (result: StatementDeleteResult) => void
  onCancel: () => void
}) {
  const { dataChanged } = useDataVersion()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const titleId = `delete-${statementId}`

  async function confirm() {
    setBusy(true)
    setError(null)
    try {
      const result = await imports.deleteStatement(statementId)
      onDone(result)
      dataChanged({ shell: true })
    } catch (e) {
      setError((e as Error).message)
      setBusy(false)
    }
  }

  return (
    <div role="alertdialog" aria-labelledby={titleId} className="confirm">
      <p id={titleId}>{question}</p>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      <div className="import-actions">
        <button type="button" className="button-danger" disabled={busy} onClick={() => void confirm()}>
          {busy ? 'Deleting…' : 'Delete statement'}
        </button>
        <button type="button" className="button-secondary" disabled={busy} onClick={onCancel}>
          Cancel
        </button>
      </div>
    </div>
  )
}

function StatementRow({
  account,
  statement,
  onDeleted,
}: {
  account: Account
  statement: StatementListItem
  onDeleted: (text: string) => void
}) {
  const [confirming, setConfirming] = useState(false)
  const period = formatPeriod(statement.periodStart, statement.periodEnd)
  return (
    <li className="statement-row">
      <div className="statement-main">
        <Link to={`/transactions?statement=${statement.id}`} className="statement-link">
          {period}
        </Link>
        <span className="txn-meta muted">
          {plural(statement.transactionCount, 'transaction')}
          {statement.parsedCount !== null && ` · ${plural(statement.parsedCount, 'row')} in the file`}
          {' · '}
          {STATUS_TEXT[statement.status]}
          {statement.status === 'NEEDS_REVIEW' && statement.reviewedAt && ', accepted'}
          {' · '}
          {BALANCE_TEXT[statement.balanceCheck]}
          {' · imported '}
          {formatDate(statement.importedAt.slice(0, 10), true)}
        </span>
      </div>
      {!confirming && (
        <button
          type="button"
          className="button-secondary"
          aria-label={`Delete the statement ${period}`}
          onClick={() => setConfirming(true)}
        >
          Delete
        </button>
      )}
      {confirming && (
        <ConfirmDelete
          statementId={statement.id}
          question={deleteQuestion(period, accountLabel(account), statement.transactionCount)}
          onDone={(result) => {
            setConfirming(false)
            onDeleted(deletedText(result))
          }}
          onCancel={() => setConfirming(false)}
        />
      )}
    </li>
  )
}

export type StatementsByAccount = Map<number, StatementListItem[] | { error: string }>

/**
 * Every statement, grouped by account, each group newest period first as the API returns it (one read per account,
 * `GET /api/accounts/{id}/statements`). A statement opens the Transactions tab filtered to it.
 */
export default function StatementsList({
  accounts,
  statements,
  deleted,
  onDeleted,
}: {
  accounts: Account[]
  statements: StatementsByAccount | null
  deleted: string | null
  onDeleted: (text: string) => void
}) {
  return (
    <section className="card" aria-labelledby="statements-title">
      <h2 id="statements-title">Statements</h2>
      {deleted && (
        <p role="status" className="import-done">
          {deleted}
        </p>
      )}
      {statements === null && <p className="muted">Loading…</p>}
      {statements !== null && accounts.length === 0 && <p>No accounts yet: add one above, then import its statements.</p>}
      {statements !== null &&
        accounts.map((account) => {
          const list = statements.get(account.id)
          return (
            <section key={account.id} className="statement-group" aria-labelledby={`statements-${account.id}`}>
              <h3 id={`statements-${account.id}`}>
                {accountLabel(account)}
                {account.closedOn && <span className="muted"> · closed</span>}
              </h3>
              {list === undefined && <p className="muted">Loading…</p>}
              {list !== undefined && 'error' in list && (
                <p className="error">Couldn’t load its statements: {list.error}</p>
              )}
              {Array.isArray(list) && list.length === 0 && <p className="muted">No statements yet.</p>}
              {Array.isArray(list) && list.length > 0 && (
                <ul className="statement-list">
                  {list.map((statement) => (
                    <StatementRow key={statement.id} account={account} statement={statement} onDeleted={onDeleted} />
                  ))}
                </ul>
              )}
            </section>
          )
        })}
    </section>
  )
}
