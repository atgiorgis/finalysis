import { Link } from 'react-router-dom'
import type { AnswerSource, Citation, ChatGrounding, ChatUsage, LimitHit } from '../../api'
import { alertHref, drillHref, seriesHref } from '../../citationLinks'
import { answerParts, stripEmphasis } from '../../chatText'
import { formatDate, formatMoney, formatMonth } from '../../format'
import { useTxnSheet } from '../../shell/TxnSheet'
import { ALERT_LABELS } from '../alertText'

const LIMIT_TEXT: Record<LimitHit, string> = {
  ROUNDS: 'model calls',
  TOOL_CALLS: 'tool calls',
  TOKENS: 'length',
  TIME: 'time',
  COST: 'cost',
}

/** What a turn view needs, from a fresh answer or a stored one. */
export interface TurnEntry {
  key: string
  question: string
  answer: string | null
  answerSource: AnswerSource | null
  citations: Citation[]
  limitHit: LimitHit | null
  usage: ChatUsage | null
  grounding: ChatGrounding | null
  /** A request that failed before an answer: said in place of one. */
  error?: string
}

const anchor = (turn: string, marker: number) => `cite-${turn}-${marker}`

/** The answer as text, emphasis stripped, `[n]` markers linking to their citation below. */
function Answer({ entry }: { entry: TurnEntry }) {
  const cited = new Set(entry.citations.map((c) => c.marker))
  return (
    <p className="chat-answer">
      {answerParts(stripEmphasis(entry.answer ?? ''), cited).map((part, i) =>
        'marker' in part ? (
          <a key={i} href={`#${anchor(entry.key, part.marker)}`} className="chat-marker" aria-label={`citation ${part.marker}`}>
            [{part.marker}]
          </a>
        ) : (
          <span key={i}>{part.text}</span>
        ),
      )}
    </p>
  )
}

function CitationItem({ citation }: { citation: Citation }) {
  const { open } = useTxnSheet()
  switch (citation.kind) {
    case 'TRANSACTION': {
      const t = citation.txn
      if (t.status === 'GONE' || t.id === null) {
        return <span className="muted">A transaction no longer in your statements (its statement was deleted)</span>
      }
      const id = t.id
      return (
        <button type="button" className="link-button" onClick={() => open(id)}>
          {[formatDate(t.txnDate), t.merchant ?? '(no merchant)', formatMoney(t.amount), t.accountName]
            .filter(Boolean)
            .join(' · ')}
        </button>
      )
    }
    case 'TOTAL':
      return (
        <span>
          {citation.label}
          {citation.drillDowns.map((d, i) => (
            <span key={i}>
              {' · '}
              <Link to={drillHref(d, citation.label)}>
                {d.count === 1 ? 'its transaction' : `its ${d.count} transactions`}
                {citation.drillDowns.length > 1 && ` (part ${i + 1} of ${citation.drillDowns.length})`}
              </Link>
            </span>
          ))}
        </span>
      )
    case 'ALERT':
      return (
        <Link to={alertHref(citation)}>
          {ALERT_LABELS[citation.type]} alert · {formatMonth(citation.month)}
        </Link>
      )
    case 'SERIES':
      return (
        <span>
          <Link to={seriesHref(citation)}>{citation.merchant}: recurring charges</Link>
          {' · '}
          <Link to={drillHref(citation.drillDown, `${citation.merchant}: its charges`)}>its charges</Link>
        </span>
      )
  }
}

/** One question and its answer: the text, its citations as links, how it was checked, its cost, any limit. */
export default function ChatTurnView({ entry }: { entry: TurnEntry }) {
  return (
    <li className="chat-turn">
      <p className="chat-question">{entry.question}</p>
      {entry.error && (
        <p className="chat-error" role="alert">
          {entry.error}
        </p>
      )}
      {!entry.error && entry.answerSource === 'ERROR' && (
        <p className="chat-error">This question failed: the AI service didn’t answer.</p>
      )}
      {entry.answer !== null && !entry.error && (
        <div className="chat-reply">
          {entry.answerSource === 'FALLBACK' && (
            <p className="chat-note">The model’s answer couldn’t be checked, so here are the figures it used.</p>
          )}
          <Answer entry={entry} />
          {entry.limitHit && (
            <p className="chat-note">
              This answer stopped early: it reached the limit on {LIMIT_TEXT[entry.limitHit]} for one question.
            </p>
          )}
          {entry.citations.length > 0 && (
            <ol className="chat-citations" aria-label="Citations">
              {[...entry.citations]
                .sort((a, b) => a.marker - b.marker)
                .map((c) => (
                  <li key={c.marker} id={anchor(entry.key, c.marker)} value={c.marker}>
                    <CitationItem citation={c} />
                  </li>
                ))}
            </ol>
          )}
          <p className="chat-meta muted">
            {entry.usage?.model ?? 'model unknown'}
            {' · '}
            {entry.usage?.estimatedCost ? `${formatMoney(entry.usage.estimatedCost)} estimated` : 'no price'}
            {entry.grounding && entry.grounding.figuresChecked > 0 && (
              <>
                {' · '}
                {entry.grounding.figuresChecked === 1 ? '1 figure checked' : `${entry.grounding.figuresChecked} figures checked`}
              </>
            )}
          </p>
        </div>
      )}
    </li>
  )
}
