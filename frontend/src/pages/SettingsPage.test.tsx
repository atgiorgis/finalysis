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
      ai: { path: 'LOCAL', provider: 'ollama', models: { categorization: 'qwen', embedding: 'nomic', main: 'qwen' } },
    },
    '/api/chat/usage': { ...usageFixture, spentThisMonth: '0.000000', questionsThisMonth: 1 },
  })

  const costs = await screen.findByRole('region', { name: 'Chat costs' })
  expect(await within(costs).findByText(/1 question/)).toBeInTheDocument()
  expect(facts(costs)['Spent today (Oct 23, 2026)']).toBe('$0.00 · local')
  expect(facts(costs)['This month (October 2026)']).toBe('$0.00 · local · 1 question')
})

test('on Self-hosted, the cost lines say there is no token cost, and no cap shows (caps are Cloud’s)', async () => {
  renderApp('/settings', {
    '/api/status': {
      ...fixtures.statusOff,
      ai: { path: 'SELF_HOSTED', server: 'finalysis-selfhost', provider: 'ollama', reachable: true,
        models: { categorization: 'm', embedding: 'nomic', main: 'm' } },
    },
    '/api/chat/usage': { ...usageFixture, questionsThisMonth: 3 },
  })

  const costs = await screen.findByRole('region', { name: 'Chat costs' })
  expect(await within(costs).findByText(/3 questions/)).toBeInTheDocument()
  expect(facts(costs)).toEqual({
    'Spent today (Oct 23, 2026)': 'No token cost on Self-hosted.',
    'This month (October 2026)': 'No token cost on Self-hosted. · 3 questions',
  })
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

// Since Phase 8 the vault and statement-file cards are editable (docs/plans/phase-8.md §6), and since Phase 9 where
// Corgi runs (CorgiSettings.test.tsx); the chat costs stay read-only, set in the backend's configuration.
test('the chat costs have no control that changes anything', async () => {
  renderApp('/settings')

  for (const name of ['Chat costs']) {
    const card = (await screen.findByRole('heading', { level: 2, name })).closest('section') as HTMLElement
    expect(within(card).queryAllByRole('button')).toEqual([])
    expect(within(card).queryAllByRole('textbox')).toEqual([])
    expect(within(card).queryAllByRole('combobox')).toEqual([])
  }
})
