import { expect, test } from 'vitest'
import { getJson, reads, withParams } from '../api'
import { fetchFrom, fixtures } from './fixtures'
import { vi } from 'vitest'

test('the fixtures are the sample data with AI off', () => {
  expect(fixtures.statusOff.ai).toEqual({ mode: 'OFF' })
  expect(fixtures.accounts.length).toBeGreaterThan(0)
  expect(fixtures.months.months.length).toBeGreaterThan(0)
  expect(fixtures.coverage.latestMonth).toMatch(/^\d{4}-\d{2}$/)
})

test('the drill-down fixture is the rows behind its category cell', () => {
  const row = fixtures.categoriesCompleteMonth.rows.find(
    (r) => r.category?.id === fixtures.trendsTopCategory.categoryId && r.bucket === 'EXPENSE',
  )
  // Compared as the API's strings: the UI never adds them up itself.
  expect(fixtures.drillDownTopCategory.total.net).toBe(row?.total.net)
  expect(fixtures.drillDownTopCategory.total.count).toBe(row?.total.count)
})

test('withParams keeps the params that have a value, in order', () => {
  expect(withParams('/api/x')).toBe('/api/x')
  expect(withParams('/api/x', { month: '2026-10', categoryId: 4, empty: '', none: null, gone: undefined, flag: false }))
    .toBe('/api/x?month=2026-10&categoryId=4&flag=false')
})

test('reads answer from the fetch stub, and an unknown read is a problem', async () => {
  vi.stubGlobal('fetch', fetchFrom({ '/api/analytics/coverage': fixtures.coverage }))

  await expect(reads.coverage()).resolves.toEqual(fixtures.coverage)
  await expect(getJson('/api/analytics/months')).rejects.toMatchObject({ status: 404 })

  vi.unstubAllGlobals()
})

test('the import-pass fixtures agree with each other (step 7d)', () => {
  // The statement imported, its detail and the rows `statementId` lists are the same statement.
  expect(fixtures.statementDetail.id).toBe(fixtures.importSummary.statementId)
  expect(fixtures.transactionsStatement.items.every((t) => t.statementId === fixtures.statementDetail.id)).toBe(true)
  expect(fixtures.transactionsStatement.total).toBe(fixtures.statementDetail.transactionCount)
  expect(fixtures.statementsFixtureChecking.map((s) => s.id)).toContain(fixtures.statementDetail.id)
  // The second import of the same file names that statement.
  expect(fixtures.importAlreadyImported.statementId).toBe(fixtures.importSummary.statementId)
  // Between them, the three queues hold every kind the Import page resolves.
  const kinds = new Set(
    [fixtures.reviewQueueCandidates, fixtures.reviewQueueCompeting, fixtures.reviewQueuePossibleMatch].flatMap((q) =>
      q.items.map((i) => i.kind),
    ),
  )
  for (const kind of ['STATEMENT', 'UNPARSED_UPLOAD', 'TRANSFER_CANDIDATES', 'COMPETING_CANDIDATE', 'POSSIBLE_MATCH']) {
    expect(kinds).toContain(kind)
  }
  // A mismatch carries the file's headers and no rows (SEC-10); the preview endpoint shows rows.
  expect(fixtures.importMappingMismatch.preview?.headers?.length).toBeGreaterThan(0)
  expect(fixtures.importMappingMismatch.preview?.rows).toBeUndefined()
  expect(fixtures.importPreviewCsv.rows?.length).toBeGreaterThan(0)
})
