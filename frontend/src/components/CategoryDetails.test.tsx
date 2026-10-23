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
const GROCERIES = fixtures.detailsCategory.category?.id
const HEALTH = fixtures.detailsOtherMember.category?.id

const drill = (params: Record<string, string>, page = 0) =>
  `/api/analytics/transactions?${new URLSearchParams({ ...params, page: String(page), size: '50' })}`

/** September's Overview, its three panels and the rows each names. */
const ROUTES: Record<string, unknown> = {
  '/api/dashboard': fixtures.dashboardCompleteMonth,
  [`/api/dashboard/details?month=2026-09&categoryId=${GROCERIES}`]: fixtures.detailsCategory,
  [drill(fixtures.detailsCategory.drillDown.params)]: fixtures.drillDownCategory,
  [`/api/dashboard/details?month=2026-09&categoryId=${HEALTH}`]: fixtures.detailsOtherMember,
  [drill(fixtures.detailsOtherMember.drillDown.params)]: fixtures.drillDownOtherMember,
  '/api/dashboard/details?month=2026-09&line=INCOME': fixtures.detailsIncome,
  [drill(fixtures.detailsIncome.drillDown.params)]: fixtures.drillDownIncome,
}

async function legendRow(name: RegExp) {
  const legend = await screen.findByRole('list', { name: 'Spending by category' })
  return within(legend).getByRole('button', { name })
}

test('a legend row opens its panel: total, share, the month against its average, in the URL', async () => {
  const user = userEvent.setup()
  const { location, requests } = renderApp('/overview?month=2026-09', ROUTES)

  await user.click(await legendRow(/^Groceries/))

  const panel = await screen.findByRole('region', { name: 'Groceries' })
  expect(location()).toBe(`/overview?month=2026-09&detail=cat%3A${GROCERIES}`)
  expect(requests).toContain(`/api/dashboard/details?month=2026-09&categoryId=${GROCERIES}`)
  expect(within(panel).getByText('September 2026 › Groceries')).toBeInTheDocument()
  expect(within(panel).getByText('$477.94', { selector: '.details-total' })).toBeInTheDocument()
  expect(
    within(panel).getByText(
      `13.5% of spending · $189.25 (${MINUS}28.4%) below the 3-month average ($667.19)`,
    ),
  ).toBeInTheDocument()
  expect(await legendRow(/^Groceries/)).toHaveAttribute('aria-pressed', 'true')
})

test('the panel heading takes the focus', async () => {
  const user = userEvent.setup()
  renderApp('/overview?month=2026-09', ROUTES)

  await user.click(await legendRow(/^Groceries/))

  const heading = await screen.findByRole('heading', { level: 2, name: 'Groceries' })
  expect(heading).toHaveFocus()
})

test('the 6-month trend lists each month, partial ones marked; top merchants as returned', async () => {
  renderApp(`/overview?month=2026-09&detail=cat:${GROCERIES}`, ROUTES)

  const trend = await screen.findByRole('list', { name: '6-month trend' })
  expect(within(trend).getAllByRole('listitem').map((li) => li.textContent)).toEqual([
    'Apr*$691.89',
    'May$544.99',
    'Jun$832.68',
    'Jul$627.89',
    'Aug$540.99',
    'Sep$477.94',
  ])
  const merchants = screen.getByRole('list', { name: 'Top merchants' })
  expect(within(merchants).getAllByRole('listitem').map((li) => li.textContent)).toEqual([
    'SAFEWAY$217.12',
    'TRADER JOE S$172.51',
    'GIANT FOOD$88.31',
  ])
})

test('the transactions behind the total add up to the figure the Overview showed: ✓', async () => {
  renderApp(`/overview?month=2026-09&detail=cat:${GROCERIES}`, ROUTES)

  const check = await screen.findByText(/8 transactions ·/)
  expect(check).toHaveTextContent('8 transactions · add up to $477.94 ✓')
  expect(check).not.toHaveClass('adds-up-not')
  const panel = screen.getByRole('region', { name: 'Groceries' })
  const rows = [...panel.querySelectorAll('.txn-row')]
  expect(rows).toHaveLength(8)
  expect(rows[0]).toHaveTextContent(`Sep 30TRADER JOE S${MINUS}$74.09`)
  // The slice the Overview showed is the same string as the panel's total and the rows' total.
  const slice = fixtures.dashboardCompleteMonth.spendingSlices.rows.find((s) => s.category?.id === GROCERIES)
  expect(slice?.amount).toBe(fixtures.detailsCategory.total)
  expect(fixtures.drillDownCategory.total.net).toBe(fixtures.detailsCategory.total)
})

test('rows that don’t add up to the panel are shown as such, without the ✓', async () => {
  renderApp(`/overview?month=2026-09&detail=cat:${GROCERIES}`, {
    ...ROUTES,
    [drill(fixtures.detailsCategory.drillDown.params)]: {
      ...fixtures.drillDownCategory,
      total: { ...fixtures.drillDownCategory.total, net: '-470.00' },
    },
  })

  const check = await screen.findByText(/8 transactions ·/)
  expect(check).toHaveTextContent('8 transactions · add up to $470.00, not $477.94')
  expect(check).toHaveClass('adds-up-not')
  expect(check).not.toHaveTextContent('✓')
})

