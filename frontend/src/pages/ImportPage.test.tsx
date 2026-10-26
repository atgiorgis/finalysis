import '@testing-library/jest-dom/vitest'
import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, test, vi } from 'vitest'
import { PRIVACY_LINE } from '../components/import/importText'
import { fixtures } from '../test/fixtures'
import { renderApp, type Write } from '../test/renderApp'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

const MINUS = '−'

/** Every sample account's statements, and the queue with a candidate group, statements to review and uploads. */
const ROUTES: Record<string, unknown> = {
  ...Object.fromEntries(
    Object.entries(fixtures.statementsByAccount).map(([id, list]) => [`/api/accounts/${id}/statements`, list]),
  ),
  '/api/review-queue': fixtures.reviewQueueCandidates,
}

const json = (body: unknown, status = 200) => Response.json(body, { status })
const problem = (body: unknown) => json(body, (body as { status: number }).status)

/** A small synthetic file, as the browser hands it over. */
function csvFile(name = 'transfers-northfield-checking_2026-05.csv') {
  return new File(['Date,Description,Debit,Credit,Balance\n'], name, { type: 'text/csv' })
}

/** Picks Northfield Bank Checking (id 1) and a file, ready to import. */
async function chooseAccountAndFile(user: ReturnType<typeof userEvent.setup>, file = csvFile()) {
  await user.selectOptions(await screen.findByLabelText('Account'), '1')
  await user.upload(screen.getByLabelText('Choose a file'), file)
}

// ---------------------------------------------------------------------------- account picker

test('the picker lists every account with its last four, closed ones marked, and the choice goes in the URL', async () => {
  const user = userEvent.setup()
  const { location } = renderApp('/import', ROUTES)

  const picker = await screen.findByLabelText('Account')
  const options = within(picker).getAllByRole('option').map((o) => o.textContent)
  expect(options).toEqual([
    'Choose an account',
    ...fixtures.accounts.map((a) => `${a.name} ···${a.lastFour}${a.closedOn ? ' (closed)' : ''}`),
  ])
  expect(options).toContain('Northfield Bank Checking ···1001')
  expect(options).toContain('Northfield Checking (imported twice) ···1001 (closed)')

  await user.selectOptions(picker, '3')
  expect(location()).toBe('/import?account=3')
  expect(screen.getByRole('button', { name: 'Import' })).toBeDisabled()
})

test('add an account: the request body is name, institution, type and last four; the new account is chosen', async () => {
  const user = userEvent.setup()
  const { writes, requests, location } = renderApp('/import', ROUTES, (w) =>
    w.url === '/api/accounts' ? json(fixtures.accountCreated, 201) : undefined,
  )

  await user.click(await screen.findByRole('button', { name: 'Add an account' }))
  const form = screen.getByRole('form', { name: 'Add an account' })
  await user.type(within(form).getByLabelText('Name'), 'Fixture Checking')
  await user.type(within(form).getByLabelText('Institution'), 'Fixture Bank')
  await user.selectOptions(within(form).getByLabelText('Type'), 'SAVINGS')
  await user.type(within(form).getByLabelText('Last four digits'), '5101')
  const accountReads = requests.filter((r) => r === '/api/accounts').length
  await user.click(within(form).getByRole('button', { name: 'Add account' }))

  expect(writes).toEqual([
    {
      method: 'POST',
      url: '/api/accounts',
      body: { name: 'Fixture Checking', institution: 'Fixture Bank', type: 'SAVINGS', lastFour: '5101' },
    },
  ])
  await waitFor(() => expect(location()).toBe('/import?account=7'))
  expect(screen.queryByRole('form', { name: 'Add an account' })).not.toBeInTheDocument()
  // The shell reads the accounts again, so every tab sees the new one.
  await waitFor(() => expect(requests.filter((r) => r === '/api/accounts').length).toBeGreaterThan(accountReads))
})

