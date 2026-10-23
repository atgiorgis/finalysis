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
