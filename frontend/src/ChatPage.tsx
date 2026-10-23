import { useState, type FormEvent } from 'react'
import { askTurn, createConversation, type ChatTurn, type Citation } from './api'

/** The longest question the API takes (`TurnRequest`: 1 to 1,000 characters). */
export const MAX_MESSAGE = 1000

interface Entry {
  question: string
  turn?: ChatTurn
  error?: string
}

function citationText(c: Citation): string {
  switch (c.kind) {
    case 'TRANSACTION':
      if (c.txn.status === 'GONE') {
        return 'a transaction no longer in your statements'
      }
      return [c.txn.txnDate, c.txn.merchant, c.txn.amount, c.txn.accountName].filter(Boolean).join(' · ')
    case 'TOTAL':
      return c.label
    case 'ALERT':
      return `${c.type} alert · ${c.month}`
    case 'SERIES':
      return `${c.merchant} (recurring charges)`
  }
}

function TurnView({ entry }: { entry: Entry }) {
  const { question, turn, error } = entry
  return (
    <li className="chat-turn">
      <p className="chat-question">
        <strong>You:</strong> {question}
      </p>
      {error && <p className="error">{error}</p>}
      {turn && (
        <>
          <p className="chat-answer">{turn.answer}</p>
          {turn.answerSource === 'FALLBACK' && (
            <p className="chat-note">Shown straight from your data: the model's answer couldn't be checked.</p>
          )}
          {turn.citations.length > 0 && (
            <ol className="chat-citations" aria-label="Citations">
              {turn.citations.map((c) => (
                <li key={c.marker}>
                  [{c.marker}] {citationText(c)}
                </li>
              ))}
            </ol>
          )}
          <p className="chat-cost">
            Cost:{' '}
            {turn.usage.estimatedCost === null ? 'not priced' : `$${turn.usage.estimatedCost}`}
            {turn.usage.model && ` · ${turn.usage.model}`}
          </p>
        </>
      )}
    </li>
  )
}

/**
 * A plain chat page (CHT-01; not the Phase 7 UI): one conversation, a question box, and each answer with its
 * citations and cost. The first question creates the conversation; follow-ups continue it.
 */
export default function ChatPage() {
  const [conversationId, setConversationId] = useState<number | null>(null)
  const [entries, setEntries] = useState<Entry[]>([])
  const [draft, setDraft] = useState('')
  const [sending, setSending] = useState(false)

  async function send(event: FormEvent) {
    event.preventDefault()
    const question = draft.trim()
    if (!question || sending) {
      return
    }
    setSending(true)
    try {
      let id = conversationId
      if (id === null) {
        id = (await createConversation()).id
        setConversationId(id)
      }
      const turn = await askTurn(id, question)
      setEntries((all) => [...all, { question, turn }])
      setDraft('')
    } catch (e) {
      setEntries((all) => [...all, { question, error: (e as Error).message }])
    } finally {
      setSending(false)
    }
  }

  return (
    <section className="chat" aria-label="Chat">
      <h2>Ask about your data</h2>
      <ol className="chat-turns">
        {entries.map((entry, i) => (
          <TurnView key={i} entry={entry} />
        ))}
      </ol>
      <form onSubmit={send}>
        <label htmlFor="chat-message">Question</label>
        <textarea
          id="chat-message"
          value={draft}
          maxLength={MAX_MESSAGE}
          rows={3}
          onChange={(e) => setDraft(e.target.value)}
          disabled={sending}
        />
        <button type="submit" disabled={sending || draft.trim() === ''}>
          {sending ? 'Asking…' : 'Ask'}
        </button>
      </form>
    </section>
  )
}
