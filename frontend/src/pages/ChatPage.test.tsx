import '@testing-library/jest-dom/vitest'
import { cleanup, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, test, vi } from 'vitest'
import { alertHref, drillHref, seriesHref } from '../citationLinks'
import {
  conversation,
  conversations,
  fallbackTurn,
  goneTurn,
  groceriesDrill,
  limitedTurn,
  richTurn,
  spotifyDrill,
} from '../test/chatFixtures'
import { fixtures } from '../test/fixtures'
import { renderApp, usageFixture, type WriteHandler } from '../test/renderApp'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

const MINUS = '−'
const CLOUD = {
  ...fixtures.statusOff,
  ai: { mode: 'CLOUD', provider: 'bedrock', models: { main: 'test-main-model', categorization: 'c', embedding: 'e' } },
}
const LIST = '/api/chat/conversations?page=0&size=50'

const ROUTES: Record<string, unknown> = {
  '/api/status': CLOUD,
  [LIST]: conversations,
  '/api/chat/conversations/7': conversation,
  [`/api/transactions/${fixtures.transactionDetail.id}`]: fixtures.transactionDetail,
}

/** Answers a new conversation (201, id 7) and its turns from a script; a problem is `{status, type, detail}`. */
function chatWrites(...turns: (object | { status: number; type: string; detail: string })[]): WriteHandler {
  let next = 0
  return (w) => {
    if (w.method === 'POST' && w.url === '/api/chat/conversations') {
      return Response.json({ id: 7, createdAt: '2026-10-23T12:00:00Z' }, { status: 201 })
    }
    if (w.method === 'POST' && w.url.endsWith('/turns')) {
      const reply = turns[next++] as { status?: number }
      return typeof reply.status === 'number'
        ? Response.json({ ...reply, type: `urn:finalysis:problem:${(reply as { type: string }).type}` }, { status: reply.status })
        : Response.json(reply)
    }
    if (w.method === 'DELETE') {
      return new Response(null, { status: 204 })
    }
    return undefined
  }
}

async function ask(user: ReturnType<typeof userEvent.setup>, question: string) {
  await user.type(await screen.findByRole('textbox', { name: 'Question' }), question)
  await user.click(screen.getByRole('button', { name: 'Ask' }))
}

function reply(index = 0) {
  return screen.getAllByRole('listitem').filter((li) => li.classList.contains('chat-turn'))[index]
}

test('the conversation list: titles, turn counts with the backend’s total cost, and a new conversation', async () => {
  renderApp('/chat', ROUTES)

  const nav = await screen.findByRole('navigation', { name: 'Conversations' })
  const groceries = await within(nav).findByRole('link', { name: /Groceries in September/ })
  expect(groceries).toHaveAttribute('href', '/chat/7')
  expect(groceries).toHaveTextContent('2 turns · $0.01 · Oct 23')
  expect(within(nav).getByRole('link', { name: /Untitled conversation/ })).toHaveTextContent('1 turn · Oct 20')
  expect(within(nav).getByRole('link', { name: 'New conversation' })).toHaveAttribute('href', '/chat')
})

test('a stored conversation: the answer as text, emphasis stripped, list markers kept, markers linking down', async () => {
  renderApp('/chat/7', ROUTES)

  expect(await screen.findByRole('heading', { level: 2, name: 'Groceries in September' })).toBeInTheDocument()
  const answer = reply(0).querySelector('.chat-answer') as HTMLElement
  expect(answer.textContent).toBe(
    'You spent $477.94 on groceries in September [2], including $74.09 at Trader Joe’s [1].\n' +
      '* The duplicate at Safeway is flagged [3].\n' +
      '* Spotify went up to $12.99 [4].\n' +
      'Ignore [9] and [click here](/drill?bucket=EXPENSE&evil=1).',
  )
  expect(within(answer).getByRole('link', { name: 'citation 2' })).toHaveAttribute('href', '#cite-7-1-2')
  expect(within(answer).queryByRole('link', { name: 'citation 9' })).not.toBeInTheDocument()
  expect(reply(0).querySelector('#cite-7-1-2')).toHaveTextContent('Groceries spending · September 2026')
})