test('add an account: the API’s field errors are shown as returned, named as the form labels them', async () => {
  const user = userEvent.setup()
  renderApp('/import', ROUTES, () =>
    problem({
      type: 'about:blank',
      title: 'Invalid request',
      status: 400,
      detail: 'One or more fields are invalid',
      errors: [{ field: 'lastFour', message: 'must be exactly 4 digits' }],
    }),
  )

  await user.click(await screen.findByRole('button', { name: 'Add an account' }))
  await user.type(screen.getByLabelText('Last four digits'), '12')
  await user.click(screen.getByRole('button', { name: 'Add account' }))

  const alert = await screen.findByRole('alert')
  expect(alert).toHaveTextContent('One or more fields are invalid')
  expect(alert).toHaveTextContent('Last four: must be exactly 4 digits')
  expect(screen.getByRole('form', { name: 'Add an account' })).toBeInTheDocument()
})

// ---------------------------------------------------------------------------- file and import request

test('the privacy line under the drop zone says what leaves the machine', async () => {
  renderApp('/import', ROUTES)

  expect(await screen.findByTestId('privacy-line')).toHaveTextContent(PRIVACY_LINE)
  expect(PRIVACY_LINE).toBe(
    'The file is read on this computer. With cloud AI on, only merchant names and amounts leave it, for categorization.',
  )
  expect(screen.getByLabelText('Choose a file')).toHaveAttribute('accept', '.ofx,.qfx,.csv,.txt,.pdf')
})

test('a chosen or dropped file shows its name and size; two at once are refused', async () => {
  const user = userEvent.setup()
  renderApp('/import', ROUTES)

  await user.upload(await screen.findByLabelText('Choose a file'), csvFile('statement.csv'))
  expect(screen.getByText('statement.csv')).toBeInTheDocument()
  expect(screen.getByText('· 38 bytes')).toBeInTheDocument()

  const zone = screen.getByTestId('drop-zone')
  fireEvent.drop(zone, { dataTransfer: { files: [csvFile('april.csv')] } })
  expect(screen.getByText('april.csv')).toBeInTheDocument()

  fireEvent.drop(zone, { dataTransfer: { files: [csvFile('a.csv'), csvFile('b.csv')] } })
  expect(screen.getByRole('alert')).toHaveTextContent('One file at a time')
  expect(screen.queryByText('april.csv')).not.toBeInTheDocument()
})

test('import: one multipart part named file to the account’s imports, progress while it runs, then the result', async () => {
  const user = userEvent.setup()
  let answer: (r: Response) => void = () => {}
  const { writes } = renderApp(
    '/import',
    ROUTES,
    () =>
      new Promise<Response>((resolve) => {
        answer = resolve
      }),
  )
  await chooseAccountAndFile(user)
  await user.click(screen.getByRole('button', { name: 'Import' }))

  expect(await screen.findByRole('progressbar', { name: 'Importing' })).toBeInTheDocument()
  expect(writes).toHaveLength(1)
  const write = writes[0] as Write & { body: FormData }
  expect(write.method).toBe('POST')
  expect(write.url).toBe('/api/accounts/1/imports')
  expect(write.body).toBeInstanceOf(FormData)
  expect([...write.body.keys()]).toEqual(['file'])
  expect((write.body.get('file') as File).name).toBe('transfers-northfield-checking_2026-05.csv')

  answer(json(fixtures.importSummary, 201))
  const result = await screen.findByRole('region', { name: 'Imported transfers-northfield-checking_2026-05.csv' })
  expect(screen.queryByRole('progressbar')).not.toBeInTheDocument()
  const s = fixtures.importSummary
  expect(result).toHaveTextContent('Statement periodMay 1, 2026 to May 27, 2026')
  expect(result).toHaveTextContent(`Rows parsed${s.parsed}`)
  expect(result).toHaveTextContent(`New transactions${s.inserted}`)
  expect(result).toHaveTextContent(`Duplicates skipped${s.duplicates}`)
  expect(result).toHaveTextContent('StatusReconciled · Balances check out')
  expect(within(result).queryByRole('heading', { name: 'Warnings' })).not.toBeInTheDocument()
  expect(within(result).getByRole('link', { name: 'Open its transactions' })).toHaveAttribute(
    'href',
    `/transactions?statement=${s.statementId}`,
  )
  // No categorization run is offered for one statement (the endpoint runs over everything): the queue instead.
  expect(within(result).getByRole('link', { name: 'Review queue' })).toHaveAttribute('href', '#review')
  expect(within(result).getByRole('link', { name: 'Transactions that need a category' })).toHaveAttribute(
    'href',
    '/transactions?view=review',
  )
  expect(within(result).queryByRole('button', { name: /categorize/i })).not.toBeInTheDocument()
})

