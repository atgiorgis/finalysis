import { expect, test, type Page } from '@playwright/test'
import { fixtures, usageFixture } from '../src/test/fixtures'
import { mockApi, withStatus } from './mockApi'

/**
 * UI-07 (docs/plans/phase-7.md §7): every screen fits a 375 px phone with no horizontal scroll and its touch targets are
 * at least 44 px tall. Since step 7c one top bar and a slide-in menu serve every width: the menu button shows, the tabs
 * don't until it is opened.
 */

const MIN_TARGET = 44
const GROCERIES = fixtures.detailsCategory.category?.id
const CLOUD = {
  ...fixtures.statusOff,
  ai: { mode: 'CLOUD', provider: 'bedrock', models: { main: 'm', categorization: 'c', embedding: 'e' } },
}

/** Every screen, with the state that makes it widest: open panels, a sheet, long text. */
const SCREENS: { name: string; path: string; ready: string; overrides?: Record<string, unknown> }[] = [
  { name: 'overview', path: '/overview?month=2026-09', ready: 'Where it went' },
  {
    name: 'overview, cloud banner',
    path: '/overview?month=2026-09',
    ready: 'Where it went',
    overrides: { '/api/status': CLOUD },
  },
  { name: 'overview, partial month', path: '/overview?month=2026-10', ready: 'Data quality' },
  { name: 'details panel', path: `/overview?month=2026-09&detail=cat:${GROCERIES}`, ready: '6-month trend' },
  { name: 'details: other', path: '/overview?month=2026-09&detail=other', ready: 'each category opens its own panel' },
  { name: 'details: income', path: '/overview?month=2026-09&detail=income', ready: '6-month trend' },
  { name: 'transaction sheet', path: `/overview?month=2026-09&txn=${fixtures.transactionDetail.id}`, ready: 'Statement line' },
  { name: 'transactions', path: '/transactions?month=2026-09', ready: 'transactions in September 2026' },
  { name: 'to review', path: '/transactions?view=review', ready: 'Transfers without a match' },
  { name: 'alerts', path: '/alerts?month=2026-09', ready: 'Why this alert' },
  { name: 'recurring', path: '/recurring', ready: 'See its charges' },
  { name: 'drill', path: '/drill?month=2026-09&bucket=EXPENSE&categoryId=2&label=Groceries&expect=-477.94', ready: 'add up to' },
  { name: 'chat list', path: '/chat', ready: 'New conversation' },
  { name: 'chat conversation', path: '/chat/7', ready: 'You spent $477.94' },
  { name: 'goals', path: '/goals', ready: 'No goals yet' },
  {
    name: 'import: statements and a candidate group',
    path: '/import?account=1',
    ready: 'Which transactions are one transfer?',
    overrides: { '/api/review-queue': fixtures.reviewQueueCandidates },
  },
  {
    name: 'import: a competing candidate',
    path: '/import',
    ready: 'An automatic link with a rival',
    overrides: { '/api/review-queue': fixtures.reviewQueueCompeting },
  },
  {
    name: 'import: a possible match',
    path: '/import',
    ready: 'One-sided transfers that may be one',
    overrides: { '/api/review-queue': fixtures.reviewQueuePossibleMatch },
  },
  {
    name: 'transactions: one statement',
    path: `/transactions?month=2026-09&statement=${fixtures.statementDetail.id}`,
    ready: 'in this statement',
    overrides: { '/api/transactions': fixtures.transactionsStatement },
  },
  { name: 'settings', path: '/settings', ready: 'Chat costs' },
]

/** Interactive elements under 44 px tall that aren't inline links in running text (WCAG 2.5.8's inline exception). */
async function smallTargets(page: Page): Promise<string[]> {
  return page.evaluate((min) => {
    const out: string[] = []
    for (const control of document.querySelectorAll<HTMLElement>('a[href], button, select, input, textarea, [role="button"]')) {
      // A visually hidden input (the Import tab's file input) is reached through its label: measure the label.
      const hiddenInput = control.tagName === 'INPUT' && control.getBoundingClientRect().width <= 1
      const el = hiddenInput ? (control as HTMLInputElement).labels?.[0] ?? control : control
      const box = el.getBoundingClientRect()
      const style = getComputedStyle(el)
      if (box.width === 0 || box.height === 0 || style.visibility === 'hidden') {
        continue
      }
      const inline = style.display === 'inline' && el.tagName === 'A'
      const checkbox = el.tagName === 'INPUT' && (el as HTMLInputElement).type === 'checkbox'
      if (inline || checkbox) {
        continue
      }
      if (box.height < min - 0.5) {
        const label = (el.getAttribute('aria-label') ?? el.textContent ?? el.tagName).trim().slice(0, 40)
        out.push(`${el.tagName.toLowerCase()} "${label}" ${Math.round(box.height)}px`)
      }
    }
    return out
  }, MIN_TARGET)
}

