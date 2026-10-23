import '@testing-library/jest-dom/vitest'
import { cleanup, screen } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import { TABS } from './routes'
import { renderApp } from './test/renderApp'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

test('lists the seven tabs in the plan’s order', () => {
  expect(TABS.map((tab) => tab.label)).toEqual([
    'Overview',
    'Transactions',
    'Alerts',
    'Recurring',
    'Chat',
    'Goals',
    'Settings',
  ])
})

test.each(TABS.filter((tab) => tab.path !== '/chat').map((tab) => [tab.path, tab.label]))(
  '%s renders the %s tab',
  async (path, label) => {
    renderApp(path)

    expect(await screen.findByRole('heading', { level: 1, name: label })).toBeInTheDocument()
  },
)

test('the Chat tab is still the Phase 6 chat page until step 6', async () => {
  renderApp('/chat')

  expect(await screen.findByRole('region', { name: 'Chat' })).toBeInTheDocument()
})

test('a tab’s sub-path stays on that tab', async () => {
  renderApp('/alerts/some-key')

  expect(await screen.findByRole('heading', { level: 1, name: 'Alerts' })).toBeInTheDocument()
})

test('an unknown path goes to the Overview', async () => {
  const { location } = renderApp('/nowhere')

  expect(await screen.findByRole('heading', { level: 1, name: 'Overview' })).toBeInTheDocument()
  expect(location()).toBe('/overview')
})
