import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import {
  ApiProblem,
  askTurn,
  chat,
  createConversation,
  reads,
  type ChatTurn,
  type Conversation,
  type ConversationItem,
  type StoredTurn,
} from '../api'
import ChatTurnView, { type TurnEntry } from '../components/chat/ChatTurnView'
import { formatDate, formatMoney } from '../format'
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
    case `${PROBLEM}ai-off`:
      return 'Chat needs an AI model, and AI is off in this setup.'
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

function ConversationList({
  items,
  current,
  error,
}: {
  items: ConversationItem[] | null
  current: number | null
  error: string | null
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
                  {c.turns === 1 ? '1 question' : `${c.turns} questions`} · {formatDate(c.updatedAt.slice(0, 10))}
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </nav>
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
  const aiOff = status?.ai.mode === 'OFF'

  const [list, setList] = useState<ConversationItem[] | null>(null)
  const [listError, setListError] = useState<string | null>(null)
  const [conversation, setConversation] = useState<Conversation | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [entries, setEntries] = useState<TurnEntry[]>([])
  const [message, setMessage] = useState('')
  const [asking, setAsking] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [listVersion, setListVersion] = useState(0)
  /** The conversation this page just created: its turns are the ones asked here, so it isn't read back over them. */
  const createdHere = useRef<string | null>(null)

  useEffect(() => {
    chat
      .list()
      .then((page) => setList(page.items))
      .catch((e: Error) => setListError(e.message))
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
    let conversationId = conversation?.id ?? null
    try {
      if (conversationId === null) {
        conversationId = (await createConversation()).id
        createdHere.current = String(conversationId)
        setConversation({ id: conversationId, title: null, createdAt: '', updatedAt: '', turns: [] })
        navigate(`/chat/${conversationId}`, { replace: true })
      }
      const turn = await askTurn(conversationId, question)
      setEntries((current) => [...current, fromTurn(question, turn)])
      setMessage('')
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
        <ConversationList items={list} current={conversation?.id ?? null} error={listError} />
        <section className="chat-conversation" aria-label="Conversation">
          {id && (
            <Link to="/chat" className="chat-back">
              ‹ All conversations
            </Link>
          )}
          {loadError && <p className="error">{loadError}</p>}
          {conversation && id && (
            <div className="chat-conversation-head">
              <h2>{conversation.title ?? 'New conversation'}</h2>
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
          </ol>
          {aiOff ? (
            <p className="card chat-ai-off" role="note">
              Chat needs an AI model. AI is off in this setup (the backend runs without the <code>aws</code> profile).
              Earlier conversations can still be read.
            </p>
          ) : (
            <form className="chat-form" onSubmit={(e) => void ask(e)}>
              <label htmlFor="chat-question">Question</label>
              <textarea
                id="chat-question"
                value={message}
                maxLength={MAX_MESSAGE}
                rows={3}
                disabled={asking}
                onChange={(e) => setMessage(e.target.value)}
              />
              <div className="chat-form-row">
                <span className="muted">
                  {message.length} / {MAX_MESSAGE}
                </span>
                <button type="submit" className="button-primary" disabled={asking || !message.trim()}>
                  {asking ? 'Answering…' : 'Ask'}
                </button>
              </div>
            </form>
          )}
        </section>
      </div>
    </section>
  )
}