for (const screen of SCREENS) {
  test(`${screen.name} fits`, async ({ page }, info) => {
    await mockApi(page, screen.overrides)
    await page.goto(screen.path)
    const ready = screen.ready.startsWith('h1:')
      ? page.getByRole('heading', { level: 1, name: screen.ready.slice(3) })
      : page.getByText(screen.ready, { exact: false }).first()
    await expect(ready).toBeVisible()

    const { scrollWidth, innerWidth } = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      innerWidth: window.innerWidth,
    }))
    expect(scrollWidth, 'no horizontal scroll').toBeLessThanOrEqual(innerWidth)

    const phone = info.project.name === 'phone'
    await expect(page.getByRole('button', { name: 'Open menu' })).toBeVisible()
    await expect(page.getByRole('navigation', { name: 'Main' })).toHaveCount(0)

    if (phone) {
      expect(await smallTargets(page), 'touch targets at least 44 px').toEqual([])
    }
  })
}

test('the touch-target check finds a small button and skips inline links', async ({ page }, info) => {
  test.skip(info.project.name !== 'phone', 'phone only')
  await mockApi(page)
  await page.goto('/settings')
  await expect(page.getByText('Chat costs')).toBeVisible()
  expect(await smallTargets(page)).toEqual([])
  await page.evaluate(() => {
    const small = document.createElement('button')
    small.textContent = 'tiny'
    small.style.height = '20px'
    const inline = document.createElement('a')
    inline.href = '/x'
    inline.textContent = 'inline'
    const p = document.createElement('p')
    p.append('text with an ', inline, ' link')
    // A visually hidden file input is measured by its label.
    const input = document.createElement('input')
    input.type = 'file'
    input.id = 'tiny-file'
    input.className = 'visually-hidden'
    const label = document.createElement('label')
    label.htmlFor = 'tiny-file'
    label.textContent = 'tiny label'
    label.style.display = 'inline-block'
    label.style.height = '20px'
    document.querySelector('main')?.append(small, p, label, input)
  })
  expect(await smallTargets(page)).toEqual(['button "tiny" 20px', 'label "tiny label" 20px'])
})

async function noHorizontalScroll(page: Page) {
  const { scrollWidth, innerWidth } = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    innerWidth: window.innerWidth,
  }))
  expect(scrollWidth, 'no horizontal scroll').toBeLessThanOrEqual(innerWidth)
}

for (const theme of ['dark', 'light'] as const) {
  test(`the open menu fits, its targets are 44 px, and a tab is reachable (${theme})`, async ({ page }, info) => {
    await page.addInitScript((t) => localStorage.setItem('finalysis.theme', t), theme)
    await mockApi(page)
    await page.goto('/overview?month=2026-09')
    await expect(page.getByText('Where it went')).toBeVisible()
    await expect(page.locator('html')).toHaveAttribute('data-theme', theme)

    await page.getByRole('button', { name: 'Open menu' }).click()
    const menu = page.getByRole('dialog', { name: 'Menu' })
    await expect(menu).toBeVisible()
    await expect(menu.getByRole('link', { name: 'Overview' })).toBeFocused()
    // Measured once the slide-in has finished.
    await expect.poll(async () => (await menu.boundingBox())!.x).toBe(0)
    const box = (await menu.boundingBox())!
    expect(box.x + box.width).toBeLessThanOrEqual(page.viewportSize()!.width)
    await noHorizontalScroll(page)
    if (info.project.name === 'phone') {
      expect(await smallTargets(page), 'touch targets at least 44 px').toEqual([])
    }

    await menu.getByRole('link', { name: 'Recurring' }).click()
    await expect(page.getByRole('heading', { level: 1, name: 'Recurring charges' })).toBeVisible()
    await expect(menu).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Open menu' })).toBeFocused()
    await noHorizontalScroll(page)
  })
}