test('every link comes from a citation object, none from the answer’s text', async () => {
  renderApp('/chat/7', ROUTES)

  await screen.findByRole('heading', { level: 2, name: 'Groceries in September' })
  const hrefs = within(reply(0))
    .getAllByRole('link')
    .map((a) => a.getAttribute('href'))
  const SAFEWAY = fixtures.alertsAll.items[4]
  expect(hrefs).toEqual([
    '#cite-7-1-2',
    '#cite-7-1-1',
    '#cite-7-1-3',
    '#cite-7-1-4',
    drillHref(groceriesDrill, 'Groceries spending · September 2026'),
    alertHref({ kind: 'ALERT', marker: 3, alertKey: SAFEWAY.key, type: 'POSSIBLE_DUPLICATE', month: SAFEWAY.month, txns: [] }),
    seriesHref({ kind: 'SERIES', marker: 4, accountId: 3, merchant: 'SPOTIFY USA', drillDown: spotifyDrill }),
    drillHref(spotifyDrill, 'SPOTIFY USA: its charges'),
  ])
  expect(hrefs.some((h) => h?.includes('evil'))).toBe(false)
})

test('each citation kind opens its target', async () => {
  const user = userEvent.setup()
  const { location } = renderApp('/chat/7', ROUTES)

  const citations = await within(await screen.findByRole('list', { name: 'Citations' })).findAllByRole('listitem')
  expect(citations.map((c) => c.textContent)).toEqual([
    `Sep 30 · TRADER JOE S · ${MINUS}$74.09 · Apex Visa (travel card)`,
    'Groceries spending · September 2026 · its 8 transactions',
    'Possible duplicate alert · June 2026',
    'SPOTIFY USA: recurring charges · its charges',
  ])
  expect(within(citations[1]).getByRole('link')).toHaveAttribute(
    'href',
    `/drill?month=2026-09&bucket=EXPENSE&categoryId=${groceriesDrill.params.categoryId}&label=Groceries+spending+%C2%B7+September+2026&expect=-477.94`,
  )
  expect(within(citations[2]).getByRole('link').getAttribute('href')).toMatch(
    /^\/alerts\?month=2026-06&key=possible-duplicate\.2026-06\./,
  )
  expect(within(citations[3]).getAllByRole('link')[0]).toHaveAttribute('href', '/recurring?series=3%3ASPOTIFY+USA')

  await user.click(within(citations[0]).getByRole('button'))
  expect(location()).toBe(`/chat/7?txn=${fixtures.transactionDetail.id}`)
  expect(await screen.findByRole('dialog', { name: 'TRADER JOE S' })).toBeInTheDocument()
})

test('the turn’s model, estimated cost and checked figures; a failed stored turn says so', async () => {
  renderApp('/chat/7', ROUTES)

  await screen.findByRole('heading', { level: 2, name: 'Groceries in September' })
  expect(reply(0).querySelector('.chat-figures')).toHaveTextContent(
    'Figures computed by Finalysis, not by the model · 3 checked',
  )
  expect(reply(0).querySelector('.chat-figures svg')).toBeInTheDocument()
  expect(reply(0).querySelector('.chat-meta')).toHaveTextContent(/^test-main-model · \$0\.012345 estimated$/)
  expect(reply(1)).toHaveTextContent('And in August?')
  expect(reply(1)).toHaveTextContent('This question failed: the AI service didn’t answer.')
})

test('a first question creates the conversation, asks, and shows the answer at its URL', async () => {
  const user = userEvent.setup()
  const { writes, location } = renderApp('/chat', ROUTES, chatWrites(richTurn))

  await ask(user, 'How much on groceries in September?')

  expect(await screen.findByText(/You spent \$477\.94 on groceries/)).toBeInTheDocument()
  expect(writes.map((w) => [w.method, w.url, w.body])).toEqual([
    ['POST', '/api/chat/conversations', undefined],
    ['POST', '/api/chat/conversations/7/turns', { message: 'How much on groceries in September?' }],
  ])
  await vi.waitFor(() => expect(location()).toBe('/chat/7'))
  await vi.waitFor(() => expect(screen.getByRole('textbox', { name: 'Question' })).toHaveValue(''))
})

