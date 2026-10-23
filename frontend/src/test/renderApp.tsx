import { render } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { vi } from 'vitest'
import AppRoutes from '../routes'
import { fetchFrom, fixtures } from './fixtures'

/** `GET /api/chat/usage` with nothing spent and the default caps (hand-written: the capture runs before step 1). */
export const usageFixture = {
  day: '2026-10-23',
  spentToday: '0.000000',
  maxTurnCost: '0.15',
  maxDailyCost: '1.00',
  resetsAt: '2026-10-24T00:00:00-04:00',
}

/** What every tab reads through the shell, from the sample-data fixtures. */
export const SHELL_ROUTES: Record<string, unknown> = {
  '/api/status': fixtures.statusOff,
  '/api/analytics/coverage': fixtures.coverage,
  '/api/accounts': fixtures.accounts,
  '/api/analytics/months': fixtures.months,
  '/api/chat/usage': usageFixture,
}

let location = ''

function LocationProbe() {
  location = useLocation().pathname + useLocation().search
  return null
}

/**
 * Renders the whole app at `path` with `fetch` answered from fixtures (`routes` on top of the shell's). Returns the
 * URLs requested and the current location.
 */
export function renderApp(path: string, routes: Record<string, unknown> = {}) {
  const requests: string[] = []
  const answer = fetchFrom({ ...SHELL_ROUTES, ...routes })
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: string) => {
      requests.push(input)
      return answer(input)
    }),
  )
  const result = render(
    <MemoryRouter initialEntries={[path]}>
      <AppRoutes />
      <LocationProbe />
    </MemoryRouter>,
  )
  return { ...result, requests, location: () => location }
}
