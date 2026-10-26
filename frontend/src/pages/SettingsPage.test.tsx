import '@testing-library/jest-dom/vitest'
import { cleanup, screen, within } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import { fixtures } from '../test/fixtures'
import { renderApp, usageFixture } from '../test/renderApp'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

/** The term → value pairs of a definition list, as shown. */
function facts(section: HTMLElement): Record<string, string> {
  const terms = within(section).getAllByRole('term')
  return Object.fromEntries(terms.map((dt) => [dt.textContent ?? '', dt.nextElementSibling?.textContent ?? '']))
}

test('with AI off: the mode explains what that means, and no models are listed', async () => {
  renderApp('/settings')

  const ai = await screen.findByRole('region', { name: 'Corgi' })
  expect(facts(ai)).toEqual({ Mode: 'Off. Categorization uses rules only, and chat is unavailable.' })
})

test('with cloud AI: the provider, and each of Corgi’s roles with its model id as a fact', async () => {
  renderApp('/settings', {
    '/api/status': {
      ...fixtures.statusOff,
      ai: {
        mode: 'CLOUD',
        provider: 'bedrock',
        models: { categorization: 'cat-model', embedding: 'emb-model', main: 'chat-model' },
      },
    },
  })

  const ai = await screen.findByRole('region', { name: 'Corgi' })
  expect(facts(ai)).toEqual({
    Mode: 'Cloud (Amazon Bedrock)',
    'Corgi · chat': 'chat-model',
    'Corgi · categorization': 'cat-model',
    'Search (embedding) model': 'emb-model',
  })
  expect(within(ai).getAllByRole('term').map((t) => t.textContent)).toEqual([
    'Mode',
    'Corgi · chat',
    'Corgi · categorization',
    'Search (embedding) model',
  ])
  expect(within(ai).getByText(/Corgi is the name Finalysis gives its AI/)).toBeInTheDocument()
})

test('the chat costs as the API returns them, with this month’s spend and questions from the ledger', async () => {
  renderApp('/settings', {
    '/api/chat/usage': { ...usageFixture, spentToday: '0.214300', spentThisMonth: '1.234567', questionsThisMonth: 42 },
  })

  const costs = await screen.findByRole('region', { name: 'Chat costs' })
  expect(await within(costs).findByText('$0.214300')).toBeInTheDocument()
  expect(facts(costs)).toEqual({
    'Spent today (Oct 23, 2026)': '$0.214300',
    'This month (October 2026)': '$1.23 · 42 questions · estimated from list prices',
    'Daily limit': '$1.00',
    'Limit per question': '$0.15',
  })
})

test('with a local model, the cost lines say $0.00 · local', async () => {
  renderApp('/settings', {
    '/api/status': {
      ...fixtures.statusOff,
      ai: { mode: 'LOCAL', provider: 'ollama', models: { categorization: 'qwen', embedding: 'nomic', main: 'qwen' } },
    },
    '/api/chat/usage': { ...usageFixture, spentThisMonth: '0.000000', questionsThisMonth: 1 },
  })

  const costs = await screen.findByRole('region', { name: 'Chat costs' })
  expect(await within(costs).findByText(/1 question/)).toBeInTheDocument()
  expect(facts(costs)['Spent today (Oct 23, 2026)']).toBe('$0.00 · local')
  expect(facts(costs)['This month (October 2026)']).toBe('$0.00 · local · 1 question')
})

test('a cap that is off says so', async () => {
  renderApp('/settings', { '/api/chat/usage': { ...usageFixture, maxTurnCost: null, maxDailyCost: null } })

  const costs = await screen.findByRole('region', { name: 'Chat costs' })
  expect(await within(costs).findByText('$0.000000')).toBeInTheDocument()
  expect(facts(costs)['Daily limit']).toBe('Off')
  expect(facts(costs)['Limit per question']).toBe('Off')
})

test('a failed usage read is said plainly, and the AI section still shows', async () => {
  renderApp('/settings', { '/api/chat/usage': undefined })

  expect(await screen.findByText(/Couldn’t read the chat usage/)).toBeInTheDocument()
  expect(screen.getByRole('region', { name: 'Corgi' })).toBeInTheDocument()
})

test('Settings has no control that changes anything', async () => {
  renderApp('/settings')

  const page = await screen.findByRole('heading', { level: 1, name: 'Settings' })
  const section = page.closest('section') as HTMLElement
  expect(within(section).queryAllByRole('button')).toEqual([])
  expect(within(section).queryAllByRole('textbox')).toEqual([])
  expect(within(section).queryAllByRole('combobox')).toEqual([])
})
