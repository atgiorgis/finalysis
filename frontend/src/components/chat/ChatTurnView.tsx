import { Link } from 'react-router-dom'
import type { AiStatus, AnswerSource, Citation, ChatGrounding, ChatUsage, LimitHit } from '../../api'
import { alertHref, drillHref, seriesHref } from '../../citationLinks'
import { answerParts, stripEmphasis } from '../../chatText'
import { AI_NAME, costText } from '../../corgi'
import { formatDate, formatMoney, formatMonth } from '../../format'
import { CalculatorIcon } from '../../shell/icons'
import { useShellData } from '../../shell/ShellData'
import { useTxnSheet } from '../../shell/TxnSheet'
import { ALERT_LABELS } from '../alertText'
import CorgiAvatar from './CorgiAvatar'

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

/** The turn's cost as the backend estimated it, exact; `$0.00 · local` with a local model. */
function turnCost(usage: ChatUsage | null, ai: AiStatus | undefined): string {
  return costText(usage?.estimatedCost, ai, (v) => (v ? `${formatMoney(v)} estimated` : 'no price'))
}

/**
 * One question and its answer: Corgi's avatar and name, the text, its citations as links, the figures note when the
 * backend checked any figure, then the model and cost; any limit.
 */
export default function ChatTurnView({ entry }: { entry: TurnEntry }) {
  const { status } = useShellData()
  const checked = entry.grounding?.figuresChecked ?? 0
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
        <div className="chat-answer-row">
          <CorgiAvatar />
          <div className="chat-answer-body">
            <p className="chat-speaker">{AI_NAME}</p>
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
            </div>
            {checked > 0 && (
              <p className="chat-figures muted">
                <CalculatorIcon />
                <span>Figures computed by Finalysis, not by the model · {checked} checked</span>
              </p>
            )}
            <p className="chat-meta muted">
              {entry.usage?.model ?? 'model unknown'}
              {' · '}
              {turnCost(entry.usage, status?.ai)}
            </p>
          </div>
        </div>
      )}
    </li>
  )
}

/** While a question is being answered: the question, then Corgi thinking. */
export function PendingTurn({ question }: { question: string }) {
  return (
    <li className="chat-turn">
      <p className="chat-question">{question}</p>
      <div className="chat-answer-row chat-thinking" role="status">
        <CorgiAvatar />
        <span className="muted">{AI_NAME} is thinking…</span>
      </div>
    </li>
  )
}
