import { expect, test, type Page } from '@playwright/test'
import { fixtures } from '../src/test/fixtures'
import { mockApi } from './mockApi'

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
  { name: 'settings', path: '/settings', ready: 'Chat costs' },
]

/** Interactive elements under 44 px tall that aren't inline links in running text (WCAG 2.5.8's inline exception). */
async function smallTargets(page: Page): Promise<string[]> {
  return page.evaluate((min) => {
    const out: string[] = []
    for (const el of document.querySelectorAll<HTMLElement>('a[href], button, select, input, textarea, [role="button"]')) {
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
    document.querySelector('main')?.append(small, p)
  })
  expect(await smallTargets(page)).toEqual(['button "tiny" 20px'])
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