test('Escape closes the menu and focus returns to the menu button', async ({ page }) => {
  await mockApi(page)
  await page.goto('/settings')
  await expect(page.getByText('Chat costs')).toBeVisible()
  await page.getByRole('button', { name: 'Open menu' }).click()
  await expect(page.getByRole('dialog', { name: 'Menu' })).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog', { name: 'Menu' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Open menu' })).toBeFocused()
})

test('the light theme fits too: the Overview at its widest', async ({ page }, info) => {
  await page.addInitScript(() => localStorage.setItem('finalysis.theme', 'light'))
  await mockApi(page, { '/api/status': CLOUD })
  await page.goto(`/overview?month=2026-09&detail=cat:${GROCERIES}`)
  await expect(page.getByText('6-month trend').first()).toBeVisible()
  await noHorizontalScroll(page)
  if (info.project.name === 'phone') {
    expect(await smallTargets(page), 'touch targets at least 44 px').toEqual([])
  }
})

test('the transaction sheet is a bottom sheet on a phone, a side panel on desktop', async ({ page }, info) => {
  await mockApi(page)
  await page.goto(`/overview?month=2026-09&txn=${fixtures.transactionDetail.id}`)
  const sheet = page.getByRole('dialog')
  await expect(sheet).toBeVisible()
  const box = (await sheet.boundingBox())!
  const viewport = page.viewportSize()!
  if (info.project.name === 'phone') {
    expect(box.width).toBeCloseTo(viewport.width, 0)
    expect(box.y + box.height).toBeCloseTo(viewport.height, 0)
  } else {
    expect(box.width).toBeLessThanOrEqual(420)
    expect(box.x + box.width).toBeCloseTo(viewport.width, 0)
  }
})


/** The Import tab's states that need a click: a result, the mapping step, the add-account form, a delete question. */
const FILE = { name: 'transfers-northfield-checking_2026-05.csv', mimeType: 'text/csv', buffer: Buffer.from('Date\n') }

async function fitsAt375(page: Page, phone: boolean) {
  await noHorizontalScroll(page)
  if (phone) {
    expect(await smallTargets(page), 'touch targets at least 44 px').toEqual([])
  }
}

/**
 * Chromium offers the File System Access picker, so the Import tab shows a button that opens it (Phase 8 §7). The check
 * replaces the browser's picker with one that hands back a synthetic file and a handle that deletes nothing.
 */
async function chooseFile(page: Page) {
  await page.evaluate(({ name, text }) => {
    const file = new File([text], name, { type: 'text/csv' })
    ;(window as unknown as { showOpenFilePicker: unknown }).showOpenFilePicker = async () => [
      { name, getFile: async () => file, requestPermission: async () => 'granted', remove: async () => {} },
    ]
  }, { name: FILE.name, text: FILE.buffer.toString() })
  await page.getByRole('button', { name: 'Choose a file' }).click()
  await expect(page.getByText(FILE.name)).toBeVisible()
}

async function importFile(page: Page) {
  await page.getByLabel('Account').selectOption('1')
  await chooseFile(page)
  await page.getByRole('button', { name: 'Import', exact: true }).click()
}

test('import: the choice after an import, and its delete question, fit', async ({ page }, info) => {
  await mockApi(page, {
    '/api/status': vaultStatus('UNLOCKED'),
    '/api/accounts/1/imports': withStatus(201, fixtures.importSummary),
    '/api/imports/settings': { afterImport: 'ASK' },
  })
  await page.goto('/import')
  await importFile(page)
  await expect(page.getByRole('button', { name: 'Keep a copy in the vault' })).toBeVisible()
  await fitsAt375(page, info.project.name === 'phone')
  expect(await outsideTheirCard(page), 'controls inside their card').toEqual([])

  await page.getByRole('button', { name: 'Delete the original' }).click()
  await expect(page.getByRole('button', { name: 'Delete it permanently' })).toBeVisible()
  await fitsAt375(page, info.project.name === 'phone')
  expect(await outsideTheirCard(page), 'controls inside their card').toEqual([])
})

