import '@testing-library/jest-dom/vitest'
import { cleanup, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, test, vi } from 'vitest'
import type { Transaction } from '../api'
import { fixtures } from '../test/fixtures'
import { renderApp } from '../test/renderApp'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

const MINUS = '−'
const SEPTEMBER = '/api/transactions?from=2026-09-01&to=2026-09-30&page=0&size=50'
const UNCATEGORIZED = '/api/transactions?uncategorized=true&page=0&size=50'

/** Two transactions waiting for a category, from the captured one: one the AI left, one marked not a transfer. */
const waiting: Transaction[] = [
  { ...fixtures.transactionDetail, category: null, categorySource: null, aiReviewReason: 'LOW_CONFIDENCE' },
  {
    ...fixtures.transactionDetail,
    id: 9001,
    merchant: 'CORNER KIOSK',
    category: null,
    categorySource: null,
    transferExcluded: true,
  },
]

const ROUTES: Record<string, unknown> = {
  [SEPTEMBER]: fixtures.transactionsPage,
  [UNCATEGORIZED]: { items: waiting, page: 0, size: 50, total: 2 },
  '/api/review-queue': fixtures.reviewQueue,
  [`/api/transactions/${fixtures.transactionDetail.id}`]: fixtures.transactionDetail,
}

test('the month’s transactions, newest first, with their count and Show more', async () => {
  const user = userEvent.setup()
  const { requests } = renderApp('/transactions?month=2026-09', ROUTES)

  const list = await screen.findByRole('region', { name: 'Transactions list' })
  expect(requests).toContain(SEPTEMBER)
  expect(within(list).getByText('265 transactions in September 2026')).toBeInTheDocument()
  const rows = list.querySelectorAll('.txn-row')
  expect(rows).toHaveLength(25)
  expect(rows[0]).toHaveTextContent(`Sep 30TRADER JOE SApex Visa (travel card) · Groceries${MINUS}$74.09`)
  expect(rows[1]).toHaveTextContent('ONLINE PAYMENT THANK YOUSummit Cash Card · Transfer · transfer$467.76')

  await user.click(within(list).getByRole('button', { name: 'Show more' }))
  expect(requests).toContain('/api/transactions?from=2026-09-01&to=2026-09-30&page=1&size=50')
})

test('the filters go in the URL and in the request', async () => {
  const user = userEvent.setup()
  const { requests, location } = renderApp('/transactions?month=2026-09', ROUTES)

  await screen.findByRole('region', { name: 'Transactions list' })
  await user.selectOptions(screen.getByRole('combobox', { name: 'Category' }), 'Groceries')
  expect(location()).toBe('/transactions?month=2026-09&category=2')
  expect(requests).toContain('/api/transactions?from=2026-09-01&to=2026-09-30&categoryId=2&page=0&size=50')

  await user.selectOptions(screen.getByRole('combobox', { name: 'Account' }), 'Apex Visa (travel card)')
  expect(requests).toContain(
    '/api/transactions?from=2026-09-01&to=2026-09-30&categoryId=2&accountId=3&page=0&size=50',
  )

  await user.selectOptions(screen.getByRole('combobox', { name: 'Category' }), 'Uncategorized')
  expect(requests).toContain(
    '/api/transactions?from=2026-09-01&to=2026-09-30&uncategorized=true&accountId=3&page=0&size=50',
  )
})

test('a month from the picker changes the dates asked for', async () => {
  const user = userEvent.setup()
  const { requests } = renderApp('/transactions?month=2026-09', ROUTES)

  await user.selectOptions(await screen.findByRole('combobox', { name: 'Month' }), '2026-06')

  expect(requests).toContain('/api/transactions?from=2026-06-01&to=2026-06-30&page=0&size=50')
})

test('a row opens its sheet', async () => {
  const user = userEvent.setup()
  const { location } = renderApp('/transactions?month=2026-09', ROUTES)

  const list = await screen.findByRole('region', { name: 'Transactions list' })
  await user.click(list.querySelectorAll<HTMLElement>('.txn-row')[0])

  expect(location()).toBe(`/transactions?month=2026-09&txn=${fixtures.transactionDetail.id}`)
  expect(await screen.findByRole('dialog', { name: 'TRADER JOE S' })).toBeInTheDocument()
})

test('to review: transactions without a category, every month, with why they wait', async () => {
  const { requests } = renderApp('/transactions?month=2026-09&view=review', ROUTES)

  const section = await screen.findByRole('region', { name: 'Needs a category' })
  expect(requests).toContain(UNCATEGORIZED)
  expect(await within(section).findByText('2 transactions, every month')).toBeInTheDocument()
  const rows = section.querySelectorAll('.txn-row')
  expect(rows[0]).toHaveTextContent('Uncategorized · The AI wasn’t sure')
  expect(rows[1]).toHaveTextContent('CORNER KIOSK')
  expect(rows[1]).toHaveTextContent('Uncategorized · marked not a transfer')
  expect(screen.getByRole('button', { name: 'To review' })).toHaveAttribute('aria-pressed', 'true')
})

test('to review: transfers without a match, and the kinds left to the API until Phase 8', async () => {
  renderApp('/transactions?view=review', ROUTES)

  const transfers = await screen.findByRole('region', { name: 'Transfers without a match' })
  expect(await within(transfers).findAllByRole('button')).toHaveLength(8)
  expect(within(transfers).getAllByRole('button')[0]).toHaveTextContent('ONLINE TRANSFER TO SAVINGS XXXXXX1002')
  const elsewhere = screen.getByRole('region', { name: 'Also in the review queue' })
  expect(within(elsewhere).getByText('1 statement needs review')).toBeInTheDocument()
  expect(elsewhere).toHaveTextContent('resolved through the API until the upload page (Phase 8)')
})

test('nothing to review says so', async () => {
  renderApp('/transactions?view=review', {
    ...ROUTES,
    [UNCATEGORIZED]: { items: [], page: 0, size: 50, total: 0 },
    '/api/review-queue': {
      counts: { ...fixtures.reviewQueue.counts, statements: 0, suggestedOneSidedTransfers: 0 },
      items: [],
    },
  })

  expect(await screen.findByText('Every transaction has a category.')).toBeInTheDocument()
  expect(await screen.findByText('None.')).toBeInTheDocument()
  expect(screen.queryByRole('region', { name: 'Also in the review queue' })).not.toBeInTheDocument()
})

test('a failed list read is said plainly', async () => {
  renderApp('/transactions?month=2026-09', { ...ROUTES, [SEPTEMBER]: undefined })

  expect(await screen.findByText(/Couldn’t load the transactions/)).toBeInTheDocument()
})
