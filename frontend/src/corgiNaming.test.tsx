import '@testing-library/jest-dom/vitest'
import { cleanup, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, test, vi } from 'vitest'
import type { CategorizationRun, Transaction } from './api'
import { AI_NAME } from './corgi'
import { fixtures } from './test/fixtures'
import { renderApp } from './test/renderApp'

/**
 * Corgi is the categorizer's name everywhere the UI shows who set a category (Phase 7 step 7e): the transaction
 * sheet's "set by", the review queue's reasons and their tooltips, Settings and the run summary. None of them says
 * "AI" or "the model" for it; "rule" and "you" stay. The cloud banner and chat's own messages are about the service,
 * not the categorizer, and keep their SEC-01 wording.
 */

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

const NAMES_THE_CATEGORIZER_OTHERWISE = /\bAI\b|\bthe model\b|claude|haiku|sonnet|bedrock/i

const aiCategorized: Transaction = {
  ...fixtures.transactionDetail,
  categorySource: 'AI',
  categoryModel: 'us.anthropic.claude-haiku-4-5-20251001-v1:0',
} as Transaction
const UNCATEGORIZED = '/api/transactions?uncategorized=true&page=0&size=50'
const waiting: Transaction[] = [
  { ...fixtures.transactionDetail, id: 9101, category: null, categorySource: null, aiReviewReason: 'LOW_CONFIDENCE' },
  { ...fixtures.transactionDetail, id: 9102, category: null, categorySource: null, aiReviewReason: 'INVALID_ANSWER' },
]

/** Every visible string in `el`, and its tooltips. */
function shown(el: HTMLElement): string {
  const titles = [...el.querySelectorAll('[title]')].map((t) => t.getAttribute('title'))
  return [el.textContent, ...titles].join(' ')
}

test('the sheet says a category was suggested by Corgi, never by the AI or a model name', async () => {
  renderApp(`/transactions?month=2026-09&txn=${aiCategorized.id}`, {
    [`/api/transactions/${aiCategorized.id}`]: aiCategorized,
  })

  const sheet = await screen.findByRole('dialog')
  await within(sheet).findByText(/suggested by Corgi/)
  expect(shown(sheet)).not.toMatch(NAMES_THE_CATEGORIZER_OTHERWISE)
})

test('rule and you stay as they are', async () => {
  for (const [source, text] of [['RULE', 'set by a rule'], ['USER', 'set by you']] as const) {
    renderApp(`/transactions?month=2026-09&txn=${aiCategorized.id}`, {
      [`/api/transactions/${aiCategorized.id}`]: { ...aiCategorized, categorySource: source },
    })
    expect(await within(await screen.findByRole('dialog')).findByText(new RegExp(text))).toBeInTheDocument()
    cleanup()
  }
})

test('the review queue’s reasons name Corgi', async () => {
  renderApp('/transactions?view=review', {
    [UNCATEGORIZED]: { items: waiting, page: 0, size: 50, total: 2 },
    '/api/review-queue': fixtures.reviewQueue,
  })

  const section = await screen.findByRole('region', { name: 'Needs a category' })
  await within(section).findByText(/Corgi wasn’t sure/)
  expect(section).toHaveTextContent('Corgi’s answers were invalid')
  expect(shown(section)).not.toMatch(NAMES_THE_CATEGORIZER_OTHERWISE)
})

test('Settings names Corgi, with the model ids only as facts beside its roles', async () => {
  renderApp('/settings', {
    '/api/status': {
      ...fixtures.statusOff,
      ai: { mode: 'CLOUD', provider: 'bedrock', models: { categorization: 'cat-model', embedding: 'emb', main: 'chat' } },
    },
  })

  const section = await screen.findByRole('region', { name: AI_NAME })
  for (const term of within(section).getAllByRole('term')) {
    expect(term.textContent).not.toMatch(NAMES_THE_CATEGORIZER_OTHERWISE)
  }
  expect(within(section).getByText('Corgi · categorization')).toBeInTheDocument()
})

test('the run summary and its tooltip name no AI', async () => {
  const user = userEvent.setup()
  const run: CategorizationRun = {
    ...fixtures.categorizationRunOff,
    ai: { ...fixtures.categorizationRunOff.ai, status: 'DONE' },
    summary: { categorized: 3, toReview: 1, heldBack: 1, modelCalls: 1, merchantsIndexed: 2 },
  }
  renderApp(
    '/transactions?view=review',
    { [UNCATEGORIZED]: { items: waiting, page: 0, size: 50, total: 2 }, '/api/review-queue': fixtures.reviewQueue },
    (w) => (w.url === '/api/categorization/run' ? Response.json(run) : undefined),
  )

  const section = await screen.findByRole('region', { name: 'Needs a category' })
  const button = await within(section).findByRole('button', { name: 'Categorize now' })
  expect(shown(button.parentElement as HTMLElement)).not.toMatch(NAMES_THE_CATEGORIZER_OTHERWISE)
  await user.click(button)
  const summary = await within(section).findByRole('status', { name: /3 categorized/ })
  expect(shown(summary)).not.toMatch(NAMES_THE_CATEGORIZER_OTHERWISE)
})
