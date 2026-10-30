import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import {
  ApiProblem,
  askTurn,
  chat,
  createConversation,
  reads,
  type AiStatus,
  type ChatDailyUsage,
  type ChatTurn,
  type Conversation,
  type ConversationItem,
  type StoredTurn,
} from '../api'
import ChatTurnView, { PendingTurn, type TurnEntry } from '../components/chat/ChatTurnView'
import CorgiAvatar from '../components/chat/CorgiAvatar'
import { AI_NAME, CHAT_TAGLINE, costText, isOff, offText, pathBadge } from '../corgi'
import { formatCost, formatCount, formatDate, formatMoney } from '../format'
import { useDataVersion, useShellData } from '../shell/ShellData'

/** The longest question the API takes (`TurnRequest`: 1 to 1,000 characters). */
export const MAX_MESSAGE = 1000

const PROBLEM = 'urn:finalysis:problem:'

/** A refused or failed turn, said plainly (docs/plans/phase-7.md §5.3). */
async function problemText(e: unknown): Promise<string> {
  if (!(e instanceof ApiProblem)) {
    return e instanceof Error ? `The request failed: ${e.message}` : 'The request failed.'
  }
  switch (e.type) {
    case `${PROBLEM}ai-off`: {
      // As the backend says it, with the reason the selected path can't run (Phase 9)
      const reason = typeof e.body.reason === 'string' ? ` (${e.body.reason})` : ''
      return `${e.message}${reason}`
    }
    case `${PROBLEM}chat-budget`: {
      const cap = await reads
        .chatUsage()
        .then((u) => (u.maxDailyCost ? ` (${formatMoney(u.maxDailyCost)})` : ''))
        .catch(() => '')
      return `Today’s chat budget${cap} is used up. It resets at midnight.`
    }
    case `${PROBLEM}chat-busy`:
      return 'Chat is busy with other questions. Try again in a moment.'
    case `${PROBLEM}turn-in-progress`:
      return 'This conversation is still answering the last question.'
    case `${PROBLEM}ai-unavailable`:
      return 'The AI service didn’t answer. Try the question again.'
    default:
      return e.message
  }
}

function fromStored(conversationId: number, t: StoredTurn): TurnEntry {
  return {
    key: `${conversationId}-${t.turn}`,
    question: t.question,
    answer: t.answer,
    answerSource: t.answerSource,
    citations: t.citations,
    limitHit: t.limitHit,
    usage: t.usage,
    grounding: t.grounding,
  }
}

function fromTurn(question: string, t: ChatTurn): TurnEntry {
  return {
    key: `${t.conversationId}-${t.turn}`,
    question,
    answer: t.answer,
    answerSource: t.answerSource,
    citations: t.citations,
    limitHit: t.limitHit,
    usage: t.usage,
    grounding: t.grounding ?? null,
  }
}

/** "3 turns · $0.03": the count and the backend's sum; no cost shown when no turn had a price. */
export function conversationLine(c: ConversationItem, ai: AiStatus | null | undefined): string {
  const turns = c.turns === 1 ? '1 turn' : `${c.turns} turns`
  const cost = c.totalCost === null && ai?.path !== 'LOCAL' && ai?.path !== 'SELF_HOSTED' ? null : costText(c.totalCost, ai)
  return cost ? `${turns} · ${cost}` : turns
}

/** Today's budget, as `GET /api/chat/usage` gives it. */
export function budgetLine(usage: ChatDailyUsage, ai: AiStatus | null | undefined): string {
  if (ai?.path === 'LOCAL' || ai?.path === 'SELF_HOSTED') {
    return costText(usage.spentToday, ai)
  }
  return usage.maxDailyCost
    ? `${formatCost(usage.spentToday)} of ${formatCost(usage.maxDailyCost)} today · resets at midnight`
    : `${formatCost(usage.spentToday)} today · no daily limit`
}

