#!/usr/bin/env node
// Saves the reads the UI uses as JSON test fixtures (docs/plans/phase-7.md §6.1).
//
// Run it against a backend holding the sample data only, with AI off: a fresh database, then the newman folders
// listed in the README. Never against a database with real statements: the fixtures are committed.
//
//   FINALYSIS_URL=http://localhost:8080 npm run capture-fixtures
//
// Timestamps that differ between runs are replaced by a fixed one, so capturing twice gives the same files and the
// close-out can check that the fixtures still match the API.

import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const BASE = process.env.FINALYSIS_URL ?? 'http://localhost:8080'
const OUT = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'test', 'fixtures')
const FIXED_TIME = '2026-10-23T12:00:00Z'
const VOLATILE = new Set(['timestamp', 'createdAt', 'updatedAt', 'since', 'reviewedAt', 'dismissedAt', 'categorizedAt'])

async function get(path) {
  const res = await fetch(BASE + path)
  if (!res.ok) {
    throw new Error(`GET ${path}: ${res.status} ${await res.text()}`)
  }
  return res.json()
}

function normalize(value) {
  if (Array.isArray(value)) {
    return value.map(normalize)
  }
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value).map(([key, v]) => [key, VOLATILE.has(key) && typeof v === 'string' ? FIXED_TIME : normalize(v)]),
    )
  }
  return value
}

function save(name, body) {
  writeFileSync(join(OUT, `${name}.json`), JSON.stringify(normalize(body), null, 2) + '\n')
  console.log(`saved ${name}.json`)
}

const status = await get('/api/status')
if (status.ai?.mode !== 'OFF') {
  throw new Error(`AI must be off while capturing fixtures (mode ${status.ai?.mode})`)
}
mkdirSync(OUT, { recursive: true })
save('status-off', status)

const coverage = await get('/api/analytics/coverage')
save('coverage', coverage)
const month = coverage.latestMonth
const completeMonth = coverage.latestCompleteMonth
if (!month || !completeMonth) {
  throw new Error('no data: import the sample data first')
}

save('accounts', await get('/api/accounts'))
save('categories', await get('/api/categories'))
save('analytics-months', await get('/api/analytics/months'))
save('analytics-categories', await get('/api/analytics/categories'))

const monthCategories = await get(`/api/analytics/categories?from=${completeMonth}&to=${completeMonth}`)
save('analytics-categories-complete-month', monthCategories)
save('analytics-merchants-complete-month', await get(`/api/analytics/merchants?from=${completeMonth}&to=${completeMonth}`))
save('analytics-trends-complete-month', await get(`/api/analytics/trends?month=${completeMonth}`))

// The largest expense category of the latest complete month: its trend and the drill-down behind its total.
const top = monthCategories.rows
  .filter((row) => row.bucket === 'EXPENSE' && row.category)
  // A dev tool, not a screen: comparing amounts as numbers here only picks which category to capture.
  .sort((a, b) => Number(a.total.net) - Number(b.total.net) || a.category.id - b.category.id)
  .at(0)
if (!top) {
  throw new Error(`no expense category in ${completeMonth}`)
}
const categoryId = top.category.id
save('analytics-trends-top-category', await get(`/api/analytics/trends?month=${completeMonth}&categoryId=${categoryId}`))
save(
  'analytics-drilldown-top-category',
  await get(`/api/analytics/transactions?month=${completeMonth}&categoryId=${categoryId}&size=50`),
)

save('analytics-recurring-all', await get('/api/analytics/recurring?status=ALL'))
const firstMonth = coverage.accounts
  .map((account) => account.firstDay?.slice(0, 7))
  .filter(Boolean)
  .sort()
  .at(0) ?? month
save('alerts-all', await get(`/api/alerts?from=${firstMonth}&to=${month}&includeDismissed=true`))

const transactions = await get('/api/transactions?size=25')
save('transactions-page', transactions)
save('transactions-uncategorized', await get('/api/transactions?uncategorized=true&size=25'))
if (transactions.items.length > 0) {
  save('transaction-detail', await get(`/api/transactions/${transactions.items[0].id}`))
}
save('review-queue', await get('/api/review-queue'))
save('chat-conversations', await get('/api/chat/conversations'))

// The Overview (step 2): the latest month (partial on the sample data) and the latest complete month.
save('dashboard-latest', await get(`/api/dashboard?month=${month}`))
save('dashboard-complete-month', await get(`/api/dashboard?month=${completeMonth}`))
