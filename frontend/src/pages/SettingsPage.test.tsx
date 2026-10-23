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

  const ai = await screen.findByRole('region', { name: 'AI' })
  expect(facts(ai)).toEqual({ Mode: 'Off. Categorization uses rules only, and chat is unavailable.' })
})

test('with cloud AI: the provider and the model of each role', async () => {
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

  const ai = await screen.findByRole('region', { name: 'AI' })
  expect(facts(ai)).toEqual({
    Mode: 'Cloud (Amazon Bedrock)',
    'Categorization model': 'cat-model',
    'Search (embedding) model': 'emb-model',
    'Chat model': 'chat-model',
  })
})

test('the chat costs as the API returns them', async () => {
  renderApp('/settings', { '/api/chat/usage': { ...usageFixture, spentToday: '0.214300' } })

  const costs = await screen.findByRole('region', { name: 'Chat costs' })
  expect(await within(costs).findByText('$0.214300')).toBeInTheDocument()
  expect(facts(costs)).toEqual({
    'Spent today (Oct 23, 2026)': '$0.214300',
    'Daily limit': '$1.00',
    'Limit per question': '$0.15',
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
  expect(screen.getByRole('region', { name: 'AI' })).toBeInTheDocument()
})

test('Settings has no control that changes anything', async () => {
  renderApp('/settings')

  const page = await screen.findByRole('heading', { level: 1, name: 'Settings' })
  const section = page.closest('section') as HTMLElement
  expect(within(section).queryAllByRole('button')).toEqual([])
  expect(within(section).queryAllByRole('textbox')).toEqual([])
  expect(within(section).queryAllByRole('combobox')).toEqual([])
})
