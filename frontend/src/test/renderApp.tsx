import { render } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { vi } from 'vitest'
import AppRoutes from '../routes'
import { fetchFrom, fixtures, selfHostFixture, usageFixture } from './fixtures'

export { usageFixture }

/** What every tab reads through the shell, and the Overview it opens on, from the sample-data fixtures. */
export const SHELL_ROUTES: Record<string, unknown> = {
  '/api/status': fixtures.statusOff,
  '/api/analytics/coverage': fixtures.coverage,
  '/api/accounts': fixtures.accounts,
  '/api/categories': fixtures.categories,
  '/api/analytics/months': fixtures.months,
  '/api/chat/usage': usageFixture,
  '/api/selfhost': selfHostFixture,
  '/api/dashboard': fixtures.dashboardLatest,
  '/api/chat/conversations': fixtures.chatConversations,
}

let location = ''

function LocationProbe() {
  location = useLocation().pathname + useLocation().search
  return null
}

/** A write the app sent: method, URL and parsed JSON body (a multipart body stays the `FormData` sent). */
export interface Write {
  method: string
  url: string
  body: unknown
}

/**
 * Answers a write, now or later (a promise lets a test see the page while a write is in flight); the default is a 404
 * problem, so a test notices a write it didn't expect.
 */
export type WriteHandler = (write: Write) => Response | undefined | Promise<Response | undefined>

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
      const body = init?.body
      const write = {
        method,
        url: input,
        body: body instanceof FormData ? body : body ? JSON.parse(String(body)) : undefined,
      }
      writes.push(write)
      return (
        (await onWrite?.(write)) ??
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