test('a NEEDS_REVIEW import lists every warning as returned', async () => {
  const user = userEvent.setup()
  renderApp('/import', ROUTES, () => json(fixtures.importNeedsReview, 201))
  await chooseAccountAndFile(user, csvFile('csv-windows-1252.csv'))
  await user.click(screen.getByRole('button', { name: 'Import' }))

  const result = await screen.findByRole('region', { name: 'Imported csv-windows-1252.csv' })
  expect(result).toHaveTextContent('StatusNeeds review · Balances don’t add up')
  expect(result).toHaveTextContent('Rows parsed2')
  const warnings = within(within(result).getByRole('region', { name: 'Warnings' })).getAllByRole('listitem')
  expect(warnings.map((w) => w.textContent)).toEqual(fixtures.importNeedsReview.warnings)
  expect(warnings[0]).toHaveTextContent(
    `reconciliation failed: request balances: openingBalance + rows = closingBalance: expected -21.25, actual 0.01, difference 21.26`,
  )
})

test('an unparsed upload (202) says nothing was imported, and why', async () => {
  const user = userEvent.setup()
  renderApp('/import', ROUTES, () => json(fixtures.importUnparsedScan, 202))
  await chooseAccountAndFile(user, new File(['%PDF-'], 'pdf-no-text.pdf'))
  await user.click(screen.getByRole('button', { name: 'Import' }))

  const result = await screen.findByRole('region', { name: 'pdf-no-text.pdf was not imported' })
  expect(result).toHaveTextContent(fixtures.importUnparsedScan.detail)
  expect(result).toHaveTextContent('The PDF has no text (most likely a scan); scans can’t be read yet.')
  expect(within(result).getByRole('link', { name: /It waits in the review queue/ })).toHaveAttribute('href', '#review')
})

test('a file already imported is said plainly, as a notice, not an error', async () => {
  const user = userEvent.setup()
  renderApp('/import', ROUTES, () => problem(fixtures.importAlreadyImported))
  await chooseAccountAndFile(user)
  await user.click(screen.getByRole('button', { name: 'Import' }))

  const result = await screen.findByRole('region', { name: 'Statement already imported' })
  expect(result).toHaveAttribute('aria-live', 'polite')
  expect(result).toHaveTextContent('This file was already imported as statement 24')
  expect(result).toHaveTextContent('Nothing was imported again.')
  expect(within(result).getByRole('link', { name: 'Open statement 24’s transactions' })).toHaveAttribute(
    'href',
    '/transactions?statement=24',
  )
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expect(result.querySelector('.error')).toBeNull()
})

test('any other refusal shows the problem’s title and detail as an error', async () => {
  const user = userEvent.setup()
  renderApp('/import', ROUTES, () => problem(fixtures.importParseError))
  await chooseAccountAndFile(user, new File(['%PDF-'], 'pdf-section-total-mismatch.pdf'))
  await user.click(screen.getByRole('button', { name: 'Import' }))

  const alert = await screen.findByRole('alert')
  expect(alert).toHaveTextContent('Statement could not be read')
  expect(alert).toHaveTextContent(fixtures.importParseError.detail)
})

// ---------------------------------------------------------------------------- CSV mapping (the third write)

