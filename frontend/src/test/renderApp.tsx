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

/** What every tab reads through the shell, and the Overview it opens on, from the sample-data fixtures. */
export const SHELL_ROUTES: Record<string, unknown> = {
  '/api/status': fixtures.statusOff,
  '/api/analytics/coverage': fixtures.coverage,
  '/api/accounts': fixtures.accounts,
  '/api/categories': fixtures.categories,
  '/api/analytics/months': fixtures.months,
  '/api/chat/usage': usageFixture,
  '/api/dashboard': fixtures.dashboardLatest,
  '/api/chat/conversations': fixtures.chatConversations,
}

let location = ''

function LocationProbe() {
  location = useLocation().pathname + useLocation().search
  return null
}

/** A write the app sent: method, URL and parsed JSON body. */
export interface Write {
  method: string
  url: string
  body: unknown
}

/** Answers a write; the default is a 404 problem, so a test notices a write it didn't expect. */
export type WriteHandler = (write: Write) => Response | undefined

/**
 * Renders the whole app at `path` with `fetch` answered from fixtures (`routes` on top of the shell's); writes (PUT,
 * POST, DELETE) go to `onWrite`. Returns the GETs and writes sent, and the current location.
 */
export function renderApp(path: string, routes: Record<string, unknown> = {}, onWrite?: WriteHandler) {
  const requests: string[] = []
  const writes: Write[] = []
  const answer = fetchFrom({ ...SHELL_ROUTES, ...routes })
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET'
      if (method === 'GET') {
        requests.push(input)
        return answer(input)
      }
      const write = { method, url: input, body: init?.body ? JSON.parse(String(init.body)) : undefined }
      writes.push(write)
      return (
        onWrite?.(write) ??
        Response.json({ type: 'not-found', detail: `no handler for ${method} ${input}` }, { status: 404 })
      )
    }),
  )
  const result = render(
    <MemoryRouter initialEntries={[path]}>
      <AppRoutes />
      <LocationProbe />
    </MemoryRouter>,
  )
  return { ...result, requests, writes, location: () => location }
}
