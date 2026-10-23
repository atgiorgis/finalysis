import '@testing-library/jest-dom/vitest'
import { cleanup, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, test, vi } from 'vitest'
import type { CorrectionResult, Transaction } from '../api'
import { fixtures } from '../test/fixtures'
import { renderApp, type Write } from '../test/renderApp'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

const TXN = fixtures.transactionDetail
const DINING = fixtures.categories.find((c) => c.name === 'Dining')!
const TRANSFER = fixtures.categories.find((c) => c.name === 'Transfer')!
const SEPTEMBER = '/api/transactions?from=2026-09-01&to=2026-09-30&page=0&size=50'

/** A card payment on a transfer link, from the captured txn. */
const PAYMENT: Transaction = {
  ...TXN,
  id: 236,
  merchant: 'ONLINE PAYMENT THANK YOU',
  amount: '467.76',
  category: { id: TRANSFER.id, name: 'Transfer', kind: 'TRANSFER', parentId: null },
  isTransfer: true,
}

const ROUTES: Record<string, unknown> = {
  [SEPTEMBER]: fixtures.transactionsPage,
  [`/api/transactions/${TXN.id}`]: TXN,
  [`/api/transactions/${PAYMENT.id}`]: PAYMENT,
}

const asDining: Transaction = {
  ...TXN,
  category: { id: DINING.id, name: 'Dining', kind: 'EXPENSE', parentId: null },
  categorySource: 'USER',
}

function corrected(overrides: Partial<CorrectionResult> = {}): CorrectionResult {
  return {
    txn: asDining,
    ruleId: 77,
    ruleAction: 'CREATED',
    otherTxnsUpdated: 3,
    otherTxnsKeptUser: 1,
    otherTxnsKeptOtherRule: 0,
    ...overrides,
  }
}

async function sheet(name = 'TRADER JOE S') {
  const dialog = await screen.findByRole('dialog', { name })
  return within(dialog)
}

test('a new category for the merchant: the request, what it changed in words, and the pages read again', async () => {
  const user = userEvent.setup()
  const { writes, requests } = renderApp(`/transactions?month=2026-09&txn=${TXN.id}`, ROUTES, (w: Write) =>
    w.method === 'PUT' ? Response.json(corrected()) : undefined,
  )
  const s = await sheet()
  const listReads = () => requests.filter((r) => r === SEPTEMBER).length
  const before = listReads()

  await user.selectOptions(s.getByRole('combobox', { name: 'Category' }), 'Dining')
  await user.click(s.getByRole('button', { name: 'Save category' }))

  expect(writes).toEqual([
    { method: 'PUT', url: `/api/transactions/${TXN.id}/category`, body: { categoryId: DINING.id, applyToMerchant: true } },
  ])
  expect(await s.findByRole('status')).toHaveTextContent(
    'Saved as Dining. 3 other TRADER JOE S transactions changed too, and future ones will follow. ' +
      '1 other kept the category you set.',
  )
  expect(s.getByText('Dining (set by you)')).toBeInTheDocument()
  expect(listReads()).toBeGreaterThan(before)
})

test('for this transaction only: applyToMerchant false, and the answer says so', async () => {
  const user = userEvent.setup()
  const { writes } = renderApp(`/transactions?month=2026-09&txn=${TXN.id}`, ROUTES, (w) =>
    w.method === 'PUT'
      ? Response.json(corrected({ ruleId: null, ruleAction: 'NONE', otherTxnsUpdated: 0, otherTxnsKeptUser: 0 }))
      : undefined,
  )
  const s = await sheet()

  await user.click(s.getByRole('checkbox', { name: 'Also for TRADER JOE S’s other transactions' }))
  await user.selectOptions(s.getByRole('combobox', { name: 'Category' }), 'Dining')
  await user.click(s.getByRole('button', { name: 'Save category' }))

  expect(writes[0].body).toEqual({ categoryId: DINING.id, applyToMerchant: false })
  expect(await s.findByRole('status')).toHaveTextContent('Saved as Dining, for this transaction only.')
})

test('Save waits for a different category', async () => {
  renderApp(`/transactions?month=2026-09&txn=${TXN.id}`, ROUTES)

  const s = await sheet()
  expect(s.getByRole('button', { name: 'Save category' })).toBeDisabled()
})