test('mapping required: the preview’s first rows and suggested mapping; Use this mapping saves it and imports again', async () => {
  const user = userEvent.setup()
  let imports = 0
  const { writes } = renderApp('/import', ROUTES, (w) => {
    if (w.url === '/api/accounts/1/imports') {
      imports += 1
      return imports === 1 ? problem(fixtures.importMappingRequired) : json(fixtures.importSummary, 201)
    }
    if (w.url === '/api/accounts/1/imports/preview') {
      return json(fixtures.importPreviewCsv)
    }
    if (w.url === '/api/accounts/1/csv-mapping') {
      return json({}, 201)
    }
    return undefined
  })
  await chooseAccountAndFile(user)
  await user.click(screen.getByRole('button', { name: 'Import' }))

  const step = await screen.findByRole('region', { name: 'CSV mapping required' })
  const rows = within(step).getByRole('region', { name: 'First rows of the file' })
  expect(within(rows).getAllByRole('columnheader').map((h) => h.textContent)).toEqual(
    fixtures.importPreviewCsv.headers,
  )
  expect(within(rows).getAllByRole('row')).toHaveLength(1 + fixtures.importPreviewCsv.rows!.length)
  expect(rows).toHaveTextContent('HARBORLINE FREIGHT PAYROLL DIRECT DEP')
  const mapping = within(step).getByRole('region', { name: 'Suggested mapping' })
  expect(mapping).toHaveTextContent('DateDate · found')
  expect(mapping).toHaveTextContent('Date formatMM/dd/yyyy · found')
  expect(mapping).toHaveTextContent('Money outDebit · found')
  expect(mapping).toHaveTextContent('Money inCredit · found')
  expect(mapping).toHaveTextContent('AmountsAs printed: money out is negative · found')
  expect(step).not.toHaveTextContent('POST /api')

  await user.click(within(step).getByRole('button', { name: 'Use this mapping' }))

  expect(await screen.findByRole('region', { name: 'Imported transfers-northfield-checking_2026-05.csv' })).toBeInTheDocument()
  expect(writes.map((w) => `${w.method} ${w.url}`)).toEqual([
    'POST /api/accounts/1/imports',
    'POST /api/accounts/1/imports/preview',
    'PUT /api/accounts/1/csv-mapping',
    'POST /api/accounts/1/imports',
  ])
  expect(writes[2].body).toEqual(fixtures.importPreviewCsv.suggestedMapping!.mapping)
  expect((writes[3].body as FormData).get('file')).toBeInstanceOf(File)
})

test('mapping required: a mapping the API refuses is said, and nothing is imported', async () => {
  const user = userEvent.setup()
  const { writes } = renderApp('/import', ROUTES, (w) => {
    if (w.url === '/api/accounts/1/imports') {
      return problem(fixtures.importMappingRequired)
    }
    if (w.url === '/api/accounts/1/imports/preview') {
      return json(fixtures.importPreviewCsv)
    }
    return problem({
      type: 'about:blank',
      title: 'Invalid request',
      status: 400,
      detail: 'One or more fields are invalid',
      errors: [{ field: 'dateColumn', message: 'must not be blank' }],
    })
  })
  await chooseAccountAndFile(user)
  await user.click(screen.getByRole('button', { name: 'Import' }))
  await user.click(await screen.findByRole('button', { name: 'Use this mapping' }))

  expect(await screen.findByRole('alert')).toHaveTextContent('One or more fields are invalid. dateColumn must not be blank')
  expect(writes.filter((w) => w.url === '/api/accounts/1/imports')).toHaveLength(1)
})

