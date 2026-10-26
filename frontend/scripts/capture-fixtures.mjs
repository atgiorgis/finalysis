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

import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const BASE = process.env.FINALYSIS_URL ?? 'http://localhost:8080'
const OUT = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'test', 'fixtures')
const FIXED_TIME = '2026-10-23T12:00:00Z'
const VOLATILE = new Set(['timestamp', 'createdAt', 'updatedAt', 'since', 'reviewedAt', 'dismissedAt', 'categorizedAt', 'importedAt', 'resolvedAt',
  'confirmedAt'])

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
const dashboard = await get(`/api/dashboard?month=${completeMonth}`)
save('dashboard-complete-month', dashboard)

// The details panel (step 3): the second slice (more rows than the first), Other's first member, and the income line,
// each with the first page of the drill-down it names, as the panel asks for it.
async function panel(name, query) {
  const details = await get(`/api/dashboard/details?month=${completeMonth}&${query}`)
  save(`details-${name}`, details)
  const params = new URLSearchParams({ ...details.drillDown.params, page: '0', size: '50' })
  save(`drilldown-${name}`, await get(`${details.drillDown.path}?${params}`))
}
const slices = dashboard.spendingSlices.rows
await panel('category', `categoryId=${slices[1].category.id}`)
const other = slices.find((s) => s.kind === 'OTHER')
if (other) {
  await panel('other-member', `categoryId=${other.members[0].category.id}`)
}
await panel('income', 'line=INCOME')

// ---------------------------------------------------------------------------- the Import page (step 7d)
//
// First the statements as the page lists them (one read per account), then an import pass on new synthetic accounts
// ("Fixture Bank"), which writes: every result shape the page shows (201 with warnings, 202 unparsed, 409 already
// imported, 422 mapping required and mismatch), the CSV preview and the mapping it saves, every review-queue kind,
// one account's statements, a statement's rows and a delete. It runs last, so the fixtures above are unchanged.

const SAMPLE = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'sample-data')

for (const account of await get('/api/accounts')) {
  save(`statements-account-${account.id}`, await get(`/api/accounts/${account.id}/statements`))
}

/** A write's status and JSON body (null for 204); never throws on a 4xx, which several captures want. */
async function write(method, path, body) {
  const init = { method }
  if (body instanceof FormData) {
    init.body = body
  } else if (body !== undefined) {
    init.headers = { 'Content-Type': 'application/json' }
    init.body = JSON.stringify(body)
  }
  const res = await fetch(BASE + path, init)
  return { status: res.status, body: res.status === 204 ? null : await res.json() }
}

function expectStatus(result, status, what) {
  if (result.status !== status) {
    throw new Error(`${what}: expected ${status}, got ${result.status} ${JSON.stringify(result.body)}`)
  }
  return result.body
}

/** The multipart body the page sends: one part named `file`. */
function fileForm(relativePath) {
  const form = new FormData()
  const name = relativePath.split('/').at(-1)
  form.append('file', new Blob([readFileSync(join(SAMPLE, relativePath))]), name)
  return form
}

const importInto = (accountId, file) => write('POST', `/api/accounts/${accountId}/imports`, fileForm(file))

async function fixtureAccount(name, type, lastFour) {
  const created = await write('POST', '/api/accounts', { name, institution: 'Fixture Bank', type, lastFour })
  return expectStatus(created, 201, `create ${name}`)
}

/** Preview, save the suggested mapping (with `edits`), import: the page's "Use this mapping" path. */
async function importCsv(accountId, file, edits = {}) {
  const preview = expectStatus(
    await write('POST', `/api/accounts/${accountId}/imports/preview`, fileForm(file)),
    200,
    `preview ${file}`,
  )
  expectStatus(
    await write('PUT', `/api/accounts/${accountId}/csv-mapping`, { ...preview.suggestedMapping.mapping, ...edits }),
    201,
    `mapping for ${file}`,
  )
  return expectStatus(await importInto(accountId, file), 201, `import ${file}`)
}

const checking = await fixtureAccount('Fixture Checking', 'CHECKING', '5101')
save('account-created', checking)
const TRANSFERS = 'edge-cases/transfers'

