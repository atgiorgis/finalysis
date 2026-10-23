import '@testing-library/jest-dom/vitest'
import { cleanup, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, test, vi } from 'vitest'
import type { Alert } from '../api'
import { fixtures } from '../test/fixtures'
import { renderApp } from '../test/renderApp'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

const MINUS = '−'
const ALERTS = fixtures.alertsAll
const [DINING, , , SPOTIFY, SAFEWAY] = ALERTS.items
const LIST = '/api/alerts?from=2026-07&to=2026-09&includeDismissed=false'

const ROUTES: Record<string, unknown> = {
  [LIST]: ALERTS,
  [`/api/transactions/${fixtures.transactionDetail.id}`]: fixtures.transactionDetail,
}

function card(name: string) {
  return screen.getByRole('article', { name })
}

function facts(el: HTMLElement): Record<string, string> {
  return Object.fromEntries(
    within(el)
      .getAllByRole('term')
      .map((dt) => [dt.textContent ?? '', dt.nextElementSibling?.textContent ?? '']),
  )
}

test('the three months to the month picked, counts by type, every alert with what it found', async () => {
  const { requests } = renderApp('/alerts?month=2026-09', ROUTES)

  expect(await screen.findByRole('article', { name: 'Dining' })).toBeInTheDocument()
  expect(requests).toContain(LIST)
  expect(screen.getByText('July 2026 to September 2026 · rule-based checks, no AI')).toBeInTheDocument()
  expect(screen.getByRole('list', { name: 'Alerts by type' })).toHaveTextContent(
    'Spending spike: 2Large transaction: 1Possible duplicate: 1Price increase: 1',
  )
  expect(screen.getAllByRole('article').map((a) => a.querySelector('h2')?.textContent)).toEqual([
    'Dining',
    'Shopping',
    'BEST BUY',
    'SPOTIFY USA',
    'SAFEWAY',
  ])
  expect(card('Dining')).toHaveTextContent('Spending spike · August 2026')
  expect(card('Dining')).toHaveTextContent('$340.63 spent, $132.84 (+63.9%) above the 3-month average of $207.79.')
  expect(card('BEST BUY')).toHaveTextContent(
    'BEST BUY $899.99 on Aug 22: 13.55× the median charge of $66.43 over 15 earlier charges.',
  )
  expect(card('SPOTIFY USA')).toHaveTextContent('From $11.99 to $12.99: +$1.00 (+8.3%).')
  expect(card('SAFEWAY')).toHaveTextContent('2 charges of $84.12 (same merchant, amount and day).')
})

test('each basis: observed, baseline, threshold, period and the settings applied, as returned', async () => {
  renderApp('/alerts?month=2026-09', ROUTES)

  await screen.findByRole('article', { name: 'Dining' })
  expect(facts(card('Dining'))).toEqual({
    Observed: '$340.63',
    Baseline: '$207.79',
    Threshold: '$311.68 (it fires at this or more)',
    Period: 'May 1, 2026 to Jul 31, 2026',
    'Minimum increase': '50.0%',
    'Minimum increase in dollars': '$100.00',
    'Complete months the average needs': '3',
  })
  expect(facts(card('SAFEWAY'))).toEqual({
    Observed: '2',
    Threshold: '2 (it fires at this or more)',
    Period: 'Jun 13, 2026',
    Within: '1 day',
  })
  expect(facts(card('BEST BUY'))).toMatchObject({ 'Times the median': '3.00×', 'History looked at': '180 days' })
})

test('the transactions an alert is about open their sheet; its months’ flags are named by account', async () => {
  const user = userEvent.setup()
  const { location } = renderApp('/alerts?month=2026-09', ROUTES)

  const dining = await screen.findByRole('article', { name: 'Dining' })
  expect(dining.querySelectorAll('.txn-row')).toHaveLength(DINING.txns.length)
  expect(dining.querySelectorAll('.txn-row')[0]).toHaveTextContent(`Aug 22NAKAMURA RAMEN${MINUS}$43.88`)
  expect(dining).toHaveTextContent(
    'Its figures use months with a flag, so they may be off:Northfield Bank Checking: a statement didn’t reconcile (reviewed)',
  )
  await user.click(within(card('SAFEWAY')).getAllByRole('button', { name: /SAFEWAY/ })[0])
  expect(location()).toBe('/alerts?month=2026-09&txn=94')
})