test('mapping mismatch: the problem as returned, with the same preview, and no button to replace the mapping', async () => {
  const user = userEvent.setup()
  renderApp('/import', ROUTES, (w) =>
    w.url.endsWith('/preview')
      ? json({ ...fixtures.importMappingMismatch.preview, rows: [['2026-05-02', '2026-05-03', 'REFUND', 'Shopping', '12.00']] })
      : problem(fixtures.importMappingMismatch),
  )
  await chooseAccountAndFile(user, csvFile('csv-card-refund.csv'))
  await user.click(screen.getByRole('button', { name: 'Import' }))

  const step = await screen.findByRole('region', { name: 'CSV mapping does not match the file' })
  expect(step).toHaveTextContent(fixtures.importMappingMismatch.detail)
  const rows = within(step).getByRole('region', { name: 'First rows of the file' })
  expect(within(rows).getAllByRole('columnheader').map((h) => h.textContent)).toEqual(
    fixtures.importMappingMismatch.preview!.headers,
  )
  expect(rows).toHaveTextContent('REFUND')
  expect(within(step).getByRole('region', { name: 'Suggested mapping' })).toHaveTextContent('AmountAmount · found')
  expect(within(step).queryByRole('button', { name: 'Use this mapping' })).not.toBeInTheDocument()
})

test('the suggested mapping says when amounts would be flipped, and that it is a guess', async () => {
  const user = userEvent.setup()
  const preview = fixtures.importPreviewCsv
  const flipped = {
    ...preview,
    suggestedMapping: {
      ...preview.suggestedMapping!,
      mapping: { ...preview.suggestedMapping!.mapping, sign: 'INVERTED' as const },
      confidence: { ...preview.suggestedMapping!.confidence, sign: 'GUESSED' as const },
    },
  }
  renderApp('/import', ROUTES, (w) => (w.url.endsWith('/preview') ? json(flipped) : problem(fixtures.importMappingRequired)))
  await chooseAccountAndFile(user)
  await user.click(screen.getByRole('button', { name: 'Import' }))

  expect(await screen.findByRole('region', { name: 'Suggested mapping' })).toHaveTextContent(
    'AmountsMoney out is printed positive: every amount is flipped · guessed, check it',
  )
})

// ---------------------------------------------------------------------------- statements list

test('statements by account, newest first as returned, each opening Transactions filtered to it', async () => {
  renderApp('/import', ROUTES)

  const list = await screen.findByRole('region', { name: 'Statements' })
  const checking = await within(list).findByRole('region', { name: 'Northfield Bank Checking ···1001' })
  const links = within(checking).getAllByRole('link')
  expect(links.map((l) => l.getAttribute('href'))).toEqual(
    fixtures.statementsByAccount[1].map((s) => `/transactions?statement=${s.id}`),
  )
  expect(links[0]).toHaveTextContent('Sep 1, 2026 to Sep 30, 2026')
  const first = links[0].closest('li')!
  expect(first).toHaveTextContent('9 transactions · 9 rows in the file · Reconciled · Balances check out · imported Oct 23, 2026')
  expect(within(list).getByRole('region', { name: 'Northfield Bank Savings ···1002' })).toHaveTextContent(
    'No statements yet.',
  )
  expect(within(list).getByRole('region', { name: /Northfield Checking \(imported twice\)/ })).toHaveTextContent('closed')
})

test('a statement opens the Transactions tab filtered to it', async () => {
  const user = userEvent.setup()
  const { location } = renderApp('/import', ROUTES)

  const checking = await screen.findByRole('region', { name: 'Northfield Bank Checking ···1001' })
  await user.click(await within(checking).findByRole('link', { name: 'Sep 1, 2026 to Sep 30, 2026' }))
  expect(location()).toBe('/transactions?statement=21')
})

