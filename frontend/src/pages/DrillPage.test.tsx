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
const PARAMS = fixtures.detailsCategory.drillDown.params
const page = (n: number) =>
  `/api/analytics/transactions?${new URLSearchParams({ ...PARAMS, page: String(n), size: '50' })}`

const ROUTES: Record<string, unknown> = {
  '/api/categories': fixtures.categories,
  [page(0)]: fixtures.drillDownCategory,
  [`/api/transactions/${fixtures.transactionDetail.id}`]: fixtures.transactionDetail,
}

const drillUrl = (extra: Record<string, string> = {}) =>
  `/drill?${new URLSearchParams({ ...PARAMS, ...extra })}`

test('the exact drill-down parameters are sent, and the rows, their total and the filters shown', async () => {
  const { requests } = renderApp(drillUrl({ label: 'Groceries in September 2026' }), ROUTES)

  expect(await screen.findByRole('heading', { level: 1, name: 'Groceries in September 2026' })).toBeInTheDocument()
  expect(await screen.findByText('Groceries', { selector: '.chip' })).toBeInTheDocument()
  await screen.findByRole('region', { name: 'Rows' })
  expect(requests).toContain(page(0))
  expect(within(screen.getByRole('list', { name: 'Filters' })).getAllByRole('listitem').map((li) => li.textContent))
    .toEqual(['September 2026', 'Spending', 'Groceries'])
  const rows = screen.getByRole('region', { name: 'Rows' })
  expect(rows.querySelectorAll('.txn-row')).toHaveLength(8)
  expect(within(rows).getByText(`${MINUS}$477.94`, { selector: '.details-total' })).toBeInTheDocument()
  expect(within(rows).getByRole('status')).toHaveTextContent(`8 transactions · add up to ${MINUS}$477.94`)
  expect(within(rows).getByRole('status')).not.toHaveTextContent('✓')
})

test('only the drill-down’s own parameters are forwarded', async () => {
  const { requests } = renderApp(`${drillUrl()}&evil=1&size=999&page=7&month2=x`, ROUTES)

  await screen.findByRole('region', { name: 'Rows' })
  const sent = requests.filter((r) => r.startsWith('/api/analytics/transactions'))
  expect(sent).toEqual([page(0)])
})

test('with the figure it must add up to: ✓ when the rows’ total is that string', async () => {
  renderApp(drillUrl({ expect: '-477.94' }), ROUTES)

  const status = await screen.findByRole('status')
  expect(status).toHaveTextContent(`8 transactions · add up to ${MINUS}$477.94 ✓`)
  expect(status).not.toHaveClass('adds-up-not')
})

test('and the difference when it isn’t', async () => {
  renderApp(drillUrl({ expect: '-480.00' }), ROUTES)

  const status = await screen.findByRole('status')
  expect(status).toHaveTextContent(`8 transactions · add up to ${MINUS}$477.94, not ${MINUS}$480.00`)
  expect(status).toHaveClass('adds-up-not')
})

test('a row opens the transaction sheet', async () => {
  const user = userEvent.setup()
  renderApp(drillUrl(), ROUTES)

  const rows = await screen.findByRole('region', { name: 'Rows' })
  await user.click(within(rows).getAllByRole('button', { name: /TRADER JOE S/ })[0])

  expect(await screen.findByRole('dialog', { name: 'TRADER JOE S' })).toBeInTheDocument()
})

test('Show more asks for the next page with the same parameters', async () => {
  const user = userEvent.setup()
  const { requests } = renderApp(drillUrl(), {
    ...ROUTES,
    [page(0)]: { ...fixtures.drillDownCategory, totalPages: 2, items: fixtures.drillDownCategory.items.slice(0, 4) },
    [page(1)]: { ...fixtures.drillDownCategory, page: 1, totalPages: 2, items: fixtures.drillDownCategory.items.slice(4) },
  })

  await user.click(await screen.findByRole('button', { name: 'Show more' }))

  expect(requests).toContain(page(1))
  expect(screen.getByRole('region', { name: 'Rows' }).querySelectorAll('.txn-row')).toHaveLength(8)
})

test('a failed read is said plainly', async () => {
  renderApp(drillUrl(), { ...ROUTES, [page(0)]: undefined })

  expect(await screen.findByText(/Couldn’t load the transactions/)).toBeInTheDocument()
})