test('dismiss with a note: the request, then the list read again', async () => {
  const user = userEvent.setup()
  const { writes, requests } = renderApp('/alerts?month=2026-09', ROUTES, (w) =>
    w.method === 'PUT' ? Response.json({ ...SAFEWAY, dismissed: true, note: 'Two real trips' }) : undefined,
  )
  const safeway = await screen.findByRole('article', { name: 'SAFEWAY' })
  const before = requests.filter((r) => r === LIST).length

  await user.type(within(safeway).getByRole('textbox', { name: 'Note (optional)' }), 'Two real trips')
  await user.click(within(safeway).getByRole('button', { name: 'Dismiss' }))

  expect(writes).toEqual([
    { method: 'PUT', url: `/api/alerts/${encodeURIComponent(SAFEWAY.key)}/dismissal`, body: { note: 'Two real trips' } },
  ])
  await vi.waitFor(() => expect(requests.filter((r) => r === LIST).length).toBeGreaterThan(before))
})

test('dismiss without a note sends an empty body', async () => {
  const user = userEvent.setup()
  const { writes } = renderApp('/alerts?month=2026-09', ROUTES, (w) =>
    w.method === 'PUT' ? Response.json({ ...SAFEWAY, dismissed: true }) : undefined,
  )

  await user.click(within(await screen.findByRole('article', { name: 'SAFEWAY' })).getByRole('button', { name: 'Dismiss' }))

  expect(writes[0].body).toEqual({})
})

test('showing dismissed alerts asks for them; a dismissed one shows when and why, and restores', async () => {
  const user = userEvent.setup()
  const dismissed: Alert = { ...SAFEWAY, dismissed: true, dismissedAt: '2026-10-20T09:00:00Z', note: 'Two real trips' }
  const { writes, requests, location } = renderApp('/alerts?month=2026-09', {
    ...ROUTES,
    '/api/alerts?from=2026-07&to=2026-09&includeDismissed=true': { ...ALERTS, items: [dismissed] },
  }, (w) => (w.method === 'DELETE' ? new Response(null, { status: 204 }) : undefined))

  await user.click(await screen.findByRole('checkbox', { name: 'Show dismissed alerts' }))

  expect(location()).toBe('/alerts?month=2026-09&dismissed=show')
  expect(requests).toContain('/api/alerts?from=2026-07&to=2026-09&includeDismissed=true')
  const safeway = await screen.findByRole('article', { name: 'SAFEWAY' })
  expect(safeway).toHaveTextContent('Possible duplicate · June 2026 · dismissed')
  expect(safeway).toHaveTextContent('Dismissed on Oct 20, 2026: “Two real trips”')
  await user.click(within(safeway).getByRole('button', { name: 'Restore' }))
  expect(writes).toEqual([
    { method: 'DELETE', url: `/api/alerts/${encodeURIComponent(SAFEWAY.key)}/dismissal`, body: undefined },
  ])
})

test('a type filter asks for that type only', async () => {
  const user = userEvent.setup()
  const { requests } = renderApp('/alerts?month=2026-09', ROUTES)

  await user.selectOptions(await screen.findByRole('combobox', { name: 'Type' }), 'Price increase')

  expect(requests).toContain('/api/alerts?from=2026-07&to=2026-09&type=RECURRING_PRICE_INCREASE&includeDismissed=false')
})

test('?key= focuses that alert in the list', async () => {
  renderApp(`/alerts?month=2026-09&key=${encodeURIComponent(SPOTIFY.key)}`, ROUTES)

  const spotify = await screen.findByRole('article', { name: 'SPOTIFY USA' })
  expect(spotify).toHaveClass('alert-focused')
  expect(spotify).toHaveFocus()
})

test('?key= outside the list’s months is read on its own; a key that no longer matches is said plainly', async () => {
  const outside = { ...SPOTIFY, key: 'recurring-price-increase.2026-03.aaaaaaaaaaaaaaaa', month: '2026-03' }
  const { requests } = renderApp(`/alerts?month=2026-09&key=${encodeURIComponent(outside.key)}`, {
    ...ROUTES,
    [LIST]: { ...ALERTS, items: [DINING] },
    [`/api/alerts/${encodeURIComponent(outside.key)}`]: outside,
  })

  expect(await screen.findByText('Price increase · March 2026')).toBeInTheDocument()
  expect(requests).toContain(`/api/alerts/${encodeURIComponent(outside.key)}`)
  cleanup()

  renderApp('/alerts?month=2026-09&key=gone.2026-01.0000000000000000', { ...ROUTES, [LIST]: { ...ALERTS, items: [DINING] } })
  expect(await screen.findByText('This alert no longer applies.')).toBeInTheDocument()
})

test('a refused dismissal shows the API’s reason', async () => {
  const user = userEvent.setup()
  renderApp('/alerts?month=2026-09', ROUTES, (w) =>
    w.method === 'PUT'
      ? Response.json({ type: 'urn:finalysis:problem:not-found', detail: 'No current alert has this key.' }, { status: 404 })
      : undefined,
  )

  const safeway = await screen.findByRole('article', { name: 'SAFEWAY' })
  await user.click(within(safeway).getByRole('button', { name: 'Dismiss' }))

  expect(await within(safeway).findByRole('alert')).toHaveTextContent('No current alert has this key.')
})