test('delete asks first, saying what it removes; then says what went and every page reads again', async () => {
  const user = userEvent.setup()
  const { writes, requests } = renderApp('/import', ROUTES, (w) =>
    w.method === 'DELETE' ? json(fixtures.statementDeleted) : undefined,
  )

  const checking = await screen.findByRole('region', { name: 'Northfield Bank Checking ···1001' })
  const row = (await within(checking).findByRole('link', { name: 'Sep 1, 2026 to Sep 30, 2026' })).closest('li')!
  await user.click(within(row).getByRole('button', { name: 'Delete the statement Sep 1, 2026 to Sep 30, 2026' }))
  const dialog = within(row).getByRole('alertdialog')
  expect(dialog).toHaveTextContent(
    'Delete the statement Sep 1, 2026 to Sep 30, 2026 of Northfield Bank Checking ···1001? This removes the 9 transactions it imported, and any transfer links on them. The file can be imported again afterwards.',
  )
  await user.click(within(dialog).getByRole('button', { name: 'Cancel' }))
  expect(within(row).queryByRole('alertdialog')).not.toBeInTheDocument()
  expect(writes).toEqual([])

  await user.click(within(row).getByRole('button', { name: 'Delete the statement Sep 1, 2026 to Sep 30, 2026' }))
  const coverageReads = requests.filter((r) => r === '/api/analytics/coverage').length
  const statementReads = requests.filter((r) => r === '/api/accounts/1/statements').length
  await user.click(within(row).getByRole('button', { name: 'Delete statement' }))

  expect(writes).toEqual([{ method: 'DELETE', url: '/api/statements/21', body: undefined }])
  expect(await screen.findByText('Statement deleted: 8 transactions and 4 transfer links removed.')).toBeInTheDocument()
  await waitFor(() => {
    expect(requests.filter((r) => r === '/api/analytics/coverage').length).toBeGreaterThan(coverageReads)
    expect(requests.filter((r) => r === '/api/accounts/1/statements').length).toBeGreaterThan(statementReads)
  })
})

// ---------------------------------------------------------------------------- review sections

test('statements to review: warnings as returned; accept and delete use the existing endpoints', async () => {
  const user = userEvent.setup()
  const { writes } = renderApp('/import', ROUTES, (w) =>
    w.url.endsWith('/review') ? json(fixtures.statementDetail) : w.method === 'DELETE' ? json(fixtures.statementDeleted) : undefined,
  )

  const section = await screen.findByRole('region', { name: 'Statements to review' })
  expect(section).toHaveTextContent('2 statements')
  const item = within(section).getByText('Fixture Ledger Checking · Apr 5, 2026 to Apr 6, 2026').closest('li')!
  expect(item).toHaveTextContent('csv-windows-1252.csv · Balances don’t add up')
  expect(within(item).getAllByRole('listitem')).toHaveLength(3)

  await user.click(within(item).getByRole('button', { name: 'Accept as it is' }))
  expect(writes[0]).toEqual({ method: 'POST', url: '/api/statements/25/review', body: undefined })
  expect(await screen.findByText('Statement accepted as it is; its warnings stay on record.')).toBeInTheDocument()

  await user.click(within(item).getByRole('button', { name: 'Delete' }))
  // Statement 25 isn't in the sample accounts' lists, so the question names the rows without a count.
  expect(within(item).getByRole('alertdialog')).toHaveTextContent('This removes the transactions it imported')
  await user.click(within(item).getByRole('button', { name: 'Delete statement' }))
  expect(writes[1]).toEqual({ method: 'DELETE', url: '/api/statements/25', body: undefined })
  expect(await screen.findByText('Statement deleted: 8 transactions and 4 transfer links removed.')).toBeInTheDocument()
})

test('uploads not recognized: file, account, reason; dismiss', async () => {
  const user = userEvent.setup()
  const { writes } = renderApp('/import', ROUTES, () => json(fixtures.importUnparsed))

  const section = await screen.findByRole('region', { name: 'Uploads not recognized' })
  expect(section).toHaveTextContent('2 uploads')
  const scan = within(section).getByText('pdf-no-text.pdf').closest('li')!
  expect(scan).toHaveTextContent('Fixture Checking · uploaded Oct 23, 2026')
  expect(scan).toHaveTextContent('The PDF has no text (most likely a scan)')

  await user.click(within(scan).getByRole('button', { name: 'Dismiss' }))
  expect(writes).toEqual([{ method: 'POST', url: '/api/unparsed-uploads/3/dismiss', body: undefined }])
  expect(await screen.findByText('Upload dismissed.')).toBeInTheDocument()
})

