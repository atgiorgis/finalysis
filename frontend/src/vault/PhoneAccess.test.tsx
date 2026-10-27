import { act, cleanup, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, test, vi } from 'vitest'
import type { AccessStatus, VaultStatus } from '../api'
import { fixtures } from '../test/fixtures'
import { renderApp } from '../test/renderApp'
import { STATUS_POLL_MS } from './VaultGate'

/**
 * The phone (docs/plans/phase-8.md §9, §10 "Login"): the login screen when there is no session, a session that ends
 * mid-use taking the app off the screen, the backend going quiet (a lock, seen from the phone) doing the same; and
 * Settings → Phone access on the computer, read-only on the phone, absent with the vault off.
 */

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

const UNLOCKED: VaultStatus = { ...fixtures.statusOff.vault, storage: 'VAULT', setUp: true, state: 'UNLOCKED' }
const LOGIN_REQUIRED = () =>
  Response.json({ type: 'urn:finalysis:problem:login-required', status: 401 }, { status: 401 })

const OFF: AccessStatus = {
  enabled: false,
  address: null,
  port: 8443,
  open: false,
  reason: null,
  url: null,
  caFingerprint: null,
  availableAddresses: ['192.168.1.157'],
  sessions: 0,
}

test('the phone without a session: the login screen, then the app on the page it asked for', async () => {
  const user = userEvent.setup()
  let loggedIn = false
  const { writes, location } = renderApp(
    '/alerts',
    {
      '/api/status': () => (loggedIn ? Response.json({ ...fixtures.statusOff, vault: UNLOCKED }) : LOGIN_REQUIRED()),
      '/api/alerts': fixtures.alertsAll,
    },
    (write) => {
      if ((write.body as { password: string }).password !== 'the vault password') {
        return Response.json({ type: 'urn:finalysis:problem:wrong-password', detail: 'The password is wrong.' }, { status: 401 })
      }
      loggedIn = true
      return new Response(null, { status: 204 })
    },
  )

  expect(await screen.findByRole('heading', { name: 'Log in to Finalysis' })).toBeInTheDocument()
  expect(screen.queryByRole('banner')).not.toBeInTheDocument()
  await user.type(screen.getByLabelText('Password'), 'a guess')
  await user.click(screen.getByRole('button', { name: 'Log in' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('The password is wrong.')
  expect(screen.getByLabelText('Password')).toHaveValue('')

  await user.type(screen.getByLabelText('Password'), 'the vault password')
  await user.click(screen.getByRole('button', { name: 'Log in' }))

  const bar = await screen.findByRole('banner')
  expect(within(bar).getByText('/ Alerts')).toBeInTheDocument()
  expect(location()).toBe('/alerts')
  expect(writes.map((w) => w.url)).toEqual(['/api/access/login', '/api/access/login'])
})

test('a session that ends mid-use takes the app off the screen and asks to log in again', async () => {
  let ended = false
  renderApp('/alerts', {
    '/api/status': { ...fixtures.statusOff, vault: UNLOCKED },
    '/api/alerts': () => (ended ? LOGIN_REQUIRED() : Response.json(fixtures.alertsAll)),
  })
  ended = true

  expect(await screen.findByRole('heading', { name: 'Log in to Finalysis' })).toBeInTheDocument()
  expect(screen.queryByRole('banner')).not.toBeInTheDocument()
})

test('the backend going quiet while open (a lock, seen from the phone) takes the app off the screen', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  let quiet = false
  renderApp('/overview', {
    '/api/status': () =>
      quiet ? Promise.reject(new TypeError('Failed to fetch')) : Response.json({ ...fixtures.statusOff, vault: UNLOCKED }),
  })
  expect(await screen.findByRole('banner')).toBeInTheDocument()

  quiet = true
  await act(async () => {
    await vi.advanceTimersByTimeAsync(STATUS_POLL_MS)
  })

  expect(await screen.findByRole('heading', { name: 'Finalysis isn’t answering' })).toBeInTheDocument()
  expect(screen.queryByRole('banner')).not.toBeInTheDocument()
  expect(screen.getByText(/unlock Finalysis on the computer/)).toBeInTheDocument()
})

test('Settings on the computer: turn phone access on; the address, the certificate, its fingerprint, the steps', async () => {
  const user = userEvent.setup()
  const fingerprint = Array.from({ length: 32 }, (_, i) => (i + 16).toString(16).toUpperCase()).join(':')
  const { writes } = renderApp(
    '/settings',
    {
      '/api/status': { ...fixtures.statusOff, vault: UNLOCKED },
      '/api/access/session': { authenticated: true, lan: false },
      '/api/access': OFF,
    },
    () =>
      Response.json({
        ...OFF,
        enabled: true,
        address: '192.168.1.157',
        open: true,
        url: 'https://192.168.1.157:8443',
        caFingerprint: fingerprint,
      }),
  )

  const card = await screen.findByRole('region', { name: 'Phone access' })
  const allow = await within(card).findByRole('checkbox', { name: 'Allow phones on this network' })
  expect(allow).not.toBeChecked()
  await user.click(allow)
  expect(within(card).getByLabelText('This computer’s address')).toHaveValue('192.168.1.157')
  await user.click(within(card).getByRole('button', { name: 'Save' }))

  expect(await within(card).findByRole('status')).toHaveTextContent('Phone access is on.')
  expect(writes).toEqual([
    { method: 'PUT', url: '/api/access', body: { enabled: true, address: '192.168.1.157', port: 8443 } },
  ])
  expect(card).toHaveTextContent('https://192.168.1.157:8443')
  expect(card).toHaveTextContent(fingerprint)
  expect(within(card).getByRole('link', { name: 'Save the certificate' })).toHaveAttribute('href', '/api/access/ca.cer')
  expect(card).toHaveTextContent('Certificate Trust Settings')
})

test('Settings checks the port before sending, and says why the listener isn’t open', async () => {
  const user = userEvent.setup()
  const { writes } = renderApp('/settings', {
    '/api/status': { ...fixtures.statusOff, vault: UNLOCKED },
    '/api/access/session': { authenticated: true, lan: false },
    '/api/access': { ...OFF, enabled: true, address: '192.168.1.157', reason: 'address-gone' },
  })

  const card = await screen.findByRole('region', { name: 'Phone access' })
  expect(await within(card).findByText(/isn’t on that address now/)).toBeInTheDocument()
  const port = within(card).getByLabelText('Port')
  await user.clear(port)
  await user.type(port, '80')
  await user.click(within(card).getByRole('button', { name: 'Save' }))

  expect(within(card).getByRole('alert')).toHaveTextContent('1024 to 65535')
  expect(writes).toHaveLength(0)
})

test('on the phone, Settings says phone access is set on the computer', async () => {
  renderApp('/settings', {
    '/api/status': { ...fixtures.statusOff, vault: UNLOCKED },
    '/api/access/session': { authenticated: true, lan: true },
  })

  const card = await screen.findByRole('region', { name: 'Phone access' })
  await waitFor(() => expect(card).toHaveTextContent('turned on and off on the computer'))
  expect(within(card).queryByRole('checkbox')).not.toBeInTheDocument()
})

test('with the vault off there is no phone access', async () => {
  renderApp('/settings')

  await screen.findByRole('heading', { level: 1, name: 'Settings' })
  expect(screen.queryByRole('region', { name: 'Phone access' })).not.toBeInTheDocument()
})
