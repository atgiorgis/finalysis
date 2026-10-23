import '@testing-library/jest-dom/vitest'
import { cleanup, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, test, vi } from 'vitest'
import { fixtures } from '../test/fixtures'
import { renderApp } from '../test/renderApp'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

const MINUS = '−'
const SEPTEMBER = { '/api/dashboard': fixtures.dashboardCompleteMonth }

/** The tiles as label → the texts under it. */
function tiles(): Record<string, string[]> {
  const out: Record<string, string[]> = {}
  for (const tile of document.querySelectorAll('.kpi-tile')) {
    const [label, ...rest] = [...tile.children].map((c) => c.textContent ?? '')
    out[label] = rest
  }
  return out
}

test('one call for the month shown, with every KPI and its change as the API returns them', async () => {
  const { requests } = renderApp('/overview?month=2026-09', SEPTEMBER)

  expect(await screen.findByRole('heading', { name: 'Where the money went · September 2026' })).toBeInTheDocument()
  expect(requests.filter((r) => r.startsWith('/api/dashboard'))).toEqual(['/api/dashboard?month=2026-09'])
  expect(tiles()).toEqual({
    Spending: ['$3,549.07', `${MINUS}$924.03 (${MINUS}20.7%) vs Aug`],
    Income: ['$4,900.24', '+$0.15 (0.0%) vs Aug'],
    Net: ['$1,351.17', '+$924.18 (+216.4%) vs Aug'],
    'Savings rate': ['27.6%', '+18.9 pts vs Aug'],
  })
  // Less spending and more net are good news; the tones follow the sign of the API's strings.
  expect(screen.getByText(`${MINUS}$924.03 (${MINUS}20.7%) vs Aug`)).toHaveClass('kpi-good')
  expect(screen.getByText('+$924.18 (+216.4%) vs Aug')).toHaveClass('kpi-good')
  expect(screen.queryByText(/is partial/)).not.toBeInTheDocument()
})

test('data quality: categorized share, review count and coverage', async () => {
  renderApp('/overview?month=2026-09', SEPTEMBER)

  const tile = await screen.findByRole('region', { name: 'Data quality' })
  expect(tile).toHaveTextContent('100.0% categorized · 0 waiting for review')
  expect(within(tile).getByText('Complete month')).toBeInTheDocument()
})

test('the donut legend names every slice with its share and amount, Other with its members', async () => {
  renderApp('/overview?month=2026-09', SEPTEMBER)

  const legend = await screen.findByRole('list', { name: 'Spending by category' })
  const rows = within(legend)
    .getAllByRole('listitem')
    .map((row) => [...row.querySelectorAll('span:not(.legend-swatch)')].map((s) => s.textContent))
  expect(rows).toEqual([
    ['Housing', '52.1%', '$1,850.00'],
    ['Groceries', '13.5%', '$477.94'],
    ['Shopping', '11.1%', '$394.75'],
    ['Dining', '10.1%', '$356.91'],
    ['Utilities', '6.5%', '$231.52'],
    ['Transportation', '4.7%', '$168.10'],
    ['Other (2 categories)', '2.0%', '$69.85', 'Health, Subscriptions'],
  ])
  expect(screen.getByText('$3,549.07', { selector: '.donut-centre strong' })).toBeInTheDocument()
})

test('each slice has its own color: six by rank, then the grey of Other', async () => {
  renderApp('/overview?month=2026-09', SEPTEMBER)

  const legend = await screen.findByRole('list', { name: 'Spending by category' })
  const colors = [...legend.querySelectorAll<HTMLElement>('.legend-swatch')].map((s) => s.style.background)
  expect(colors).toEqual([
    'var(--slice-1)',
    'var(--slice-2)',
    'var(--slice-3)',
    'var(--slice-4)',
    'var(--slice-5)',
    'var(--slice-6)',
    'var(--slice-other)',
  ])
})

test('the cash flow lists six months with their net, partial months marked, the month shown pressed', async () => {
  renderApp('/overview?month=2026-09', SEPTEMBER)

  const months = await screen.findByRole('list', { name: 'Months' })
  const buttons = within(months).getAllByRole('button')
  expect(buttons.map((b) => b.textContent)).toEqual([
    'Apr*$4,185.13',
    'May$3,918.95',
    'Jun$1,261.12',
    'Jul$1,405.77',
    'Aug$426.99',
    'Sep$1,351.17',
  ])
  expect(buttons[5]).toHaveAttribute('aria-pressed', 'true')
  expect(buttons[0]).toHaveAccessibleName(/^April 2026 \(partial\): income/)
})

