import { act, cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, test, vi } from 'vitest'
import type { VaultState, VaultStatus } from '../api'
import { fixtures } from '../test/fixtures'
import { renderApp, type Write } from '../test/renderApp'
import { ACTIVITY_INTERVAL_MS, STATUS_POLL_MS } from './VaultGate'
import { RECOVERY_WARNING } from './vaultText'

/**
 * The vault screens (docs/plans/phase-8.md §6, §10 "Screens"): setup with the recovery key, unlock, recovery, any 423
 * switching to the unlock screen and dropping the app, back on the same route after unlock, the Lock button, the
 * status poll and the activity signal, and the message screens.
 */

const KEY = 'ABCD-EFGH-JKMN-PQRS-TVWX-YZ01-2345-6789-ABCD-EFGH-JKMN-PQRS-TV0G'

/** A status the test changes as the backend would; `/api/status` answers whatever it holds. */
function vaultBackend(state: VaultState, extra: Partial<VaultStatus> = {}) {
  let vault: VaultStatus = {
    ...fixtures.statusOff.vault,
    storage: 'VAULT',
    setUp: state !== 'NOT_SET_UP',
    state,
    ...extra,
  }
  return {
    set(next: VaultState, more: Partial<VaultStatus> = {}) {
      vault = { ...vault, state: next, ...more }
    },
    route: () => Response.json({ ...fixtures.statusOff, vault }),
  }
}

function problem(status: number, slug: string, detail: string, headers: Record<string, string> = {}) {
  return Response.json({ type: `urn:finalysis:problem:${slug}`, detail, status }, { status, headers })
}

const noContent = () => new Response(null, { status: 204 })

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

// ---- setup and the recovery key -------------------------------------------------------------------------------------

test('setup: two sentences, the password twice, then the key once; the app opens only after the last group', async () => {
  const user = userEvent.setup()
  const backend = vaultBackend('NOT_SET_UP')
  const { writes } = renderApp('/overview', { '/api/status': backend.route }, (write) => {
    if (write.url === '/api/vault/setup') {
      backend.set('UNLOCKED', { setUp: true })
      return Response.json({ recoveryKey: KEY }, { status: 201 })
    }
  })

  expect(await screen.findByRole('heading', { name: 'Set up your vault' })).toBeInTheDocument()
  expect(screen.getByText(/encrypted vault on this computer/)).toBeInTheDocument()
  expect(screen.queryByRole('banner')).not.toBeInTheDocument()

  await user.type(screen.getByLabelText('Password'), 'short one')
  await user.type(screen.getByLabelText('Password again'), 'short one')
  await user.click(screen.getByRole('button', { name: 'Create the vault' }))
  expect(screen.getByRole('alert')).toHaveTextContent('at least 12 characters')

  await user.clear(screen.getByLabelText('Password'))
  await user.clear(screen.getByLabelText('Password again'))
  await user.type(screen.getByLabelText('Password'), 'river stone lamp orbit')
  await user.type(screen.getByLabelText('Password again'), 'river stone lamp orbiT')
  await user.click(screen.getByRole('button', { name: 'Create the vault' }))
  expect(screen.getByRole('alert')).toHaveTextContent('The two passwords are different.')
  expect(writes).toHaveLength(0)

  await user.clear(screen.getByLabelText('Password again'))
  await user.type(screen.getByLabelText('Password again'), 'river stone lamp orbit')
  await user.click(screen.getByRole('button', { name: 'Create the vault' }))

  expect(await screen.findByRole('heading', { name: 'Your recovery key' })).toBeInTheDocument()
  expect(writes).toEqual([{ method: 'POST', url: '/api/vault/setup', body: { password: 'river stone lamp orbit' } }])
  expect(screen.getByRole('note')).toHaveTextContent(RECOVERY_WARNING)
  const groups = within(screen.getByRole('list', { name: 'Recovery key' })).getAllByRole('listitem')
  expect(groups.map((g) => g.textContent)).toEqual(KEY.split('-'))
  const go = screen.getByRole('button', { name: 'I’ve saved it. Continue' })
  expect(go).toBeDisabled()
  expect(screen.queryByRole('banner')).not.toBeInTheDocument()

  await user.type(screen.getByLabelText('Type the key’s last group to continue'), 'PQRS')
  expect(go).toBeDisabled()
  await user.clear(screen.getByLabelText('Type the key’s last group to continue'))
  await user.type(screen.getByLabelText('Type the key’s last group to continue'), 'tv0g')
  expect(go).toBeEnabled()
  await user.click(go)

  expect(await screen.findByRole('banner')).toBeInTheDocument()
  expect(screen.queryByText('TV0G')).not.toBeInTheDocument()
})

