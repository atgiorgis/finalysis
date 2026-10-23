import '@testing-library/jest-dom/vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, expect, test, vi } from 'vitest'
import AppRoutes, { TABS } from './routes'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

function renderAt(path: string) {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue(
      Response.json({ app: 'finalysis', status: 'ok', timestamp: '2026-01-01T00:00:00Z', ai: { mode: 'OFF' } }),
    ),
  )
  render(
    <MemoryRouter initialEntries={[path]}>
      <AppRoutes />
    </MemoryRouter>,
  )
}

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

test.each(TABS.map((tab) => [tab.path, tab.label]))('%s renders the %s tab', async (path, label) => {
  renderAt(path)

  expect(screen.getByRole('heading', { level: 1, name: label })).toBeInTheDocument()
})

test('a tab’s sub-path stays on that tab', () => {
  renderAt('/chat/12')

  expect(screen.getByRole('heading', { level: 1, name: 'Chat' })).toBeInTheDocument()
})

test('the root is still the status page until step 1', async () => {
  renderAt('/')

  expect(screen.getByRole('heading', { name: 'Finalysis' })).toBeInTheDocument()
  expect(await screen.findByText('ok')).toBeInTheDocument()
})

test('an unknown path goes to the root', async () => {
  renderAt('/nowhere')

  expect(screen.getByRole('heading', { name: 'Finalysis' })).toBeInTheDocument()
  expect(await screen.findByText('ok')).toBeInTheDocument()
})