test('choosing a month in the chart shows that month', async () => {
  const user = userEvent.setup()
  const { requests, location } = renderApp('/overview?month=2026-09', {
    '/api/dashboard?month=2026-09': fixtures.dashboardCompleteMonth,
    '/api/dashboard?month=2026-08': { ...fixtures.dashboardCompleteMonth, month: '2026-08' },
  })

  const months = await screen.findByRole('list', { name: 'Months' })
  await user.click(within(months).getByRole('button', { name: /^August 2026/ }))

  expect(location()).toBe('/overview?month=2026-08')
  expect(await screen.findByRole('heading', { name: 'Where the money went · August 2026' })).toBeInTheDocument()
  expect(requests).toContain('/api/dashboard?month=2026-08')
})

test('at a glance: income, spending, transfers excluded and unmatched transfers not counted', async () => {
  renderApp('/overview?month=2026-09', SEPTEMBER)

  const glance = await screen.findByRole('region', { name: 'September 2026 at a glance' })
  expect(glance).toHaveTextContent('Income$4,900.24')
  expect(glance).toHaveTextContent('Spending$3,549.07')
  expect(glance).toHaveTextContent('Transfers between your accountsExcluded ($1,403.46 moved)')
  expect(glance).toHaveTextContent('Transfers not matched yetNot counted (1 transaction)')
  expect(glance).not.toHaveTextContent('Refunds')
})

test('alerts: counts per type and the newest three with their basis, linked to the Alerts tab', async () => {
  renderApp('/overview?month=2026-09', SEPTEMBER)

  const alerts = await screen.findByRole('region', { name: 'Alerts' })
  expect(within(alerts).getByText('Jul to Sep · rule-based')).toBeInTheDocument()
  expect(within(alerts).getByRole('list', { name: 'Alerts by type' })).toHaveTextContent(
    'Spending spike: 2Large transaction: 1Price increase: 1',
  )
  const first = within(alerts).getByRole('link', { name: 'Spending spike: Dining' })
  expect(first.getAttribute('href')).toMatch(/^\/alerts\?month=2026-09&key=category-spike\.2026-08\./)
  expect(first.parentElement).toHaveTextContent('Aug · $340.63 against $311.68')
  expect(within(alerts).getByRole('link', { name: 'All alerts' })).toHaveAttribute('href', '/alerts?month=2026-09')
})

test('recurring: how many are active and the first six', async () => {
  renderApp('/overview?month=2026-09', SEPTEMBER)

  const recurring = await screen.findByRole('region', { name: 'Recurring charges' })
  expect(recurring).toHaveTextContent('7 active series')
  const rows = within(recurring).getAllByRole('row').slice(1)
  expect(rows).toHaveLength(6)
  expect(rows[0]).toHaveTextContent('CITY OF ARLINGTON WATER UTIL PMT · Utilities$46.81Oct 22')
  expect(within(recurring).getByRole('link', { name: 'All recurring charges' })).toHaveAttribute(
    'href',
    '/recurring?month=2026-09',
  )
})

test('a partial month with nothing yet: the caveat, no spending, the flags by account', async () => {
  renderApp('/overview', { '/api/dashboard': fixtures.dashboardLatest })

  expect(
    await screen.findByText(
      'October 2026 is partial: its figures can still grow, so the changes compare part of a month with all of ' +
        'September 2026.',
    ),
  ).toBeInTheDocument()
  expect(screen.getByText('No spending this month.')).toBeInTheDocument()
  const quality = screen.getByRole('region', { name: 'Data quality' })
  expect(within(quality).getByText('Partial month: figures can still grow')).toBeInTheDocument()
  expect(within(quality).getAllByRole('listitem').map((li) => li.textContent)).toEqual(
    [1, 3, 4].map((id) => `${fixtures.accounts.find((a) => a.id === id)?.name}: statements don’t cover the whole month`),
  )
  expect(quality).toHaveTextContent('— categorized')
  expect(screen.getByText('No income this month')).toBeInTheDocument()
})

test('without statements: one sentence, no tiles', async () => {
  renderApp('/overview', {
    '/api/analytics/coverage': { lastCoveredDay: null, latestMonth: null, latestCompleteMonth: null, accounts: [] },
    '/api/dashboard': {
      ...fixtures.dashboardLatest,
      month: null,
      totals: null,
      vsLastMonth: null,
      cashFlow: [],
      spendingSlices: { spending: '0.00', rows: [] },
    },
  })

  expect(await screen.findByText(/No statements yet/)).toBeInTheDocument()
  expect(document.querySelector('.kpi-tile')).toBeNull()
})

test('a failed read is said plainly', async () => {
  renderApp('/overview?month=2026-09', { '/api/dashboard': undefined })

  expect(await screen.findByText(/Couldn’t load the overview/)).toBeInTheDocument()
})
