import { useState } from 'react'
import {
  imports,
  importReviewItems,
  type CompetingCandidateItem,
  type PossibleMatchItem,
  type ReviewQueueResponse,
  type StatementListItem,
  type TransferCandidatesItem,
  type TxnSummary,
} from '../../api'
import { formatDate, formatMoney, formatPeriod } from '../../format'
import { useDataVersion } from '../../shell/ShellData'
import { useTxnSheet } from '../../shell/TxnSheet'
import { ConfirmDelete, deleteQuestion, deletedText } from './StatementsList'
import { BALANCE_TEXT, REASON_TEXT, daysApartText, plural } from './importText'

/**
 * Runs one review action at a time: while it runs its buttons are disabled; then the result is said in words, or the
 * API's refusal is shown, and every page reads again.
 */
function useActions() {
  const { dataChanged } = useDataVersion()
  const [busy, setBusy] = useState<string | null>(null)
  const [done, setDone] = useState<string | null>(null)
  const [error, setError] = useState<{ key: string; message: string } | null>(null)

  async function run(key: string, action: () => Promise<unknown>, success: string, shell = false) {
    setBusy(key)
    setError(null)
    setDone(null)
    try {
      await action()
      setDone(success)
      dataChanged({ shell })
    } catch (e) {
      setError({ key, message: (e as Error).message })
    } finally {
      setBusy(null)
    }
  }

  return { busy, done, setDone, error, run }
}

type Actions = ReturnType<typeof useActions>

function ActionError({ actions, id }: { actions: Actions; id: string }) {
  return actions.error?.key === id ? (
    <p role="alert" className="error">
      {actions.error.message}
    </p>
  ) : null
}

/** One transaction of a transfer item; it opens the sheet, where it can be marked not a transfer or recategorized. */
function TxnLine({ txn }: { txn: TxnSummary }) {
  const { open } = useTxnSheet()
  return (
    <button type="button" className="txn-row txn-row-wide row-button" onClick={() => open(txn.id)}>
      <span className="txn-date">{formatDate(txn.txnDate)}</span>
      <span className="txn-merchant">
        {txn.rawDescription}
        <span className="txn-meta muted">{txn.accountName}</span>
      </span>
      <span className="txn-amount">{formatMoney(txn.amount)}</span>
    </button>
  )
}

/** `Fixture Checking −$200.00 → Fixture Savings $200.00`, for a pair's button. */
function pairText(out: TxnSummary | undefined, into: TxnSummary | undefined): string {
  if (!out || !into) {
    return 'these two'
  }
  return `${out.accountName} ${formatMoney(out.amount)} → ${into.accountName} ${formatMoney(into.amount)}`
}

function CandidateGroup({ item, actions }: { item: TransferCandidatesItem; actions: Actions }) {
  const byId = new Map(item.txns.map((t) => [t.id, t]))
  return (
    <li className="review-item">
      <ul className="txn-list">
        {item.txns.map((t) => (
          <li key={t.id}>
            <TxnLine txn={t} />
          </li>
        ))}
      </ul>
      <ul className="review-pairs">
        {item.pairs.map((pair) => {
          const id = `${item.key}:${pair.outTxnId}-${pair.inTxnId}`
          const label = pairText(byId.get(pair.outTxnId), byId.get(pair.inTxnId))
          return (
            <li key={id}>
              <button
                type="button"
                className="button-secondary"
                disabled={actions.busy !== null}
                aria-label={`Link ${label}`}
                onClick={() =>
                  void actions.run(id, () => imports.link({ outTxnId: pair.outTxnId, inTxnId: pair.inTxnId }), 'Linked as one transfer.')
                }
              >
                Link these two
              </button>
              <span className="muted">
                {' '}
                {label} · {daysApartText(pair.daysApart)}
              </span>
              <ActionError actions={actions} id={id} />
            </li>
          )
        })}
      </ul>
    </li>
  )
}