test('import: a result with long warnings fits', async ({ page }, info) => {
  await mockApi(page, { '/api/accounts/1/imports': withStatus(201, fixtures.importNeedsReview) })
  await page.goto('/import')
  await importFile(page)
  await expect(page.getByRole('heading', { name: 'Warnings' })).toBeVisible()
  await fitsAt375(page, info.project.name === 'phone')
})

test('import: the mapping step’s wide preview scrolls inside its card', async ({ page }, info) => {
  await mockApi(page, {
    '/api/accounts/1/imports': withStatus(422, fixtures.importMappingRequired),
    '/api/accounts/1/imports/preview': fixtures.importPreviewCsv,
  })
  await page.goto('/import')
  await importFile(page)
  await expect(page.getByRole('button', { name: 'Use this mapping' })).toBeVisible()
  await fitsAt375(page, info.project.name === 'phone')
})

test('import: the already-imported notice fits', async ({ page }, info) => {
  await mockApi(page, { '/api/accounts/1/imports': withStatus(409, fixtures.importAlreadyImported) })
  await page.goto('/import')
  await importFile(page)
  await expect(page.getByText('Nothing was imported again.')).toBeVisible()
  await fitsAt375(page, info.project.name === 'phone')
})

test('import: the add-account form fits', async ({ page }, info) => {
  await mockApi(page)
  await page.goto('/import')
  await page.getByRole('button', { name: 'Add an account' }).click()
  await expect(page.getByLabel('Last four digits')).toBeVisible()
  await fitsAt375(page, info.project.name === 'phone')
})

test('import: the delete question fits', async ({ page }, info) => {
  await mockApi(page)
  await page.goto('/import')
  const checking = page.getByRole('region', { name: 'Northfield Bank Checking ···1001' })
  await checking.getByRole('button', { name: 'Delete the statement Sep 1, 2026 to Sep 30, 2026' }).click()
  await expect(checking.getByRole('alertdialog')).toBeVisible()
  await fitsAt375(page, info.project.name === 'phone')
})

// ---------------------------------------------------------------------------- step 7e: Corgi, sign choice, run summary

const CLOUD_STATUS = {
  ...fixtures.statusOff,
  ai: { mode: 'CLOUD', provider: 'bedrock', models: { main: 'test-main-model', categorization: 'c', embedding: 'e' } },
}

for (const [where, status, label] of [
  ['cloud', CLOUD_STATUS, 'Cloud · Bedrock'],
  ['local', { ...CLOUD_STATUS, ai: { ...CLOUD_STATUS.ai, mode: 'LOCAL', provider: 'ollama' } }, 'Local · Ollama'],
] as const) {
  test(`chat: Corgi’s header with the ${where} badge fits, and the input line under it`, async ({ page }, info) => {
    await mockApi(page, { '/api/status': status, '/api/chat/usage': { ...usageFixture, spentToday: '0.214300' } })
    await page.goto('/chat/7')
    await expect(page.getByText(label)).toBeVisible()
    await expect(page.getByText('Reads your books. Never changes them.')).toBeVisible()
    await expect(page.getByText('Figures computed by Finalysis, not by the model · 3 checked')).toBeVisible()
    await expect(page.getByText('It can’t give financial advice.', { exact: false })).toBeVisible()
    const badge = await page.locator('.path-badge').boundingBox()
    expect(badge!.x + badge!.width, 'the badge stays on screen').toBeLessThanOrEqual(page.viewportSize()!.width)
    await fitsAt375(page, info.project.name === 'phone')
  })
}

test('import: the two sign buttons with their examples fit', async ({ page }, info) => {
  await mockApi(page, {
    '/api/accounts/1/imports': withStatus(422, fixtures.importMappingRequired),
    '/api/accounts/1/imports/preview': fixtures.importPreviewCard,
  })
  await page.goto('/import')
  await importFile(page)
  await expect(page.getByRole('button', { name: /Purchases are negative in this file/ })).toBeVisible()
  await expect(page.getByRole('button', { name: /Purchases are positive in this file/ })).toBeVisible()
  await fitsAt375(page, info.project.name === 'phone')
})

