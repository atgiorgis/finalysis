import { expect, test, type Page } from '@playwright/test'
import { fixtures } from '../src/test/fixtures'
import { mockApi } from './mockApi'

/**
 * UI-07 (docs/plans/phase-7.md §7): every screen fits a 375 px phone with no horizontal scroll, its touch targets are
 * at least 44 px tall, and the phone shows its bottom bar instead of the header tabs; on desktop, the reverse.
 */

const MIN_TARGET = 44
const GROCERIES = fixtures.detailsCategory.category?.id
const CLOUD = {
  ...fixtures.statusOff,
  ai: { mode: 'CLOUD', provider: 'bedrock', models: { main: 'm', categorization: 'c', embedding: 'e' } },
}

/** Every screen, with the state that makes it widest: open panels, a sheet, long text. */
const SCREENS: { name: string; path: string; ready: string; overrides?: Record<string, unknown> }[] = [
  { name: 'overview', path: '/overview?month=2026-09', ready: 'Where the money went · September 2026' },
  {
    name: 'overview, cloud banner',
    path: '/overview?month=2026-09',
    ready: 'Where the money went · September 2026',
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
    await expect(page.getByRole('navigation', { name: 'Main (phone)' })).toBeVisible({ visible: phone })
    await expect(page.getByRole('navigation', { name: 'Main', exact: true })).toBeVisible({ visible: !phone })

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

test('the phone bar’s More opens the other tabs, each reachable', async ({ page }, info) => {
  test.skip(info.project.name !== 'phone', 'phone only')
  await mockApi(page)
  await page.goto('/settings')
  const bar = page.getByRole('navigation', { name: 'Main (phone)' })
  await bar.getByRole('button', { name: 'More' }).click()
  await bar.getByRole('link', { name: 'Recurring' }).click()
  await expect(page.getByRole('heading', { level: 1, name: 'Recurring charges' })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(375)
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
