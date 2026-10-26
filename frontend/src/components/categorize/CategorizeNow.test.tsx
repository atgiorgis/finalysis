import '@testing-library/jest-dom/vitest'
import { cleanup, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, test, vi } from 'vitest'
import type { CategorizationRun, Transaction } from '../../api'
import { fixtures } from '../../test/fixtures'
import { renderApp } from '../../test/renderApp'
import { indexedText, runSummaryText, stoppedText } from './runText'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

const UNCATEGORIZED = '/api/transactions?uncategorized=true&page=0&size=50'
const waiting: Transaction[] = [
  { ...fixtures.transactionDetail, category: null, categorySource: null, aiReviewReason: 'LOW_CONFIDENCE' },
]
const ROUTES: Record<string, unknown> = {
  [UNCATEGORIZED]: { items: waiting, page: 0, size: 50, total: 1 },
  '/api/review-queue': fixtures.reviewQueue,
}

/** A run with Corgi on, hand-written (the capture runs with AI off): the counts as the backend adds them up. */
const aiRun: CategorizationRun = {
  ...fixtures.categorizationRunOff,
  ai: { ...fixtures.categorizationRunOff.ai, status: 'DONE', itemsRemaining: 0 },
  searchIndex: { ...fixtures.categorizationRunOff.searchIndex, status: 'DONE', embedded: 140 },
  summary: { categorized: 97, toReview: 34, heldBack: 1, modelCalls: 4, merchantsIndexed: 126 },
}

test('the summary in plain words, each count as returned', () => {
  expect(runSummaryText(aiRun)).toBe('97 categorized, 34 to review, 1 held back for privacy, 4 model calls')
  expect(indexedText(aiRun)).toBe('126 merchants indexed for search')
  expect(stoppedText(aiRun)).toBeNull()

  const off = fixtures.categorizationRunOff
  expect(runSummaryText(off)).toBe(`0 categorized, ${off.summary.toReview} to review, rules only (Corgi is off)`)
  expect(indexedText(off)).toBeNull()

  const one = { ...aiRun, summary: { ...aiRun.summary, modelCalls: 1, merchantsIndexed: 1, heldBack: 0 } }
  expect(runSummaryText(one)).toBe('97 categorized, 34 to review, 1 model call')
  expect(indexedText(one)).toBe('1 merchant indexed for search')
  expect(stoppedText({ ...aiRun, ai: { ...aiRun.ai, status: 'CAP_REACHED' } })).toMatch(/limit for one run/)
  expect(stoppedText({ ...aiRun, ai: { ...aiRun.ai, status: 'STOPPED_ON_ERROR' } })).toMatch(/stopped after a failed call/)
})

test('Needs a category: Categorize now runs the endpoint, shows progress, then the summary, and every page reads again', async () => {
  const user = userEvent.setup()
  let answer: (r: Response) => void = () => {}
  const { writes, requests } = renderApp('/transactions?view=review', ROUTES, (w) =>
    w.url === '/api/categorization/run' ? new Promise<Response>((resolve) => (answer = resolve)) : undefined,
  )

  const section = await screen.findByRole('region', { name: 'Needs a category' })
  await user.click(await within(section).findByRole('button', { name: 'Categorize now' }))

  expect(await screen.findByRole('progressbar', { name: 'Categorizing' })).toBeInTheDocument()
  expect(within(section).getByRole('button', { name: 'Categorize now' })).toBeDisabled()
  expect(writes).toEqual([{ method: 'POST', url: '/api/categorization/run', body: undefined }])
  const before = { list: requests.filter((r) => r === UNCATEGORIZED).length, accounts: requests.filter((r) => r === '/api/accounts').length }

  answer(Response.json(aiRun))
  const summary = await within(section).findByRole('status', { name: /97 categorized/ })
  expect(summary).toHaveTextContent('97 categorized, 34 to review, 1 held back for privacy, 4 model calls')
  expect(summary).toHaveTextContent('126 merchants indexed for search')
  expect(screen.queryByRole('progressbar', { name: 'Categorizing' })).not.toBeInTheDocument()
  await vi.waitFor(() => {
    expect(requests.filter((r) => r === UNCATEGORIZED).length).toBeGreaterThan(before.list)
    expect(requests.filter((r) => r === '/api/accounts').length).toBeGreaterThan(before.accounts)
  })
  expect(screen.getByRole('status', { name: /97 categorized/ })).toBeInTheDocument() // kept across the reload
})