function CompetingItem({ item, actions }: { item: CompetingCandidateItem; actions: Actions }) {
  const keep = `${item.key}:keep`
  const unlink = `${item.key}:unlink`
  return (
    <li className="review-item">
      <p>Linked automatically:</p>
      <ul className="txn-list">
        <li>
          <TxnLine txn={item.out} />
        </li>
        <li>
          <TxnLine txn={item.in} />
        </li>
      </ul>
      <p>{item.competitors.length === 1 ? 'A later transaction could match it too:' : 'Later transactions could match it too:'}</p>
      <ul className="txn-list">
        {item.competitors.map((t) => (
          <li key={t.id}>
            <TxnLine txn={t} />
          </li>
        ))}
      </ul>
      <div className="import-actions">
        <button
          type="button"
          className="button-secondary"
          disabled={actions.busy !== null}
          onClick={() => void actions.run(keep, () => imports.confirmLink(item.linkId), 'Kept the link.')}
        >
          Keep this link
        </button>
        <button
          type="button"
          className="button-secondary"
          disabled={actions.busy !== null}
          onClick={() =>
            void actions.run(unlink, () => imports.unlink(item.linkId), 'Unlinked. The transactions wait here to be matched again.')
          }
        >
          Unlink
        </button>
      </div>
      <ActionError actions={actions} id={keep} />
      <ActionError actions={actions} id={unlink} />
    </li>
  )
}

function PossibleMatch({ item, actions }: { item: PossibleMatchItem; actions: Actions }) {
  const join = `${item.key}:join`
  const dismiss = `${item.key}:dismiss`
  return (
    <li className="review-item">
      <ul className="txn-list">
        <li>
          <TxnLine txn={item.out} />
        </li>
        <li>
          <TxnLine txn={item.in} />
        </li>
      </ul>
      <p className="muted">Both are marked one-sided · {daysApartText(item.daysApart)}</p>
      <div className="import-actions">
        <button
          type="button"
          className="button-secondary"
          disabled={actions.busy !== null}
          onClick={() =>
            void actions.run(join, () => imports.join(item.outLinkId, item.inLinkId), 'Joined as one transfer.')
          }
        >
          Join as one transfer
        </button>
        <button
          type="button"
          className="button-secondary"
          disabled={actions.busy !== null}
          onClick={() =>
            void actions.run(dismiss, () => imports.dismissJoin(item.outLinkId, item.inLinkId), 'Kept apart.')
          }
        >
          Not the same transfer
        </button>
      </div>
      <ActionError actions={actions} id={join} />
      <ActionError actions={actions} id={dismiss} />
    </li>
  )
}

function Count({ count, noun, nouns }: { count: number; noun: string; nouns?: string }) {
  return <p className="muted">{plural(count, noun, nouns)}</p>
}

/**
 * The review queue's kinds the Import page resolves (ING-07, ING-13): statements that need review, uploads no parser
 * recognized, and transfer matches to decide, each with the existing actions. Counts are the queue's own.
 */