test('undo my category: only for a category you set; the full undo, and what the rules gave it', async () => {
  const user = userEvent.setup()
  const { writes } = renderApp(`/transactions?month=2026-09&txn=${TXN.id}`, {
    ...ROUTES,
    [`/api/transactions/${TXN.id}`]: asDining,
  }, (w) =>
    w.method === 'DELETE'
      ? Response.json({ txn: { ...TXN, categorySource: 'RULE' }, ruleId: 77, ruleAction: 'REMOVED', otherTxnsUpdated: 3 })
      : undefined,
  )
  const s = await sheet()

  await user.click(s.getByRole('button', { name: 'Undo my category' }))

  expect(writes).toEqual([
    { method: 'DELETE', url: `/api/transactions/${TXN.id}/category?removeRule=true`, body: undefined },
  ])
  expect(await s.findByRole('status')).toHaveTextContent(
    'Your category was removed and the rules ran again: now Groceries. Its merchant rule was removed, and 3 other ' +
      'transactions changed back.',
  )
  expect(s.queryByRole('button', { name: 'Undo my category' })).not.toBeInTheDocument()
})

test('a rule’s category has no undo; an ordinary purchase has no transfer action', async () => {
  renderApp(`/transactions?month=2026-09&txn=${TXN.id}`, ROUTES)

  const s = await sheet()
  expect(s.queryByRole('button', { name: 'Undo my category' })).not.toBeInTheDocument()
  expect(s.queryByRole('button', { name: 'Not a transfer' })).not.toBeInTheDocument()
})

test('not a transfer: the request, its link removed, and the transaction read again', async () => {
  const user = userEvent.setup()
  let excluded = false
  const { writes, requests } = renderApp(`/transactions?month=2026-09&txn=${PAYMENT.id}`, ROUTES, (w) => {
    if (w.method === 'POST') {
      excluded = true
      return Response.json({ txnId: PAYMENT.id, transferExcluded: true, removedLinkId: 41 })
    }
    return undefined
  })
  const s = await sheet('ONLINE PAYMENT THANK YOU')

  await user.click(s.getByRole('button', { name: 'Not a transfer' }))

  expect(excluded).toBe(true)
  expect(writes[0]).toEqual({ method: 'POST', url: `/api/transactions/${PAYMENT.id}/not-transfer`, body: undefined })
  expect(await s.findByRole('status')).toHaveTextContent(
    'Marked not a transfer, and its transfer link was removed. It now counts as spending or income, and needs a ' +
      'category if it has none.',
  )
  expect(requests.filter((r) => r === `/api/transactions/${PAYMENT.id}`).length).toBeGreaterThanOrEqual(2)
})

test('undo “not a transfer”: a 204, said in words', async () => {
  const user = userEvent.setup()
  const { writes } = renderApp(`/transactions?month=2026-09&txn=${PAYMENT.id}`, {
    ...ROUTES,
    [`/api/transactions/${PAYMENT.id}`]: { ...PAYMENT, isTransfer: false, transferExcluded: true },
  }, (w) => (w.method === 'DELETE' ? new Response(null, { status: 204 }) : undefined))
  const s = await sheet('ONLINE PAYMENT THANK YOU')

  await user.click(s.getByRole('button', { name: 'Undo “not a transfer”' }))

  expect(writes[0]).toEqual({ method: 'DELETE', url: `/api/transactions/${PAYMENT.id}/not-transfer`, body: undefined })
  expect(await s.findByRole('status')).toHaveTextContent(
    'It can be matched as a transfer again at the next detection run.',
  )
})

test('a refused correction shows the API’s reason, and the form can be used again', async () => {
  const user = userEvent.setup()
  renderApp(`/transactions?month=2026-09&txn=${TXN.id}`, ROUTES, (w) =>
    w.method === 'PUT'
      ? Response.json(
          { type: 'urn:finalysis:problem:rule-exists', detail: 'A rule of yours already covers this merchant.' },
          { status: 409 },
        )
      : undefined,
  )
  const s = await sheet()

  await user.selectOptions(s.getByRole('combobox', { name: 'Category' }), 'Dining')
  await user.click(s.getByRole('button', { name: 'Save category' }))

  expect(await s.findByRole('alert')).toHaveTextContent('A rule of yours already covers this merchant.')
  expect(s.getByRole('button', { name: 'Save category' })).toBeEnabled()
  expect(s.getByText('Groceries (set by a rule)')).toBeInTheDocument()
})