// A CSV without a mapping: 422 mapping-required; the preview the page then reads; the mapping saved; the import.
save('import-mapping-required', expectStatus(
  await importInto(checking.id, `${TRANSFERS}/transfers-northfield-checking_2026-05.csv`),
  422,
  'mapping required',
))
const checkingPreview = expectStatus(
  await write('POST', `/api/accounts/${checking.id}/imports/preview`,
    fileForm(`${TRANSFERS}/transfers-northfield-checking_2026-05.csv`)),
  200,
  'checking preview',
)
save('import-preview-csv', checkingPreview)
save('csv-mapping-saved', expectStatus(
  await write('PUT', `/api/accounts/${checking.id}/csv-mapping`, checkingPreview.suggestedMapping.mapping),
  201,
  'checking mapping',
))
// The savings and card files first, so the checking file's out-row meets both possible ins at once.
const savings = await fixtureAccount('Fixture Savings', 'SAVINGS', '5102')
await importCsv(savings.id, `${TRANSFERS}/transfers-northfield-savings_2026-05.csv`)
const card = await fixtureAccount('Fixture Card', 'CREDIT_CARD', '5103')
// More payments than purchases make the suggester guess INVERTED; the file prints money out as negative.
await importCsv(card.id, `${TRANSFERS}/transfers-summit-card_2026-05.csv`, { sign: 'AS_IS' })
const checkingImport = expectStatus(
  await importInto(checking.id, `${TRANSFERS}/transfers-northfield-checking_2026-05.csv`),
  201,
  'checking import',
)
save('import-summary', checkingImport)
save('import-already-imported', expectStatus(
  await importInto(checking.id, `${TRANSFERS}/transfers-northfield-checking_2026-05.csv`),
  409,
  'already imported',
))
// Another layout into the same account: 422 mapping-mismatch, with the file's headers and no rows.
save('import-mapping-mismatch', expectStatus(
  await importInto(checking.id, 'edge-cases/csv-card-refund.csv'),
  422,
  'mapping mismatch',
))
save('import-unparsed', expectStatus(
  await importInto(checking.id, 'pdf/edge-cases/pdf-unrecognized-layout.pdf'),
  202,
  'unparsed upload',
))
// A scan (no text layer): held the same way, with its own reason.
save('import-unparsed-scan', expectStatus(
  await importInto(checking.id, 'pdf/edge-cases/pdf-no-text.pdf'),
  202,
  'unparsed scan',
))

// A PDF the layout parser recognizes but can't read yet (Phase 3b paused): 422 parse-error.
const ledger = await fixtureAccount('Fixture Ledger Checking', 'CHECKING', '5105')
save('import-parse-error', expectStatus(
  await importInto(ledger.id, 'pdf/edge-cases/pdf-section-total-mismatch.pdf'),
  422,
  'parse error',
))
// Balances sent with the file that don't add up: 201, NEEDS_REVIEW, "reconciliation failed" warnings.
const ledgerFile = 'edge-cases/csv-windows-1252.csv'
const ledgerPreview = expectStatus(
  await write('POST', `/api/accounts/${ledger.id}/imports/preview`, fileForm(ledgerFile)), 200, 'ledger preview')
expectStatus(await write('PUT', `/api/accounts/${ledger.id}/csv-mapping`, ledgerPreview.suggestedMapping.mapping), 201,
  'ledger mapping')
save('import-needs-review', expectStatus(
  await write('POST', `/api/accounts/${ledger.id}/imports?openingBalance=0.00&closingBalance=0.01`, fileForm(ledgerFile)),
  201,
  'needs review',
))

// One out, two possible ins: a transfer candidate group.
save('review-queue-candidates', await get('/api/review-queue'))

// Deleting the card statement dissolves the group (detection links checking and savings); importing the card file
// again brings its in-row back as a competitor of that automatic link.
const [cardStatement] = await get(`/api/accounts/${card.id}/statements`)
save('statement-deleted', expectStatus(await write('DELETE', `/api/statements/${cardStatement.id}`), 200, 'delete'))
expectStatus(await importInto(card.id, `${TRANSFERS}/transfers-summit-card_2026-05.csv`), 201, 'card again')
const competingQueue = await get('/api/review-queue')
save('review-queue-competing', competingQueue)
const competing = competingQueue.items.find((item) => item.kind === 'COMPETING_CANDIDATE')
if (!competing) {
  throw new Error('no competing candidate after importing the card file again')
}

// Unlinking it (the pair is rejected) and marking the checking out-row and the card's in-row one-sided: a possible
// match between the two one-sided links.
expectStatus(await write('DELETE', `/api/transfers/${competing.linkId}`), 204, 'unlink')
const cardIn = competing.competitors.find((txn) => txn.accountId === card.id)
expectStatus(await write('POST', '/api/transfers', { outTxnId: competing.out.id }), 201, 'one-sided out')
expectStatus(await write('POST', '/api/transfers', { inTxnId: cardIn.id }), 201, 'one-sided in')
save('review-queue-possible-match', await get('/api/review-queue'))

save('statements-fixture-checking', await get(`/api/accounts/${checking.id}/statements`))
save('statement-detail', await get(`/api/statements/${checkingImport.statementId}`))
save('transactions-statement', await get(`/api/transactions?statementId=${checkingImport.statementId}&size=50`))