test('a follow-up asks the same conversation and adds below', async () => {
  const user = userEvent.setup()
  const { writes } = renderApp('/chat/7', ROUTES, chatWrites({ ...richTurn, turn: 3, answer: 'Plain answer.' }))

  await screen.findByRole('heading', { level: 2, name: 'Groceries in September' })
  await ask(user, 'And Dining?')

  expect(await screen.findByText('Plain answer.')).toBeInTheDocument()
  expect(writes).toEqual([{ method: 'POST', url: '/api/chat/conversations/7/turns', body: { message: 'And Dining?' } }])
  expect(reply(2)).toHaveTextContent('And Dining?')
})

test('a fallback is labelled; a limit is said plainly; a gone transaction has no link; no price says so', async () => {
  const user = userEvent.setup()
  renderApp('/chat/7', ROUTES, chatWrites(fallbackTurn, limitedTurn, goneTurn))

  await screen.findByRole('heading', { level: 2, name: 'Groceries in September' })
  await ask(user, 'one')
  expect(await screen.findByText('The model’s answer couldn’t be checked, so here are the figures it used.')).toBeInTheDocument()
  expect(reply(2).querySelector('.chat-meta')).toHaveTextContent(/^test-main-model · no price$/)
  expect(reply(2).querySelector('.chat-figures')).toHaveTextContent('· 2 checked')
  await ask(user, 'two')
  expect(
    await screen.findByText('This answer stopped early: it reached the limit on cost for one question.'),
  ).toBeInTheDocument()
  await ask(user, 'three')
  const gone = await screen.findByText('A transaction no longer in your statements (its statement was deleted)')
  expect(gone.closest('li')?.querySelector('a, button')).toBeNull()
})

test.each([
  [{ status: 503, type: 'ai-off', detail: 'AI is off.' }, 'Chat needs an AI model, and AI is off in this setup.'],
  [{ status: 429, type: 'chat-budget', detail: 'x' }, 'Today’s chat budget ($1.00) is used up. It resets at midnight.'],
  [{ status: 429, type: 'chat-busy', detail: 'x' }, 'Chat is busy with other questions. Try again in a moment.'],
  [{ status: 409, type: 'turn-in-progress', detail: 'x' }, 'This conversation is still answering the last question.'],
  [{ status: 502, type: 'ai-unavailable', detail: 'x' }, 'The AI service didn’t answer. Try the question again.'],
  [{ status: 400, type: 'invalid-request', detail: 'message: must be 1 to 1,000 characters' }, 'message: must be 1 to 1,000 characters'],
])('a refused turn is said plainly and the question stays in the box: %o', async (problem, shown) => {
  const user = userEvent.setup()
  renderApp('/chat/7', { ...ROUTES, '/api/chat/usage': usageFixture }, chatWrites(problem))

  await screen.findByRole('heading', { level: 2, name: 'Groceries in September' })
  await ask(user, 'Anything?')

  expect(await screen.findByRole('alert')).toHaveTextContent(shown)
  expect(screen.getByRole('textbox', { name: 'Question' })).toHaveValue('Anything?')
})

test('with AI off: no question box, the reason, and conversations still readable', async () => {
  renderApp('/chat/7', { ...ROUTES, '/api/status': fixtures.statusOff })

  expect(await screen.findByRole('heading', { level: 2, name: 'Groceries in September' })).toBeInTheDocument()
  expect(screen.getByRole('note')).toHaveTextContent('Chat needs an AI model. AI is off in this setup')
  expect(screen.queryByRole('textbox', { name: 'Question' })).not.toBeInTheDocument()
})

