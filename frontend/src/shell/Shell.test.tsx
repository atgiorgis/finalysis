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

  expect(await screen.findByText('/ Overview')).toBeInTheDocument()
  expect(location()).toBe('/overview')
  expect(screen.getByText('Finalysis')).toBeInTheDocument()
})

const TAB_NAMES = ['Overview', 'Transactions', 'Import', 'Alerts', 'Recurring', 'Chat', 'Goals', 'Settings']

/** Opens the menu from the top bar and returns it. */
async function openMenu(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: 'Open menu' }))
  return screen.getByRole('dialog', { name: 'Menu' })
}

test('one top bar at every width: the menu button, the logo, the tab shown, the picker and the theme switch', async () => {
  renderApp('/alerts')

  const bar = await screen.findByRole('banner')
  expect(within(bar).getByRole('button', { name: 'Open menu' })).toHaveAttribute('aria-expanded', 'false')
  expect(within(bar).getByText('Finalysis')).toBeInTheDocument()
  expect(within(bar).getByText('/ Alerts')).toBeInTheDocument()
  expect(within(bar).getByRole('combobox', { name: 'Month' })).toBeInTheDocument()
  expect(within(bar).getByRole('switch', { name: 'Dark theme' })).toBeInTheDocument()
  // No tab bar and no bottom bar: the tabs are in the menu only, and the menu is closed.
  expect(screen.queryByRole('navigation', { name: 'Main' })).not.toBeInTheDocument()
  expect(screen.queryByRole('navigation', { name: 'Main (phone)' })).not.toBeInTheDocument()
})

test('the menu lists the eight tabs in order, the current one marked, and focus moves into it', async () => {
  const user = userEvent.setup()
  renderApp('/alerts')

  const menu = await openMenu(user)
  expect(screen.getByRole('button', { name: 'Open menu' })).toHaveAttribute('aria-expanded', 'true')
  expect(menu).toHaveAttribute('aria-modal', 'true')
  const nav = within(menu).getByRole('navigation', { name: 'Main' })
  const links = within(nav).getAllByRole('link')
  expect(links.map((link) => link.textContent)).toEqual(TAB_NAMES)
  expect(within(nav).getByRole('link', { name: 'Alerts' })).toHaveAttribute('aria-current', 'page')
  expect(within(nav).getByRole('link', { name: 'Alerts' })).toHaveFocus()
})

test('choosing a tab closes the menu, goes there and returns focus to the menu button', async () => {
  const user = userEvent.setup()
  const { location } = renderApp('/overview?month=2026-09')

  const menu = await openMenu(user)
  await user.click(within(menu).getByRole('link', { name: 'Recurring' }))

  expect(location()).toBe('/recurring?month=2026-09')
  expect(screen.queryByRole('dialog', { name: 'Menu' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Open menu' })).toHaveFocus()
  expect(screen.getByText('/ Recurring')).toBeInTheDocument()
})

test('✕ closes the menu and returns focus', async () => {
  const user = userEvent.setup()
  const { location } = renderApp('/settings')

  const menu = await openMenu(user)
  await user.click(within(menu).getByRole('button', { name: 'Close menu' }))

  expect(screen.queryByRole('dialog', { name: 'Menu' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Open menu' })).toHaveFocus()
  expect(location()).toBe('/settings')
})

test('the backdrop closes the menu and returns focus', async () => {
  const user = userEvent.setup()
  renderApp('/settings')

  await openMenu(user)
  await user.click(screen.getByTestId('sidebar-backdrop'))

  expect(screen.queryByRole('dialog', { name: 'Menu' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Open menu' })).toHaveFocus()
})

test('Escape closes the menu and returns focus', async () => {
  const user = userEvent.setup()
  renderApp('/settings')

  await openMenu(user)
  await user.keyboard('{Escape}')

  expect(screen.queryByRole('dialog', { name: 'Menu' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Open menu' })).toHaveFocus()
})

test('Tab stays inside the open menu, both ways', async () => {
  const user = userEvent.setup()
  renderApp('/overview')

  const menu = await openMenu(user)
  const close = within(menu).getByRole('button', { name: 'Close menu' })
  const settings = within(menu).getByRole('link', { name: 'Settings' })

  settings.focus()
  await user.tab()
  expect(close).toHaveFocus()
  await user.tab({ shift: true })
  expect(settings).toHaveFocus()
})

test('the menu names the account count and the month shown', async () => {
  const user = userEvent.setup()
  renderApp('/overview')

  const menu = await openMenu(user)
  expect(menu).toHaveTextContent(`${fixtures.accounts.length} accounts`)
  expect(menu).toHaveTextContent('Showing September 2026')
})

test('no banner while AI is off', async () => {
  renderApp('/overview')

  expect(await screen.findByRole('switch', { name: 'Dark theme' })).toBeInTheDocument()
  expect(await screen.findByText('Where it went')).toBeInTheDocument()
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

test('the picker opens on the latest complete month and lists every month with data, partial ones marked', async () => {
  const { requests } = renderApp('/overview')

  const picker = await screen.findByRole('combobox', { name: 'Month' })
  expect(picker).toHaveValue('2026-09')
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
  const menu = await openMenu(user)
  expect(menu).toHaveTextContent('Showing August 2026')
  expect(within(menu).getByRole('link', { name: 'Alerts' })).toHaveAttribute('href', '/alerts?month=2026-08')
})

test('‹ and › step through the months with data, and stop at either end', async () => {
  const user = userEvent.setup()
  const { location } = renderApp('/overview?month=2026-09')

  const picker = await screen.findByRole('combobox', { name: 'Month' })
  const previous = screen.getByRole('button', { name: 'Previous month' })
  const next = screen.getByRole('button', { name: 'Next month' })

  await user.click(previous)
  expect(location()).toBe('/overview?month=2026-08')
  expect(picker).toHaveValue('2026-08')

  await user.click(next)
  await user.click(next)
  expect(picker).toHaveValue('2026-10')
  expect(next).toBeDisabled()

  await user.selectOptions(picker, '2026-05')
  expect(previous).toBeDisabled()
  expect(next).toBeEnabled()
})

test('a month without data in the URL falls back to the latest complete month', async () => {
  renderApp('/overview?month=2019-01')

  expect(await screen.findByRole('combobox', { name: 'Month' })).toHaveValue('2026-09')
})

test('the partial month can still be chosen', async () => {
  renderApp('/overview?month=2026-10')

  expect(await screen.findByRole('combobox', { name: 'Month' })).toHaveValue('2026-10')
})

test('when no month is complete, the latest month', async () => {
  renderApp('/overview', { '/api/analytics/coverage': { ...fixtures.coverage, latestCompleteMonth: null } })

  expect(await screen.findByRole('combobox', { name: 'Month' })).toHaveValue('2026-10')
})

test('without statements there is no picker and the tabs still work', async () => {
  renderApp('/settings', {
    '/api/analytics/coverage': { lastCoveredDay: null, latestMonth: null, latestCompleteMonth: null, accounts: [] },
    '/api/accounts': [],
  })

  expect(await screen.findByRole('heading', { level: 1, name: 'Settings' })).toBeInTheDocument()
  expect(screen.queryByRole('combobox', { name: 'Month' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Previous month' })).not.toBeInTheDocument()
  const menu = await openMenu(userEvent.setup())
  expect(menu).toHaveTextContent('0 accounts')
  expect(within(menu).getAllByRole('link')).toHaveLength(8)
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