function ConversationList({
  items,
  current,
  error,
  usage,
  ai,
}: {
  items: ConversationItem[] | null
  current: number | null
  error: string | null
  usage: ChatDailyUsage | null
  ai: AiStatus | undefined
}) {
  return (
    <nav className="chat-list" aria-label="Conversations">
      <Link to="/chat" className="button-secondary chat-new">
        New conversation
      </Link>
      {error && <p className="error">Couldn’t load the conversations: {error}</p>}
      {items && items.length === 0 && <p className="muted">No conversations yet.</p>}
      {items && items.length > 0 && (
        <ul>
          {items.map((c) => (
            <li key={c.id}>
              <Link to={`/chat/${c.id}`} aria-current={c.id === current ? 'page' : undefined} className="chat-list-item">
                <span>{c.title ?? 'Untitled conversation'}</span>
                <span className="muted">
                  {conversationLine(c, ai)} · {formatDate(c.updatedAt.slice(0, 10))}
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
      {usage && (
        <div className="chat-budget" role="group" aria-label="Today’s chat budget">
          <p className="chat-budget-title">Today’s chat budget</p>
          <p className="muted">{budgetLine(usage, ai)}</p>
        </div>
      )}
    </nav>
  )
}

/** Corgi's header: the avatar, name and promise, and where it runs, from the status. */
function CorgiHeader({ ai }: { ai: AiStatus | undefined }) {
  const badge = ai ? pathBadge(ai) : null
  const off = offText(ai)
  return (
    <div className="corgi-head">
      <CorgiAvatar size={40} />
      <div className="corgi-head-text">
        <p className="corgi-name">{AI_NAME}</p>
        <p className="corgi-tagline muted">{CHAT_TAGLINE}</p>
      </div>
      {badge && (
        <span className={`path-badge path-badge-${badge.tone}`}>
          <span className="path-badge-dot" aria-hidden="true" />
          {badge.label}
        </span>
      )}
      {off && (
        <p className="corgi-off corgi-head-off" role="status">
          {off}
        </p>
      )}
    </div>
  )
}

/**
 * Chat (UI-03; docs/plans/phase-7.md §5): conversations on the left (on a phone, the list or one conversation), the
 * conversation with its answers and citations, and the question box. The answers are plain text; every figure in them
 * was checked against the tools' results by the backend (CHT-04).
 */
export default function ChatPage() {
  const { id: idParam } = useParams()
  const navigate = useNavigate()
  const { status } = useShellData()
  const { version } = useDataVersion()
  const id = idParam && /^\d{1,18}$/.test(idParam) ? idParam : null
  const aiOff = isOff(status?.ai)

  const [list, setList] = useState<ConversationItem[] | null>(null)
  const [listError, setListError] = useState<string | null>(null)
  const [conversation, setConversation] = useState<Conversation | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [entries, setEntries] = useState<TurnEntry[]>([])
  const [message, setMessage] = useState('')
  const [asking, setAsking] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [listVersion, setListVersion] = useState(0)
  const [usage, setUsage] = useState<ChatDailyUsage | null>(null)
  const [pending, setPending] = useState<string | null>(null)
  /** The conversation this page just created: its turns are the ones asked here, so it isn't read back over them. */
  const createdHere = useRef<string | null>(null)

  useEffect(() => {
    chat
      .list()
      .then((page) => setList(page.items))
      .catch((e: Error) => setListError(e.message))
    reads
      .chatUsage()
      .then(setUsage)
      .catch(() => setUsage(null))
  }, [listVersion, version])

  useEffect(() => {
    setConfirmDelete(false)
    setLoadError(null)
    if (!id) {
      setConversation(null)
      setEntries([])
      return
    }
    if (id === createdHere.current) {
      return
    }
    let live = true
    chat
      .get(id)
      .then((c) => {
        if (live) {
          setConversation(c)
          setEntries(c.turns.map((t) => fromStored(c.id, t)))
        }
      })
      .catch((e: Error) => live && setLoadError(e instanceof ApiProblem && e.status === 404 ? 'This conversation no longer exists.' : e.message))
    return () => {
      live = false
    }
  }, [id])

  async function ask(e: FormEvent) {
    e.preventDefault()
    const question = message.trim()
    if (!question || asking) {
      return
    }
    setAsking(true)
    setPending(question)
    let conversationId = conversation?.id ?? null
    try {
      if (conversationId === null) {
        conversationId = (await createConversation()).id
        createdHere.current = String(conversationId)
        setConversation({ id: conversationId, title: null, totalCost: null, createdAt: '', updatedAt: '', turns: [] })
        navigate(`/chat/${conversationId}`, { replace: true })
      }
      const turn = await askTurn(conversationId, question)
      setEntries((current) => [...current, fromTurn(question, turn)])
      setMessage('')
      // The total is the backend's sum: read it again rather than adding the turn's cost here.
      const asked = conversationId
      chat
        .get(asked)
        .then((c) =>
          setConversation((current) =>
            current && current.id === asked ? { ...current, title: c.title, totalCost: c.totalCost } : current,
          ),
        )
        .catch(() => {})
    } catch (error) {
      const text = await problemText(error)
      setEntries((current) => [
        ...current,
        {
          key: `failed-${current.length}`,
          question,
          answer: null,
          answerSource: null,
          citations: [],
          limitHit: null,
          usage: null,
          grounding: null,
          error: text,
        },
      ])
    } finally {
      setAsking(false)
      setPending(null)
      setListVersion((v) => v + 1)
    }
  }

  async function remove() {
    if (!conversation) {
      return
    }
    try {
      await chat.delete(conversation.id)
      setListVersion((v) => v + 1)
      navigate('/chat')
    } catch (e) {
      setLoadError(await problemText(e))
    }
  }

  return (
    <section className={`page chat-page${id ? ' chat-page-open' : ''}`}>
      <h1>Chat</h1>
      <div className="chat-layout">
        <ConversationList
          items={list}
          current={conversation?.id ?? null}
          error={listError}
          usage={usage}
          ai={status?.ai}
        />
        <section className="chat-conversation" aria-label="Conversation">
          <CorgiHeader ai={status?.ai} />
          {id && (
            <Link to="/chat" className="chat-back">
              ‹ All conversations
            </Link>
          )}
          {loadError && <p className="error">{loadError}</p>}
          {conversation && id && (
            <div className="chat-conversation-head">
              <div className="chat-conversation-title">
                <h2>{conversation.title ?? 'New conversation'}</h2>
                {(conversation.totalCost !== null || status?.ai.path === 'LOCAL' || status?.ai.path === 'SELF_HOSTED') && (
                  <p className="muted">
                    This conversation: {costText(conversation.totalCost, status?.ai)}
                    {(status?.ai.path === 'CLOUD' || status?.ai.path === 'OFF') && ' estimated'}
                  </p>
                )}
              </div>
              {!confirmDelete ? (
                <button type="button" className="link-button" onClick={() => setConfirmDelete(true)}>
                  Delete
                </button>
              ) : (
                <span className="chat-confirm" role="group" aria-label="Delete this conversation?">
                  Delete this conversation and its answers?{' '}
                  <button type="button" className="button-secondary" onClick={() => void remove()}>
                    Delete
                  </button>{' '}
                  <button type="button" className="button-secondary" onClick={() => setConfirmDelete(false)}>
                    Keep it
                  </button>
                </span>
              )}
            </div>
          )}
          {!id && entries.length === 0 && (
            <p className="muted">
              Ask about your own data: totals, categories, merchants, months, alerts and recurring charges. Every figure
              in an answer is checked against what the tools returned.
            </p>
          )}
          <ol className="chat-turns">
            {entries.map((entry) => (
              <ChatTurnView key={entry.key} entry={entry} />
            ))}
            {pending !== null && <PendingTurn question={pending} />}
          </ol>
          {aiOff ? (
            <p className="card chat-ai-off" role="note">
              {offText(status?.ai) ?? `${AI_NAME} is off. Choose where it runs in Settings.`} Earlier conversations can
              still be read.
            </p>
          ) : (
            <form className="chat-form" onSubmit={(e) => void ask(e)}>
              <label htmlFor="chat-question" className="visually-hidden">
                Question
              </label>
              <div className="chat-form-row">
                <textarea
                  id="chat-question"
                  value={message}
                  maxLength={MAX_MESSAGE}
                  rows={2}
                  disabled={asking}
                  placeholder={`Ask ${AI_NAME} about your spending`}
                  onChange={(e) => setMessage(e.target.value)}
                />
                <button type="submit" className="button-primary" disabled={asking || !message.trim()}>
                  {asking ? 'Answering…' : 'Ask'}
                </button>
              </div>
              <div className="chat-form-note muted">
                <span>{AI_NAME} reads your data and never changes it. It can’t give financial advice.</span>
                <span>
                  {formatCount(message.length)} / {formatCount(MAX_MESSAGE)}
                </span>
              </div>
            </form>
          )}
        </section>
      </div>
    </section>
  )
}