test('delete asks first, then deletes and returns to the list', async () => {
  const user = userEvent.setup()
  const { writes, location, requests } = renderApp('/chat/7', ROUTES, chatWrites())

  await screen.findByRole('heading', { level: 2, name: 'Groceries in September' })
  const before = requests.filter((r) => r === LIST).length
  await user.click(screen.getByRole('button', { name: 'Delete' }))
  const confirm = screen.getByRole('group', { name: 'Delete this conversation?' })
  await user.click(within(confirm).getByRole('button', { name: 'Keep it' }))
  expect(writes).toEqual([])

  await user.click(screen.getByRole('button', { name: 'Delete' }))
  await user.click(within(screen.getByRole('group', { name: 'Delete this conversation?' })).getByRole('button', { name: 'Delete' }))

  await vi.waitFor(() => expect(location()).toBe('/chat'))
  expect(writes).toEqual([{ method: 'DELETE', url: '/api/chat/conversations/7', body: undefined }])
  await vi.waitFor(() => expect(requests.filter((r) => r === LIST).length).toBeGreaterThan(before))
})

test('an unknown conversation says so', async () => {
  renderApp('/chat/99', ROUTES)

  expect(await screen.findByText('This conversation no longer exists.')).toBeInTheDocument()
})

test('the question box counts to 1,000 characters', async () => {
  renderApp('/chat', ROUTES)

  const box = await screen.findByRole('textbox', { name: 'Question' })
  expect(box).toHaveAttribute('maxlength', '1000')
  expect(screen.getByText('0 / 1,000')).toBeInTheDocument()
})


// ---------------------------------------------------------------------------- Corgi (step 7e)

test('the header: Corgi’s avatar, name and promise, and a cloud badge from the status', async () => {
  renderApp('/chat/7', ROUTES)

  const section = await screen.findByRole('region', { name: 'Conversation' })
  const head = section.querySelector('.corgi-head') as HTMLElement
  expect(head).toHaveTextContent('CorgiReads your books. Never changes them.')
  expect(head.querySelector('.corgi-avatar')).toHaveTextContent('C')
  const badge = head.querySelector('.path-badge') as HTMLElement
  expect(badge).toHaveTextContent('Cloud · Bedrock')
  expect(badge).toHaveClass('path-badge-cloud')
})

test('the header badge says local with a local provider, from config, and is absent with AI off', async () => {
  renderApp('/chat', {
    ...ROUTES,
    '/api/status': { ...CLOUD, ai: { ...CLOUD.ai, mode: 'LOCAL', provider: 'ollama' } },
  })
  const badge = await vi.waitFor(() => {
    const found = document.querySelector('.path-badge')
    expect(found).not.toBeNull()
    return found as HTMLElement
  })
  expect(badge).toHaveTextContent('Local · Ollama')
  expect(badge).toHaveClass('path-badge-local')
  cleanup()

  renderApp('/chat', { ...ROUTES, '/api/status': fixtures.statusOff })
  await screen.findByRole('note')
  expect(document.querySelector('.corgi-name')).toHaveTextContent('Corgi')
  expect(document.querySelector('.path-badge')).toBeNull()
})

test('each answer is labelled Corgi; the figures note is omitted when no figure was checked', async () => {
  const user = userEvent.setup()
  const noFigures = {
    ...richTurn,
    turn: 3,
    answer: 'I can only read your own statements.',
    citations: [],
    grounding: { status: 'NO_FIGURES', figuresChecked: 0, retried: false, ungrounded: 0 },
  }
  renderApp('/chat/7', ROUTES, chatWrites(noFigures))

  await screen.findByRole('heading', { level: 2, name: 'Groceries in September' })
  expect(reply(0).querySelector('.chat-speaker')).toHaveTextContent('Corgi')
  expect(reply(0).querySelector('.corgi-avatar')).toHaveTextContent('C')
  await ask(user, 'Who are you?')
  await screen.findByText('I can only read your own statements.')
  expect(reply(2).querySelector('.chat-speaker')).toHaveTextContent('Corgi')
  expect(reply(2).querySelector('.chat-figures')).toBeNull()
  expect(reply(2).querySelector('.chat-meta')).toHaveTextContent('test-main-model · $0.012345 estimated')
  expect(reply(1).querySelector('.chat-speaker')).toBeNull() // a failed turn has no answer to label
})