test('a transfer candidate group: its transactions open their sheets, and each pair can be linked', async () => {
  const user = userEvent.setup()
  const { writes, location } = renderApp('/import', ROUTES, () => json({}, 201))

  const group = await screen.findByRole('region', { name: 'Which transactions are one transfer?' })
  expect(group).toHaveTextContent('1 group')
  const out = `Fixture Checking ${MINUS}$200.00`
  expect(within(group).getByText(`${out} → Fixture Savings $200.00 · same day`, { exact: false })).toBeInTheDocument()
  expect(within(group).getByText(`${out} → Fixture Card $200.00 · 1 day apart`, { exact: false })).toBeInTheDocument()

  await user.click(within(group).getByRole('button', { name: `Link ${out} → Fixture Card $200.00` }))
  expect(writes).toEqual([{ method: 'POST', url: '/api/transfers', body: { outTxnId: 297, inTxnId: 286 } }])
  expect(await screen.findByText('Linked as one transfer.')).toBeInTheDocument()

  await user.click(within(group).getAllByRole('button', { name: /ONLINE TRANSFER FROM CHECKING/ })[0])
  expect(location()).toContain('txn=279')
})

test('a competing candidate: keep the link or unlink it', async () => {
  const user = userEvent.setup()
  const { writes } = renderApp('/import', { ...ROUTES, '/api/review-queue': fixtures.reviewQueueCompeting }, (w) =>
    w.method === 'DELETE' ? new Response(null, { status: 204 }) : json({}),
  )

  const section = await screen.findByRole('region', { name: 'An automatic link with a rival' })
  expect(section).toHaveTextContent('1 link')
  expect(section).toHaveTextContent('A later transaction could match it too:')
  await user.click(within(section).getByRole('button', { name: 'Keep this link' }))
  await screen.findByText('Kept the link.')
  await user.click(within(section).getByRole('button', { name: 'Unlink' }))
  await screen.findByText('Unlinked. The transactions wait here to be matched again.')
  expect(writes).toEqual([
    { method: 'POST', url: '/api/transfers/26/confirm', body: undefined },
    { method: 'DELETE', url: '/api/transfers/26', body: undefined },
  ])
})

test('a possible match: join the two one-sided links, or keep them apart; a refusal shows the API’s detail', async () => {
  const user = userEvent.setup()
  let calls = 0
  const { writes } = renderApp('/import', { ...ROUTES, '/api/review-queue': fixtures.reviewQueuePossibleMatch }, () => {
    calls += 1
    return calls === 1
      ? json({}, 201)
      : problem({ type: 'urn:finalysis:problem:transfer-conflict', title: 'x', status: 409, detail: 'Already linked' })
  })

  const section = await screen.findByRole('region', { name: 'One-sided transfers that may be one' })
  expect(section).toHaveTextContent('1 possible match')
  expect(section).toHaveTextContent('Both are marked one-sided · 1 day apart')
  await user.click(within(section).getByRole('button', { name: 'Join as one transfer' }))
  await screen.findByText('Joined as one transfer.')
  await user.click(within(section).getByRole('button', { name: 'Not the same transfer' }))
  expect(await within(section).findByRole('alert')).toHaveTextContent('Already linked')
  expect(writes).toEqual([
    { method: 'POST', url: '/api/transfers/join', body: { outLinkId: 31, inLinkId: 32 } },
    { method: 'POST', url: '/api/transfers/join/dismiss', body: { outLinkId: 31, inLinkId: 32 } },
  ])
})

test('an empty queue says so in each section', async () => {
  const empty = { ...fixtures.reviewQueue, counts: { ...fixtures.reviewQueue.counts, statements: 0, unparsedUploads: 0 }, items: [] }
  renderApp('/import', { ...ROUTES, '/api/review-queue': empty })

  const review = await screen.findByRole('region', { name: 'Review queue' })
  expect(await within(review).findAllByText('None.')).toHaveLength(3)
})
