import '@testing-library/jest-dom/vitest'
import { cleanup, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, test, vi } from 'vitest'
import { fixtures } from '../test/fixtures'
import { renderApp } from '../test/renderApp'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

const ALL = '/api/analytics/recurring?status=ALL&limit=100'
const ROUTES: Record<string, unknown> = { [ALL]: fixtures.recurringAll }

function facts(el: HTMLElement): Record<string, string> {
  return Object.fromEntries(
    within(el)
      .getAllByRole('term')
      .map((dt) => [dt.textContent ?? '', dt.nextElementSibling?.textContent ?? '']),
  )
}

const series = (name: string) =>
  screen.getAllByRole('listitem').find((li) => li.querySelector('h2')?.textContent === name) as HTMLElement

test('every series with its status, account, amounts, next date and charges', async () => {
  const { requests } = renderApp('/recurring', ROUTES)

  const list = await screen.findByRole('list', { name: 'Recurring charges' })
  expect(requests).toContain(ALL)
  expect(within(list).getAllByRole('heading', { level: 2 }).map((h) => h.textContent)).toEqual(
    fixtures.recurringAll.items.map((r) => r.merchant),
  )
  const spotify = series('SPOTIFY USA')
  expect(spotify).toHaveTextContent('Active')
  expect(spotify).toHaveTextContent('Apex Visa (travel card) · Subscriptions · monthly')
  expect(facts(spotify)).toEqual({
    Typical: '$11.99',
    Last: '$12.99 on Sep 12, 2026',
    'Next expected': 'Oct 12, 2026 (a projection)',
    Charges: '6 since Apr 12, 2026',
  })
  const power = series('METRO POWER CO ONLINE PMT')
  expect(facts(power).Last).toBe('$114.64 on Sep 18, 2026 · the amount varies')
})

test('an ended series says when it ended, not a next date', async () => {
  renderApp('/recurring', ROUTES)

  await screen.findByRole('list', { name: 'Recurring charges' })
  const skywave = series('SKYWAVE INTERNET AUTOPAY')
  expect(skywave).toHaveTextContent('Ended')
  expect(facts(skywave)['Ended after']).toBe('Aug 12, 2026')
  expect(facts(skywave)).not.toHaveProperty('Next expected')
})

test('a series opens its charges: the drill-down built from its own account, merchant and months', async () => {
  const user = userEvent.setup()
  const { location } = renderApp('/recurring', {
    ...ROUTES,
    '/api/analytics/transactions': fixtures.drillDownOtherMember,
  })

  await screen.findByRole('list', { name: 'Recurring charges' })
  await user.click(within(series('SPOTIFY USA')).getByRole('link', { name: 'See its charges' }))

  expect(location()).toBe(
    '/drill?from=2026-04&to=2026-09&measure=SPENT&accountId=3&merchant=SPOTIFY+USA&label=SPOTIFY+USA%3A+its+charges',
  )
  expect(await screen.findByRole('heading', { level: 1, name: 'SPOTIFY USA: its charges' })).toBeInTheDocument()
})

test('the status filter asks the endpoint for that status', async () => {
  const user = userEvent.setup()
  const { requests, location } = renderApp('/recurring', ROUTES)

  await user.click(await screen.findByRole('button', { name: 'Active' }))

  expect(location()).toBe('/recurring?status=ACTIVE')
  expect(requests).toContain('/api/analytics/recurring?status=ACTIVE&limit=100')
})

test('?series= marks that series', async () => {
  renderApp('/recurring?series=3%3ASPOTIFY%20USA', ROUTES)

  await screen.findByRole('list', { name: 'Recurring charges' })
  expect(series('SPOTIFY USA')).toHaveAttribute('aria-current', 'true')
  expect(series('NETFLIX.COM')).not.toHaveAttribute('aria-current')
})

test('a failed read is said plainly', async () => {
  renderApp('/recurring', { [ALL]: undefined })

  expect(await screen.findByText(/Couldn’t load the recurring charges/)).toBeInTheDocument()
})