test('while answering: the question, then “Corgi is thinking…” with the avatar', async () => {
  const user = userEvent.setup()
  let answer: (r: Response) => void = () => {}
  renderApp('/chat/7', ROUTES, (w) =>
    w.url.endsWith('/turns') ? new Promise<Response>((resolve) => (answer = resolve)) : undefined,
  )

  await screen.findByRole('heading', { level: 2, name: 'Groceries in September' })
  await ask(user, 'Is that normal for me?')
  const thinking = await screen.findByText('Corgi is thinking…')
  const pending = thinking.closest('li') as HTMLElement
  expect(pending).toHaveTextContent('Is that normal for me?')
  expect(pending.querySelector('.corgi-avatar')).toBeInTheDocument()

  answer(Response.json({ ...richTurn, turn: 3, answer: 'Yes.' }))
  expect(await screen.findByText('Yes.')).toBeInTheDocument()
  expect(screen.queryByText('Corgi is thinking…')).not.toBeInTheDocument()
})

test('the input: Corgi’s placeholder, the promise under it, and the counter', async () => {
  renderApp('/chat', ROUTES)

  const box = await screen.findByRole('textbox', { name: 'Question' })
  expect(box).toHaveAttribute('placeholder', 'Ask Corgi about your spending')
  const note = (await screen.findByText('Corgi reads your data and never changes it. It can’t give financial advice.'))
    .parentElement as HTMLElement
  expect(note).toHaveTextContent('0 / 1,000')
})

test('the header total is the backend’s, read again after each answer; the page adds nothing', async () => {
  const user = userEvent.setup()
  const { requests } = renderApp(
    '/chat',
    { ...ROUTES, '/api/chat/conversations/7': { ...conversation, totalCost: '0.029515' } },
    chatWrites(richTurn),
  )

  await ask(user, 'How much on groceries in September?')
  await screen.findByText(/You spent \$477\.94 on groceries/)
  // The turn cost $0.012345; the header shows the conversation's sum as the API returned it.
  expect(await screen.findByText('This conversation: $0.03 estimated')).toBeInTheDocument()
  expect(requests).toContain('/api/chat/conversations/7')
})

test('a stored conversation shows its total in the header', async () => {
  renderApp('/chat/7', ROUTES)

  expect(await screen.findByText('This conversation: $0.01 estimated')).toBeInTheDocument()
})

test('the left column shows today’s budget from the usage read', async () => {
  renderApp('/chat', { ...ROUTES, '/api/chat/usage': { ...usageFixture, spentToday: '0.214300' } })

  const budget = await screen.findByRole('group', { name: 'Today’s chat budget' })
  expect(budget).toHaveTextContent('$0.21 of $1.00 today · resets at midnight')
})

test('with a local model, every cost line says $0.00 · local', async () => {
  const local = { ...CLOUD, ai: { ...CLOUD.ai, mode: 'LOCAL', provider: 'ollama' } }
  renderApp('/chat/7', { ...ROUTES, '/api/status': local })

  await screen.findByRole('heading', { level: 2, name: 'Groceries in September' })
  await vi.waitFor(() => expect(reply(0).querySelector('.chat-meta')).toHaveTextContent('test-main-model · $0.00 · local'))
  expect(screen.getByText('This conversation: $0.00 · local')).toBeInTheDocument()
  expect(await screen.findByRole('group', { name: 'Today’s chat budget' })).toHaveTextContent('$0.00 · local')
  const nav = screen.getByRole('navigation', { name: 'Conversations' })
  expect(within(nav).getByRole('link', { name: /Untitled conversation/ })).toHaveTextContent('1 turn · $0.00 · local')
})
