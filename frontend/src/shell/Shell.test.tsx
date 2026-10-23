import '@testing-library/jest-dom/vitest'
import { cleanup, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, test, vi } from 'vitest'
import { fixtures } from '../test/fixtures'
import { renderApp } from '../test/renderApp'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

const BANNER =
  'Cloud AI is on (Amazon Bedrock): merchant names and amounts are sent for categorization, and chat ' +
  'questions with the figures they need are sent for answers.'

const cloud = (provider: string) => ({
  ...fixtures.statusOff,
  ai: { mode: 'CLOUD', provider, models: { main: 'chat-model', categorization: 'cat-model', embedding: 'emb-model' } },
})

test('the root opens the Overview inside the shell', async () => {
  const { location } = renderApp('/')

  expect(await screen.findByRole('heading', { level: 1, name: 'Overview' })).toBeInTheDocument()
  expect(location()).toBe('/overview')
  expect(screen.getByText('Finalysis')).toBeInTheDocument()
})

test('the header tabs are the seven tabs in order, and the current one is marked', async () => {
  renderApp('/alerts')

  const nav = await screen.findByRole('navigation', { name: 'Main' })
  const links = within(nav).getAllByRole('link')
  expect(links.map((link) => link.textContent)).toEqual([
    'Overview',
    'Transactions',
    'Alerts',
    'Recurring',
    'Chat',
    'Goals',
    'Settings',
  ])
  expect(within(nav).getByRole('link', { name: 'Alerts' })).toHaveAttribute('aria-current', 'page')
})

test('the phone bar has three tabs and More, which opens the rest', async () => {
  const user = userEvent.setup()
  renderApp('/overview')

  const bar = await screen.findByRole('navigation', { name: 'Main (phone)' })
  expect(within(bar).getAllByRole('link').map((link) => link.textContent)).toEqual([
    'Overview',
    'Transactions',
    'Chat',
  ])
  const more = within(bar).getByRole('button', { name: 'More' })
  expect(more).toHaveAttribute('aria-expanded', 'false')

  await user.click(more)

  expect(more).toHaveAttribute('aria-expanded', 'true')
  expect(within(bar).getAllByRole('link').map((link) => link.textContent)).toEqual([
    'Alerts',
    'Recurring',
    'Goals',
    'Settings',
    'Overview',
    'Transactions',
    'Chat',
  ])
})

test('no banner while AI is off', async () => {
  renderApp('/overview')

  expect(await screen.findByRole('heading', { level: 1, name: 'Overview' })).toBeInTheDocument()
  expect(screen.queryByText(/Cloud AI is on/)).not.toBeInTheDocument()
})

test('the banner shows the SEC-01 wording while cloud AI is on, on every tab', async () => {
  for (const path of ['/overview', '/settings', '/chat']) {
    renderApp(path, { '/api/status': cloud('bedrock') })

    expect(await screen.findByText(/Cloud AI is on/)).toHaveTextContent(BANNER)
    cleanup()
  }
})

test('an unknown cloud provider is named as the backend reports it', async () => {
  renderApp('/overview', { '/api/status': cloud('othercloud') })

  expect(await screen.findByText(/Cloud AI is on/)).toHaveTextContent('Cloud AI is on (othercloud)')
})

test('the header names the account count and the month shown', async () => {
  renderApp('/overview')

  expect(await screen.findByText(`${fixtures.accounts.length} accounts · October 2026`)).toBeInTheDocument()
})

test('the picker offers the months with data, newest first, partial months marked', async () => {
  const { requests } = renderApp('/overview')

  const picker = await screen.findByRole('combobox', { name: 'Month' })
  expect(picker).toHaveValue('2026-10')
  expect(within(picker).getAllByRole('option').map((o) => o.textContent)).toEqual([
    'October 2026 (partial)',
    'September 2026',
    'August 2026',
    'July 2026',
    'June 2026',
    'May 2026',
  ])
  // From the first month any account has data to the latest, at most 24 months.
  expect(requests).toContain('/api/analytics/months?from=2026-04&to=2026-10')
})

test('choosing a month puts it in the URL, and the tabs keep it', async () => {
  const user = userEvent.setup()
  const { location } = renderApp('/overview?month=2026-10&keep=1')

  await user.selectOptions(await screen.findByRole('combobox', { name: 'Month' }), '2026-08')

  expect(location()).toBe('/overview?month=2026-08&keep=1')
  expect(screen.getByText(`${fixtures.accounts.length} accounts · August 2026`)).toBeInTheDocument()
  const nav = screen.getByRole('navigation', { name: 'Main' })
  expect(within(nav).getByRole('link', { name: 'Alerts' })).toHaveAttribute('href', '/alerts?month=2026-08')
})

test('a month without data in the URL falls back to the latest month', async () => {
  renderApp('/overview?month=2019-01')

  expect(await screen.findByRole('combobox', { name: 'Month' })).toHaveValue('2026-10')
})

test('without statements there is no picker and the tabs still work', async () => {
  renderApp('/settings', {
    '/api/analytics/coverage': { lastCoveredDay: null, latestMonth: null, latestCompleteMonth: null, accounts: [] },
    '/api/accounts': [],
  })

  expect(await screen.findByRole('heading', { level: 1, name: 'Settings' })).toBeInTheDocument()
  expect(screen.queryByRole('combobox', { name: 'Month' })).not.toBeInTheDocument()
  expect(screen.getByText('0 accounts')).toBeInTheDocument()
})

test('an unreachable backend is said plainly', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Failed to fetch')))
  const { MemoryRouter } = await import('react-router-dom')
  const { render } = await import('@testing-library/react')
  const { default: AppRoutes } = await import('../routes')
  render(
    <MemoryRouter initialEntries={['/overview']}>
      <AppRoutes />
    </MemoryRouter>,
  )

  await waitFor(() => expect(screen.getByText('Backend unreachable: Failed to fetch')).toBeInTheDocument())
})