export default function ReviewSections({
  queue,
  statements,
}: {
  queue: { state: 'loading' } | { state: 'error'; message: string } | { state: 'done'; value: ReviewQueueResponse }
  /** For a review statement's delete question: the rows it owns, from the statements list. */
  statements: Map<number, StatementListItem>
}) {
  const actions = useActions()
  const [deleting, setDeleting] = useState<number | null>(null)

  if (queue.state !== 'done') {
    return (
      <section className="card" id="review" aria-labelledby="review-title">
        <h2 id="review-title">Review queue</h2>
        {queue.state === 'loading' && <p className="muted">Loading…</p>}
        {queue.state === 'error' && <p className="error">Couldn’t load the review queue: {queue.message}</p>}
      </section>
    )
  }
  const { counts } = queue.value
  const items = importReviewItems(queue.value)
  const noTransfers = [items.candidates, items.competing, items.possibleMatches].every((list) => list.length === 0)

  return (
    <section className="card" id="review" aria-labelledby="review-title">
      <h2 id="review-title">Review queue</h2>
      {actions.done && (
        <p role="status" className="import-done">
          {actions.done}
        </p>
      )}

      <section aria-labelledby="review-statements">
        <h3 id="review-statements">Statements to review</h3>
        <Count count={counts.statements} noun="statement" />
        {items.statements.length === 0 ? (
          <p>None.</p>
        ) : (
          <ul className="review-list">
            {items.statements.map((item) => {
              const accept = `statement-${item.statementId}:accept`
              const period = formatPeriod(item.periodStart, item.periodEnd)
              return (
                <li key={item.statementId} className="review-item">
                  <p className="review-item-title">
                    {item.accountName} · {period}
                  </p>
                  <p className="muted">
                    {item.fileName} · {BALANCE_TEXT[item.balanceCheck]}
                  </p>
                  <ul className="import-warnings">
                    {item.warnings.map((w, i) => (
                      <li key={i}>{w}</li>
                    ))}
                  </ul>
                  {deleting === item.statementId ? (
                    <ConfirmDelete
                      statementId={item.statementId}
                      question={deleteQuestion(
                        period,
                        item.accountName,
                        statements.get(item.statementId)?.transactionCount ?? null,
                      )}
                      onDone={(result) => {
                        setDeleting(null)
                        actions.setDone(deletedText(result))
                      }}
                      onCancel={() => setDeleting(null)}
                    />
                  ) : (
                    <div className="import-actions">
                      <button
                        type="button"
                        className="button-secondary"
                        disabled={actions.busy !== null}
                        onClick={() =>
                          void actions.run(
                            accept,
                            () => imports.reviewStatement(item.statementId),
                            'Statement accepted as it is; its warnings stay on record.',
                            true,
                          )
                        }
                      >
                        Accept as it is
                      </button>
                      <button type="button" className="button-secondary" onClick={() => setDeleting(item.statementId)}>
                        Delete
                      </button>
                    </div>
                  )}
                  <ActionError actions={actions} id={accept} />
                </li>
              )
            })}
          </ul>
        )}
      </section>

      <section aria-labelledby="review-uploads">
        <h3 id="review-uploads">Uploads not recognized</h3>
        <Count count={counts.unparsedUploads} noun="upload" />
        {items.uploads.length === 0 ? (
          <p>None.</p>
        ) : (
          <>
            <p className="muted">Nothing was imported from these. Importing the file again once it can be read clears it.</p>
            <ul className="review-list">
              {items.uploads.map((item) => {
                const id = `upload-${item.uploadId}`
                return (
                  <li key={item.uploadId} className="review-item">
                    <p className="review-item-title">{item.fileName}</p>
                    <p className="muted">
                      {item.accountName} · uploaded {formatDate(item.since.slice(0, 10), true)}
                    </p>
                    <p>{REASON_TEXT[item.reason]}</p>
                    <div className="import-actions">
                      <button
                        type="button"
                        className="button-secondary"
                        disabled={actions.busy !== null}
                        onClick={() =>
                          void actions.run(id, () => imports.dismissUpload(item.uploadId), 'Upload dismissed.', true)
                        }
                      >
                        Dismiss
                      </button>
                    </div>
                    <ActionError actions={actions} id={id} />
                  </li>
                )
              })}
            </ul>
          </>
        )}
      </section>

      <section aria-labelledby="review-transfers">
        <h3 id="review-transfers">Transfer matches to decide</h3>
        {noTransfers && <p>None.</p>}
        {items.candidates.length > 0 && (
          <section aria-labelledby="review-candidates">
            <h4 id="review-candidates">Which transactions are one transfer?</h4>
            <Count count={counts.transferCandidates} noun="group" />
            <p className="muted">
              More than one transaction could be the other side. Link the right pair; a transaction that isn’t a
              transfer can be marked so from its sheet.
            </p>
            <ul className="review-list">
              {items.candidates.map((item) => (
                <CandidateGroup key={item.key} item={item} actions={actions} />
              ))}
            </ul>
          </section>
        )}
        {items.competing.length > 0 && (
          <section aria-labelledby="review-competing">
            <h4 id="review-competing">An automatic link with a rival</h4>
            <Count count={counts.competingCandidates} noun="link" />
            <ul className="review-list">
              {items.competing.map((item) => (
                <CompetingItem key={item.key} item={item} actions={actions} />
              ))}
            </ul>
          </section>
        )}
        {items.possibleMatches.length > 0 && (
          <section aria-labelledby="review-possible">
            <h4 id="review-possible">One-sided transfers that may be one</h4>
            <Count count={counts.possibleMatches} noun="possible match" nouns="possible matches" />
            <ul className="review-list">
              {items.possibleMatches.map((item) => (
                <PossibleMatch key={item.key} item={item} actions={actions} />
              ))}
            </ul>
          </section>
        )}
      </section>
    </section>
  )
}