test('setup shows the backend’s refusal in its own words', async () => {
  const user = userEvent.setup()
  renderApp('/overview', { '/api/status': vaultBackend('NOT_SET_UP').route }, () =>
    problem(422, 'password-rejected', 'That password is built from one of the most common ones. Choose another.'),
  )

  await user.type(await screen.findByLabelText('Password'), 'passwordpassword')
  await user.type(screen.getByLabelText('Password again'), 'passwordpassword')
  await user.click(screen.getByRole('button', { name: 'Create the vault' }))

  expect(await screen.findByRole('alert')).toHaveTextContent('most common ones')
  expect(screen.getByRole('button', { name: 'Create the vault' })).toBeEnabled()
})

// ---- unlock ---------------------------------------------------------------------------------------------------------

test('unlock: a wrong password says so and clears the field; the right one opens the app on the same route', async () => {
  const user = userEvent.setup()
  const backend = vaultBackend('LOCKED')
  const { writes, location } = renderApp('/alerts', { '/api/status': backend.route, '/api/alerts': fixtures.alertsAll }, (write) => {
    const { password } = write.body as { password: string }
    if (password !== 'the right password') {
      return problem(401, 'wrong-password', 'The password is wrong.')
    }
    backend.set('UNLOCKED')
    return noContent()
  })

  expect(await screen.findByRole('heading', { name: 'Finalysis is locked' })).toBeInTheDocument()
  await user.type(screen.getByLabelText('Password'), 'not it')
  await user.click(screen.getByRole('button', { name: 'Unlock' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('The password is wrong.')
  expect(screen.getByLabelText('Password')).toHaveValue('')

  await user.type(screen.getByLabelText('Password'), 'the right password')
  await user.click(screen.getByRole('button', { name: 'Unlock' }))

  const bar = await screen.findByRole('banner')
  expect(within(bar).getByText('/ Alerts')).toBeInTheDocument()
  expect(location()).toBe('/alerts')
  expect(writes.map((w) => w.url)).toEqual(['/api/vault/unlock', '/api/vault/unlock'])
})

test('after too many attempts the wait is shown as a time', async () => {
  const user = userEvent.setup()
  renderApp('/overview', { '/api/status': vaultBackend('LOCKED').route }, () =>
    problem(429, 'too-many-attempts', 'Too many wrong attempts. Wait 30 seconds and try again.', { 'Retry-After': '30' }),
  )

  await user.type(await screen.findByLabelText('Password'), 'whatever it is')
  await user.click(screen.getByRole('button', { name: 'Unlock' }))

  const alert = await screen.findByRole('alert')
  expect(alert).toHaveTextContent('Wait 30 seconds')
  expect(alert).toHaveTextContent(/You can try again at \d{1,2}:\d{2}/)
})

test('recovery: the key and a new password twice unlock the app', async () => {
  const user = userEvent.setup()
  const backend = vaultBackend('LOCKED')
  const { writes } = renderApp('/overview', { '/api/status': backend.route }, () => {
    backend.set('UNLOCKED')
    return noContent()
  })

  await user.click(await screen.findByRole('button', { name: 'Forgot your password? Use your recovery key' }))
  expect(screen.getByRole('heading', { name: 'Unlock with your recovery key' })).toBeInTheDocument()
  await user.type(screen.getByLabelText('Recovery key'), KEY.toLowerCase())
  await user.type(screen.getByLabelText('New password'), 'a brand new passphrase')
  await user.type(screen.getByLabelText('New password again'), 'a brand new passphrase')
  await user.click(screen.getByRole('button', { name: 'Unlock and set the new password' }))

  expect(await screen.findByRole('banner')).toBeInTheDocument()
  expect(writes).toEqual([
    {
      method: 'POST',
      url: '/api/vault/recover',
      body: { recoveryKey: KEY.toLowerCase(), newPassword: 'a brand new passphrase' },
    },
  ])
})

// ---- 423 anywhere, the Lock button, the poll ------------------------------------------------------------------------

test('a 423 from any read switches to the unlock screen, drops the app, and unlock returns to the same page', async () => {
  const user = userEvent.setup()
  const backend = vaultBackend('UNLOCKED')
  let locked = false
  const { requests, location } = renderApp(
    '/alerts?month=2026-09',
    {
      '/api/status': backend.route,
      '/api/alerts': () => {
        if (locked) {
          backend.set('LOCKED')
          return Response.json({ type: 'urn:finalysis:problem:vault-locked', status: 423 }, { status: 423 })
        }
        return Response.json(fixtures.alertsAll)
      },
    },
    () => {
      locked = false
      backend.set('UNLOCKED')
      return noContent()
    },
  )
  // The vault locked (idle, elsewhere) before the Alerts tab read its list.
  locked = true
  expect(await screen.findByRole('heading', { name: 'Finalysis is locked' })).toBeInTheDocument()
  expect(screen.queryByRole('banner')).not.toBeInTheDocument()
  const accountReads = requests.filter((r) => r === '/api/accounts').length

  await user.type(screen.getByLabelText('Password'), 'the password, typed')
  await user.click(screen.getByRole('button', { name: 'Unlock' }))

  expect(await screen.findByRole('banner')).toBeInTheDocument()
  expect(location()).toBe('/alerts?month=2026-09')
  // The app mounted afresh: what it shows was read again, nothing kept from before the lock.
  await waitFor(() => expect(requests.filter((r) => r === '/api/accounts').length).toBe(accountReads + 1))
})

test('the Lock button locks, shows the unlock screen and comes back to the same page', async () => {
  const user = userEvent.setup()
  const backend = vaultBackend('UNLOCKED')
  const { writes, location } = renderApp('/settings', { '/api/status': backend.route }, (write: Write) => {
    if (write.url === '/api/vault/lock') {
      backend.set('LOCKED')
    } else {
      backend.set('UNLOCKED')
    }
    return noContent()
  })

  const bar = await screen.findByRole('banner')
  await user.click(within(bar).getByRole('button', { name: 'Lock' }))

  expect(await screen.findByRole('heading', { name: 'Finalysis is locked' })).toBeInTheDocument()
  expect(writes.map((w) => w.url)).toEqual(['/api/vault/lock'])
  await user.type(screen.getByLabelText('Password'), 'unlock again please')
  await user.click(screen.getByRole('button', { name: 'Unlock' }))
  expect(await screen.findByRole('heading', { name: 'Settings', level: 1 })).toBeInTheDocument()
  expect(location()).toBe('/settings')
})

test('the menu has Lock now too', async () => {
  const user = userEvent.setup()
  const backend = vaultBackend('UNLOCKED')
  const { writes } = renderApp('/overview', { '/api/status': backend.route }, () => {
    backend.set('LOCKED')
    return noContent()
  })

  await user.click(await screen.findByRole('button', { name: 'Open menu' }))
  await user.click(within(screen.getByRole('dialog', { name: 'Menu' })).getByRole('button', { name: 'Lock now' }))

  expect(await screen.findByRole('heading', { name: 'Finalysis is locked' })).toBeInTheDocument()
  expect(writes.map((w) => w.url)).toEqual(['/api/vault/lock'])
})

test('with the vault off (the plain dev database) there is nothing to lock', async () => {
  renderApp('/overview')

  const bar = await screen.findByRole('banner')
  expect(within(bar).queryByRole('button', { name: 'Lock' })).not.toBeInTheDocument()
})

test('while open, the status is read every 30 s, so an idle lock clears the screen without a click', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  const backend = vaultBackend('UNLOCKED')
  renderApp('/overview', { '/api/status': backend.route })
  expect(await screen.findByRole('banner')).toBeInTheDocument()

  backend.set('LOCKED')
  await act(async () => {
    await vi.advanceTimersByTimeAsync(STATUS_POLL_MS)
  })

  expect(await screen.findByRole('heading', { name: 'Finalysis is locked' })).toBeInTheDocument()
  expect(screen.queryByRole('banner')).not.toBeInTheDocument()
})

test('the activity signal: at most once a minute, and only when the user does something', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  const { writes } = renderApp('/overview', { '/api/status': vaultBackend('UNLOCKED').route }, () => noContent())
  expect(await screen.findByRole('banner')).toBeInTheDocument()
  const activity = () => writes.filter((w) => w.url === '/api/vault/activity').length

  fireEvent.pointerDown(window)
  expect(activity()).toBe(0)

  await act(async () => {
    await vi.advanceTimersByTimeAsync(ACTIVITY_INTERVAL_MS)
  })
  expect(activity()).toBe(0)
  fireEvent.keyDown(window, { key: 'a' })
  fireEvent.pointerMove(window)
  await waitFor(() => expect(activity()).toBe(1))
  fireEvent.pointerDown(window)
  expect(activity()).toBe(1)
})

// ---- not ready, broken, lock failed ---------------------------------------------------------------------------------

test('not ready: the reason above the unlock form, which checks again when sent', async () => {
  renderApp('/overview', {
    '/api/status': vaultBackend('NOT_READY', {
      setUp: true,
      reason: 'pgvector-wrong-version',
      message: 'Finalysis needs pgvector 0.8.6; the installed one is 0.8.7. See the README’s setup section.',
    }).route,
  })

  expect(await screen.findByRole('heading', { name: 'Finalysis is locked' })).toBeInTheDocument()
  expect(screen.getByRole('alert')).toHaveTextContent('needs pgvector 0.8.6; the installed one is 0.8.7')
})

test('not ready before setup: the reason above the setup form', async () => {
  renderApp('/overview', {
    '/api/status': vaultBackend('NOT_READY', {
      setUp: false,
      reason: 'postgres-missing',
      message: 'Finalysis needs PostgreSQL 17 from Postgres.app, and none was found.',
    }).route,
  })

  expect(await screen.findByRole('heading', { name: 'Set up your vault' })).toBeInTheDocument()
  expect(screen.getByRole('alert')).toHaveTextContent('needs PostgreSQL 17')
})

test('broken: a plain message and Check again, never a repair', async () => {
  const user = userEvent.setup()
  const { requests } = renderApp('/overview', {
    '/api/status': vaultBackend('BROKEN', {
      reason: 'image-missing',
      message: 'The vault’s key file is there but its disk image is missing.',
    }).route,
  })

  expect(await screen.findByRole('heading', { name: 'The vault needs attention' })).toBeInTheDocument()
  expect(screen.getByRole('alert')).toHaveTextContent('disk image is missing')
  const reads = requests.filter((r) => r === '/api/status').length
  await user.click(screen.getByRole('button', { name: 'Check again' }))
  await waitFor(() => expect(requests.filter((r) => r === '/api/status').length).toBe(reads + 1))
})

test('lock failed: nothing shown, and Lock again tries the lock', async () => {
  const user = userEvent.setup()
  const backend = vaultBackend('LOCK_FAILED', { reason: 'unmount', message: 'Locking did not finish (unmount).' })
  const { writes } = renderApp('/overview', { '/api/status': backend.route }, () => {
    backend.set('LOCKED', { reason: null, message: null })
    return noContent()
  })

  expect(await screen.findByRole('heading', { name: 'Locking didn’t finish' })).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'Lock again' }))

  expect(await screen.findByRole('heading', { name: 'Finalysis is locked' })).toBeInTheDocument()
  expect(writes.map((w) => w.url)).toEqual(['/api/vault/lock'])
})

test('a backend that doesn’t answer at all', async () => {
  renderApp('/overview', { '/api/status': () => Promise.reject(new TypeError('Failed to fetch')) })

  expect(await screen.findByRole('heading', { name: 'Finalysis isn’t answering' })).toBeInTheDocument()
  expect(screen.getByRole('alert')).toHaveTextContent('Failed to fetch')
})