test('“held back” is explained as the redaction block, on hover and on tap', async () => {
  const user = userEvent.setup()
  renderApp('/transactions?view=review', ROUTES, (w) =>
    w.url === '/api/categorization/run' ? Response.json(aiRun) : undefined,
  )

  await user.click(await screen.findByRole('button', { name: 'Categorize now' }))
  const heldBack = await screen.findByRole('button', { name: '1 held back for privacy' })
  expect(heldBack).toHaveAttribute('title', expect.stringMatching(/account numbers, names and other identifiers were removed/))
  expect(heldBack).toHaveAccessibleDescription(/privacy check refused it/)
  expect(heldBack).toHaveAttribute('aria-expanded', 'false')
  await user.click(heldBack)
  expect(heldBack).toHaveAttribute('aria-expanded', 'true')
  expect(screen.getByText(/privacy check refused it/)).toHaveClass('held-back-text')
})

test('with AI off the run says rules only, and a failed run is said', async () => {
  const user = userEvent.setup()
  let fail = false
  renderApp('/transactions?view=review', ROUTES, (w) =>
    w.url === '/api/categorization/run'
      ? fail
        ? Response.json({ type: 'x', title: 'Busy', status: 409, detail: 'A run is in progress.' }, { status: 409 })
        : Response.json(fixtures.categorizationRunOff)
      : undefined,
  )

  await user.click(await screen.findByRole('button', { name: 'Categorize now' }))
  expect(await screen.findByRole('status', { name: /rules only \(Corgi is off\)/ })).not.toHaveTextContent('indexed')
  fail = true
  await user.click(screen.getByRole('button', { name: 'Categorize now' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Categorizing failed: A run is in progress.')
})

test('not offered when nothing is uncategorized', async () => {
  renderApp('/transactions?view=review', {
    ...ROUTES,
    [UNCATEGORIZED]: { items: [], page: 0, size: 50, total: 0 },
  })

  expect(await screen.findByText('Every transaction has a category.')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Categorize now' })).not.toBeInTheDocument()
})

test('the Import result offers it when the uncategorized count is above zero', async () => {
  const user = userEvent.setup()
  const { requests, writes } = renderApp(
    '/import',
    {
      '/api/review-queue': fixtures.reviewQueue,
      '/api/transactions?uncategorized=true&size=1': { items: waiting, page: 0, size: 1, total: 5 },
    },
    (w) =>
      w.url === '/api/accounts/1/imports'
        ? Response.json(fixtures.importSummary, { status: 201 })
        : w.url === '/api/categorization/run'
          ? Response.json(aiRun)
          : undefined,
  )
  await user.selectOptions(await screen.findByLabelText('Account'), '1')
  await user.upload(screen.getByLabelText('Choose a file'), new File(['x'], 'a.csv', { type: 'text/csv' }))
  await user.click(screen.getByRole('button', { name: 'Import' }))

  const result = await screen.findByRole('region', { name: 'Imported a.csv' })
  await user.click(await within(result).findByRole('button', { name: 'Categorize now' }))
  expect(requests).toContain('/api/transactions?uncategorized=true&size=1')
  expect(await within(result).findByText('126 merchants indexed for search')).toBeInTheDocument()
  expect(writes.map((w) => w.url)).toEqual(['/api/accounts/1/imports', '/api/categorization/run'])
})
