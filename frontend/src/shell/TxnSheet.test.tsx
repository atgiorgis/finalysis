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
const TXN = fixtures.transactionDetail
const GROCERIES = fixtures.detailsCategory.category?.id

const ROUTES: Record<string, unknown> = {
  '/api/dashboard': fixtures.dashboardCompleteMonth,
  [`/api/dashboard/details?month=2026-09&categoryId=${GROCERIES}`]: fixtures.detailsCategory,
  [`/api/analytics/transactions?${new URLSearchParams({
    ...fixtures.detailsCategory.drillDown.params,
    page: '0',
    size: '50',
  })}`]: fixtures.drillDownCategory,
  [`/api/transactions/${TXN.id}`]: TXN,
}

function facts(dialog: HTMLElement): Record<string, string> {
  return Object.fromEntries(
    within(dialog)
      .getAllByRole('term')
      .map((dt) => [dt.textContent ?? '', dt.nextElementSibling?.textContent ?? '']),
  )
}

test('a row of the details panel opens its sheet: the statement and the line it came from', async () => {
  const user = userEvent.setup()
  const { location, requests } = renderApp(`/overview?month=2026-09&detail=cat:${GROCERIES}`, ROUTES)

  const panel = await screen.findByRole('region', { name: 'Groceries' })
  const first = (await within(panel).findAllByRole('button', { name: /TRADER JOE S/ }))[0]
  await user.click(first)

  expect(location()).toBe(`/overview?month=2026-09&detail=cat%3A${GROCERIES}&txn=${TXN.id}`)
  expect(requests).toContain(`/api/transactions/${TXN.id}`)
  const dialog = await screen.findByRole('dialog', { name: 'TRADER JOE S' })
  expect(within(dialog).getByText(`${MINUS}$74.09`)).toBeInTheDocument()
  expect(facts(dialog)).toEqual({
    Date: 'Sep 30, 2026 · posted Oct 1, 2026',
    Account: 'Apex Visa (travel card)',
    Category: 'Groceries (set by a rule)',
    Description: 'TRADER JOE S #662 ARLINGTON VA',
    Statement: 'Sep 15, 2026 to Oct 14, 2026',
  })
  expect(within(dialog).getByText('Line 12 of the statement file, as printed')).toBeInTheDocument()
  expect(within(dialog).getByText(TXN.sourceLine?.text ?? 'missing')).toBeInTheDocument()
  expect(within(dialog).getByRole('heading', { name: 'TRADER JOE S' })).toHaveFocus()
})

test('Close, Escape and the backdrop each close it and remove ?txn, keeping the page’s own parameters', async () => {
  const user = userEvent.setup()
  for (const how of ['button', 'escape', 'backdrop'] as const) {
    const { location } = renderApp(`/overview?month=2026-09&txn=${TXN.id}`, ROUTES)
    const dialog = await screen.findByRole('dialog', { name: 'TRADER JOE S' })
    if (how === 'button') {
      await user.click(within(dialog).getByRole('button', { name: 'Close' }))
    } else if (how === 'escape') {
      await user.keyboard('{Escape}')
    } else {
      await user.click(dialog.parentElement as HTMLElement)
    }
    expect(location(), how).toBe('/overview?month=2026-09')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    cleanup()
  }
})

test('a click inside the sheet keeps it open', async () => {
  const user = userEvent.setup()
  renderApp(`/overview?month=2026-09&txn=${TXN.id}`, ROUTES)

  const dialog = await screen.findByRole('dialog', { name: 'TRADER JOE S' })
  await user.click(within(dialog).getByText('Account'))

  expect(screen.getByRole('dialog')).toBeInTheDocument()
})

test('a transfer says it isn’t counted; a missing source line says so', async () => {
  renderApp(`/overview?month=2026-09&txn=${TXN.id}`, {
    ...ROUTES,
    [`/api/transactions/${TXN.id}`]: { ...TXN, isTransfer: true, sourceLine: null, category: null, categorySource: null },
  })

  const dialog = await screen.findByRole('dialog', { name: 'TRADER JOE S' })
  expect(facts(dialog)).toMatchObject({
    Category: 'Uncategorized',
    Transfer: 'Between your own accounts: not counted as spending or income',
  })
  expect(within(dialog).getByText('This import didn’t keep the statement line.')).toBeInTheDocument()
})

test('an unknown transaction is said plainly', async () => {
  renderApp('/overview?month=2026-09&txn=999999', ROUTES)

  const dialog = await screen.findByRole('dialog', { name: 'Transaction not found' })
  expect(within(dialog).getByText(/Couldn’t load this transaction/)).toBeInTheDocument()
})

test('a malformed ?txn opens nothing and reads nothing', async () => {
  const { requests } = renderApp('/overview?month=2026-09&txn=1;DROP', ROUTES)

  expect(await screen.findByRole('heading', { name: 'Where it went' })).toBeInTheDocument()
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(requests.filter((r) => r.startsWith('/api/transactions/'))).toEqual([])
})

test('/transactions/:id opens the Transactions tab with the sheet, keeping the month', async () => {
  const { location } = renderApp(`/transactions/${TXN.id}?month=2026-09`, ROUTES)

  expect(await screen.findByRole('dialog', { name: 'TRADER JOE S' })).toBeInTheDocument()
  expect(location()).toBe(`/transactions?month=2026-09&txn=${TXN.id}`)
})
