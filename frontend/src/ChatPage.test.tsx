import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import ChatPage, { MAX_MESSAGE } from './ChatPage'
import type { ChatTurn } from './api'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

function turn(n: number, answer: string, overrides: Partial<ChatTurn> = {}): ChatTurn {
  return {
    conversationId: 7,
    turn: n,
    answer,
    answerSource: 'MODEL',
    citations: [],
    usage: { model: 'test-model', rounds: 2, estimatedCost: '0.012345' },
    limitHit: null,
    ...overrides,
  }
}

/** Answers the chat endpoints from a script; records every request. */
function stubChat(turns: Array<ChatTurn | { status: number; type: string; detail: string }>) {
  const calls: Array<{ url: string; method: string; body?: unknown }> = []
  let next = 0
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      calls.push({ url, method: init?.method ?? 'GET', body: init?.body ? JSON.parse(String(init.body)) : undefined })
      if (url === '/api/chat/conversations') {
        return Response.json({ id: 7, createdAt: '2026-10-23T10:00:00Z' }, { status: 201 })
      }
      const reply = turns[next++]
      if ('status' in reply && typeof reply.status === 'number' && 'detail' in reply) {
        return Response.json({ ...reply, title: 'AI is off' }, { status: reply.status })
      }
      return Response.json(reply)
    }),
  )
  return calls
}

async function ask(question: string) {
  fireEvent.change(screen.getByLabelText('Question'), { target: { value: question } })
  fireEvent.click(screen.getByRole('button', { name: 'Ask' }))
  return screen.findByText(question, { exact: false })
}

test('shows an answer with its citations and the turn cost', async () => {
  stubChat([
    turn(1, 'You spent $84.12 twice at SAFEWAY [1], and $190.10 on dining [2].', {
      citations: [
        {
          kind: 'TRANSACTION',
          marker: 1,
          txn: { status: 'FOUND', txnDate: '2026-06-13', merchant: 'SAFEWAY', amount: '-84.12', accountName: 'Apex Rewards Visa' },
        },
        { kind: 'TOTAL', marker: 2, label: 'Dining · August 2026' },
      ],
    }),
  ])
  render(<ChatPage />)

  await ask('What happened at Safeway?')

  expect(await screen.findByText(/You spent \$84.12 twice/)).toBeInTheDocument()
  const citations = screen.getByRole('list', { name: 'Citations' })
  expect(citations).toHaveTextContent('[1] 2026-06-13 · SAFEWAY · -84.12 · Apex Rewards Visa')
  expect(citations).toHaveTextContent('[2] Dining · August 2026')
  expect(screen.getByText(/Cost:/)).toHaveTextContent('Cost: $0.012345 · test-model')
})

test('a follow-up continues the same conversation', async () => {
  const calls = stubChat([turn(1, 'Dining was $190.10 [1].'), turn(2, 'In September it was $356.91 [1].')])
  render(<ChatPage />)

  await ask('How much on dining in August?')
  await screen.findByText(/Dining was/)
  await ask('And in September?')
  await screen.findByText(/In September it was/)

  expect(calls.filter((c) => c.url === '/api/chat/conversations')).toHaveLength(1)
  const turns = calls.filter((c) => c.url.endsWith('/turns'))
  expect(turns.map((c) => c.url)).toEqual(['/api/chat/conversations/7/turns', '/api/chat/conversations/7/turns'])
  expect(turns.map((c) => c.body)).toEqual([{ message: 'How much on dining in August?' }, { message: 'And in September?' }])
})

test('shows the AI-off problem as the answer', async () => {
  stubChat([{ status: 503, type: 'urn:finalysis:problem:ai-off', detail: 'AI is off. Start the backend with the aws profile to use chat.' }])
  render(<ChatPage />)

  await ask('How much did I spend?')

  expect(await screen.findByText('AI is off. Start the backend with the aws profile to use chat.')).toHaveClass('error')
})

test('marks a fallback answer and an unpriced turn', async () => {
  stubChat([
    turn(1, '- Dining · August 2026 · money out 190.10 [1]', {
      answerSource: 'FALLBACK',
      usage: { model: null, rounds: 1, estimatedCost: null },
      citations: [{ kind: 'TRANSACTION', marker: 1, txn: { status: 'GONE' } }],
    }),
  ])
  render(<ChatPage />)

  await ask('Dining?')

  expect(await screen.findByText(/model's answer couldn't be checked/)).toBeInTheDocument()
  expect(screen.getByText(/Cost:/)).toHaveTextContent('Cost: not priced')
  expect(screen.getByRole('list', { name: 'Citations' })).toHaveTextContent('[1] a transaction no longer in your statements')
})

test('the question box takes at most the API limit and ignores a blank question', () => {
  const calls = stubChat([])
  render(<ChatPage />)

  expect(screen.getByLabelText('Question')).toHaveAttribute('maxLength', String(MAX_MESSAGE))
  fireEvent.change(screen.getByLabelText('Question'), { target: { value: '   ' } })
  expect(screen.getByRole('button', { name: 'Ask' })).toBeDisabled()
  expect(calls).toHaveLength(0)
})