test('transactions: Categorize now and its summary fit', async ({ page }, info) => {
  const run = {
    ...fixtures.categorizationRunOff,
    ai: { ...fixtures.categorizationRunOff.ai, status: 'DONE' },
    summary: { categorized: 97, toReview: 34, heldBack: 1, modelCalls: 4, merchantsIndexed: 126 },
  }
  await mockApi(page, {
    '/api/transactions': { ...fixtures.transactionsUncategorized, total: 34 },
    '/api/review-queue': fixtures.reviewQueue,
    '/api/categorization/run': run,
  })
  await page.goto('/transactions?view=review')
  await page.getByRole('button', { name: 'Categorize now' }).click()
  await expect(page.getByText('126 merchants indexed for search')).toBeVisible()
  await page.getByRole('button', { name: '1 held back for privacy' }).click()
  await expect(page.getByText(/privacy check refused it/)).toBeVisible()
  await fitsAt375(page, info.project.name === 'phone')
})

// ---- the vault (Phase 8 step 3, docs/plans/phase-8.md §6): every vault screen at 375 px, both themes ----

/** Controls that stick out of their card on the right (no page scroll shows it: the card is inside the page). */
async function outsideTheirCard(page: Page): Promise<string[]> {
  return page.evaluate(() => {
    const out: string[] = []
    for (const control of document.querySelectorAll<HTMLElement>('.card input, .card button, .card select')) {
      const card = control.closest('.card')!.getBoundingClientRect()
      const box = control.getBoundingClientRect()
      if (box.width > 0 && box.right > card.right + 0.5) {
        out.push(`${control.tagName.toLowerCase()} ${(control.getAttribute('aria-label') ?? control.textContent ?? '').trim().slice(0, 30)}`)
      }
    }
    return out
  })
}

function vaultStatus(state: string, extra: Record<string, unknown> = {}) {
  return {
    ...fixtures.statusOff,
    vault: { ...fixtures.statusOff.vault, storage: 'VAULT', setUp: state !== 'NOT_SET_UP', state, ...extra },
  }
}

const KEY = 'ABCD-EFGH-JKMN-PQRS-TVWX-YZ01-2345-6789-ABCD-EFGH-JKMN-PQRS-TV0G'
const LONG_REASON =
  'Finalysis needs pgvector 0.8.6; the installed one is 0.8.7. See the README’s setup section, which explains how ' +
  'to install the pinned version of Postgres.app and turn off its automatic updates.'

/** Each vault screen, with what it takes to reach it and its widest state. */
const VAULT_SCREENS: {
  name: string
  overrides: Record<string, unknown>
  reach?: (page: Page) => Promise<void>
  ready: string
}[] = [
  { name: 'setup', overrides: { '/api/status': vaultStatus('NOT_SET_UP') }, ready: 'Set up your vault' },
  {
    name: 'recovery key',
    overrides: {
      '/api/status': vaultStatus('NOT_SET_UP'),
      '/api/vault/setup': withStatus(201, { recoveryKey: KEY }),
    },
    reach: async (page) => {
      await page.getByLabel('Password', { exact: true }).fill('river stone lamp orbit')
      await page.getByLabel('Password again').fill('river stone lamp orbit')
      await page.getByRole('button', { name: 'Create the vault' }).click()
    },
    ready: 'Your recovery key',
  },
  { name: 'unlock', overrides: { '/api/status': vaultStatus('LOCKED') }, ready: 'Finalysis is locked' },
  {
    name: 'unlock, too many attempts',
    overrides: {
      '/api/status': vaultStatus('LOCKED'),
      '/api/vault/unlock': withStatus(429, {
        type: 'urn:finalysis:problem:too-many-attempts',
        detail: 'Too many wrong attempts. Wait 15 minutes and try again.',
      }),
    },
    reach: async (page) => {
      await page.getByLabel('Password').fill('a wrong password')
      await page.getByRole('button', { name: 'Unlock' }).click()
      await expect(page.getByRole('alert')).toBeVisible()
    },
    ready: 'Finalysis is locked',
  },
  {
    name: 'recovery',
    overrides: { '/api/status': vaultStatus('LOCKED') },
    reach: async (page) => {
      await page.getByRole('button', { name: 'Forgot your password? Use your recovery key' }).click()
    },
    ready: 'Unlock with your recovery key',
  },
  {
    name: 'not ready',
    overrides: { '/api/status': vaultStatus('NOT_READY', { setUp: true, reason: 'pgvector-wrong-version', message: LONG_REASON }) },
    ready: 'Finalysis is locked',
  },
  {
    name: 'broken',
    overrides: {
      '/api/status': vaultStatus('BROKEN', {
        reason: 'image-missing',
        message: 'The vault’s key file is there but its disk image is missing. Restore the image from a backup.',
      }),
    },
    ready: 'The vault needs attention',
  },
  {
    name: 'phone login',
    overrides: { '/api/status': withStatus(401, { type: 'urn:finalysis:problem:login-required', status: 401 }) },
    ready: 'Log in to Finalysis',
  },
  {
    name: 'lock failed',
    overrides: { '/api/status': vaultStatus('LOCK_FAILED', { reason: 'unmount', message: 'Locking did not finish (unmount).' }) },
    ready: 'Locking didn’t finish',
  },
]