test('a panel whose total differs from the slice shown is flagged too', async () => {
  renderApp(`/overview?month=2026-09&detail=cat:${GROCERIES}`, {
    ...ROUTES,
    '/api/dashboard': {
      ...fixtures.dashboardCompleteMonth,
      spendingSlices: {
        ...fixtures.dashboardCompleteMonth.spendingSlices,
        rows: fixtures.dashboardCompleteMonth.spendingSlices.rows.map((s) =>
          s.category?.id === GROCERIES ? { ...s, amount: '-480.00' } : s,
        ),
      },
    },
  })

  const check = await screen.findByText(/8 transactions ·/)
  expect(check).toHaveTextContent('8 transactions · add up to $477.94, not $480.00')
  expect(check).toHaveClass('adds-up-not')
})

test('more rows than a page: Show more asks for the next page', async () => {
  const user = userEvent.setup()
  const params = fixtures.detailsCategory.drillDown.params
  const { requests } = renderApp(`/overview?month=2026-09&detail=cat:${GROCERIES}`, {
    ...ROUTES,
    [drill(params)]: { ...fixtures.drillDownCategory, totalPages: 2, items: fixtures.drillDownCategory.items.slice(0, 5) },
    [drill(params, 1)]: { ...fixtures.drillDownCategory, page: 1, totalPages: 2, items: fixtures.drillDownCategory.items.slice(5) },
  })

  await user.click(await screen.findByRole('button', { name: 'Show more' }))

  expect(requests).toContain(drill(params, 1))
  const panel = screen.getByRole('region', { name: 'Groceries' })
  expect([...panel.querySelectorAll('.txn-row')]).toHaveLength(8)
  expect(screen.queryByRole('button', { name: 'Show more' })).not.toBeInTheDocument()
})

test('Other lists its members; a member opens its own panel, with its ✓ against the member’s amount', async () => {
  const user = userEvent.setup()
  const { location } = renderApp('/overview?month=2026-09', ROUTES)

  await user.click(await legendRow(/^Other/))
  const other = await screen.findByRole('region', { name: 'Other' })
  expect(within(other).getByText('$69.85', { selector: '.details-total' })).toBeInTheDocument()
  await user.click(within(other).getByRole('button', { name: /^Health/ }))

  expect(location()).toBe(`/overview?month=2026-09&detail=cat%3A${HEALTH}`)
  const health = await screen.findByRole('region', { name: 'Health' })
  expect(within(health).getByText(`$6.12 (+19.0%) above the 3-month average ($32.26)`, { exact: false })).toBeInTheDocument()
  expect(await within(health).findByText(/1 transaction ·/)).toHaveTextContent('1 transaction · add up to $38.38 ✓')
})

test('income opens from at a glance: no share, no merchants, rows add up to the month’s income', async () => {
  const user = userEvent.setup()
  renderApp('/overview?month=2026-09', ROUTES)

  await user.click(await screen.findByRole('button', { name: 'Income $4,900.24: details' }))

  const panel = await screen.findByRole('region', { name: 'Income' })
  expect(within(panel).getByText('$4,900.24', { selector: '.details-total' })).toBeInTheDocument()
  expect(within(panel).getByText(/^\$0\.80 \(0\.0%\) below the 3-month average \(\$4,901\.04\)$/)).toBeInTheDocument()
  expect(within(panel).queryByRole('list', { name: 'Top merchants' })).not.toBeInTheDocument()
  expect(await within(panel).findByText(/2 transactions ·/)).toHaveTextContent('2 transactions · add up to $4,900.24 ✓')
})

test('Close removes the panel and its URL parameter', async () => {
  const user = userEvent.setup()
  const { location } = renderApp(`/overview?month=2026-09&detail=cat:${GROCERIES}`, ROUTES)

  await user.click(await screen.findByRole('button', { name: 'Close' }))

  expect(location()).toBe('/overview?month=2026-09')
  expect(screen.queryByRole('region', { name: 'Groceries' })).not.toBeInTheDocument()
})

test('no complete month before: the panel says so instead of an average', async () => {
  renderApp(`/overview?month=2026-09&detail=cat:${GROCERIES}`, {
    ...ROUTES,
    [`/api/dashboard/details?month=2026-09&categoryId=${GROCERIES}`]: {
      ...fixtures.detailsCategory,
      average: { amount: null, monthsUsed: 0, monthsWanted: 3, differenceFromAverage: null, differencePercent: null },
    },
  })

  expect(
    await screen.findByText('13.5% of spending · No complete month among the three before to compare with.'),
  ).toBeInTheDocument()
})

test('a failed details read is said plainly', async () => {
  renderApp(`/overview?month=2026-09&detail=cat:${GROCERIES}`, {
    ...ROUTES,
    [`/api/dashboard/details?month=2026-09&categoryId=${GROCERIES}`]: undefined,
  })

  expect(await screen.findByText(/Couldn’t load the details/)).toBeInTheDocument()
})