for (const theme of ['dark', 'light'] as const) {
  for (const screen of VAULT_SCREENS) {
    test(`vault: ${screen.name} fits (${theme})`, async ({ page }, info) => {
      await page.addInitScript((t) => localStorage.setItem('finalysis.theme', t), theme)
      await mockApi(page, screen.overrides)
      await page.goto('/overview')
      await screen.reach?.(page)
      await expect(page.getByRole('heading', { level: 1, name: screen.ready })).toBeVisible()
      await expect(page.locator('html')).toHaveAttribute('data-theme', theme)
      // Nothing of the app behind a vault screen.
      await expect(page.getByRole('banner')).toHaveCount(0)

      const { scrollWidth, innerWidth } = await page.evaluate(() => ({
        scrollWidth: document.documentElement.scrollWidth,
        innerWidth: window.innerWidth,
      }))
      expect(scrollWidth, 'no horizontal scroll').toBeLessThanOrEqual(innerWidth)
      expect(await outsideTheirCard(page), 'controls inside their card').toEqual([])
      if (info.project.name === 'phone') {
        expect(await smallTargets(page), 'touch targets at least 44 px').toEqual([])
      }
    })
  }

  test(`vault: the top bar with the Lock button and Settings → Vault fit (${theme})`, async ({ page }, info) => {
    await page.addInitScript((t) => localStorage.setItem('finalysis.theme', t), theme)
    await mockApi(page, { '/api/status': vaultStatus('UNLOCKED') })
    await page.goto('/settings')
    const bar = page.getByRole('banner')
    const lock = bar.getByRole('button', { name: 'Lock' })
    await expect(lock).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Vault' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Lock now' })).toBeVisible()

    const box = (await lock.boundingBox())!
    expect(box.x + box.width, 'the Lock button stays on screen').toBeLessThanOrEqual(page.viewportSize()!.width)
    const { scrollWidth, innerWidth } = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      innerWidth: window.innerWidth,
    }))
    expect(scrollWidth, 'no horizontal scroll').toBeLessThanOrEqual(innerWidth)
    expect(await outsideTheirCard(page), 'controls inside their card').toEqual([])
    if (info.project.name === 'phone') {
      expect(await smallTargets(page), 'touch targets at least 44 px').toEqual([])
    }
  })
}

for (const theme of ['dark', 'light'] as const) {
  test(`phone access: the open card with its address, certificate and steps fits (${theme})`, async ({ page }, info) => {
    await page.addInitScript((t) => localStorage.setItem('finalysis.theme', t), theme)
    await mockApi(page, {
      '/api/status': vaultStatus('UNLOCKED'),
      '/api/access/session': { authenticated: true, lan: false },
      '/api/access': {
        enabled: true,
        address: '192.168.100.157',
        port: 18443,
        open: true,
        reason: null,
        url: 'https://192.168.100.157:18443',
        caFingerprint: Array.from({ length: 32 }, () => 'AB').join(':'),
        availableAddresses: ['192.168.100.157', '10.250.250.250'],
        sessions: 1,
      },
    })
    await page.goto('/settings')
    await expect(page.getByRole('link', { name: 'Save the certificate' })).toBeVisible()
    await noHorizontalScroll(page)
    expect(await outsideTheirCard(page), 'controls inside their card').toEqual([])
    if (info.project.name === 'phone') {
      expect(await smallTargets(page), 'touch targets at least 44 px').toEqual([])
    }
  })
}
