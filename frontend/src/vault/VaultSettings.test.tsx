import { cleanup, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, test, vi } from 'vitest'
import type { VaultStatus } from '../api'
import { fixtures } from '../test/fixtures'
import { renderApp } from '../test/renderApp'

/** Settings → Vault (docs/plans/phase-8.md §6): auto-lock minutes, change password, Lock now; nothing in plain mode. */

function status(extra: Partial<VaultStatus> = {}) {
  return {
    ...fixtures.statusOff,
    vault: { ...fixtures.statusOff.vault, storage: 'VAULT', setUp: true, state: 'UNLOCKED', ...extra },
  }
}

const noContent = () => new Response(null, { status: 204 })

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

async function vaultSection() {
  return screen.findByRole('region', { name: 'Vault' })
}

test('auto-lock: saved with PUT, checked for 1 to 240 before sending', async () => {
  const user = userEvent.setup()
  const { writes } = renderApp('/settings', { '/api/status': status({ idleLockMinutes: 15 }) }, () => noContent())

  const section = await vaultSection()
  const minutes = within(section).getByLabelText('Lock after this many idle minutes')
  expect(minutes).toHaveValue(15)

  await user.clear(minutes)
  await user.type(minutes, '0')
  await user.click(within(section).getAllByRole('button', { name: 'Save' })[0])
  expect(within(section).getByRole('alert')).toHaveTextContent('between 1 and 240')
  expect(writes).toHaveLength(0)

  await user.clear(minutes)
  await user.type(minutes, '5')
  await user.click(within(section).getByRole('button', { name: 'Save' }))
  expect(await within(section).findByRole('status')).toHaveTextContent('locks after 5 idle minutes')
  expect(writes).toEqual([{ method: 'PUT', url: '/api/vault/settings', body: { idleLockMinutes: 5 } }])
})

test('change password: the new one twice, the backend’s refusal in its words, then done', async () => {
  const user = userEvent.setup()
  let calls = 0
  const { writes } = renderApp('/settings', { '/api/status': status() }, () => {
    calls++
    return calls === 1
      ? Response.json(
          {
            type: 'urn:finalysis:problem:password-rejected',
            detail: 'That password contains the name of one of your accounts or banks. Choose another.',
          },
          { status: 422 },
        )
      : noContent()
  })

  const section = await vaultSection()
  await user.type(within(section).getByLabelText('Current password'), 'the current password')
  await user.type(within(section).getByLabelText('New password'), 'my apex visa password')
  await user.type(within(section).getByLabelText('New password again'), 'my apex visa passworD')
  await user.click(within(section).getByRole('button', { name: 'Change password' }))
  expect(within(section).getByRole('alert')).toHaveTextContent('The two passwords are different.')
  expect(writes).toHaveLength(0)

  await user.clear(within(section).getByLabelText('New password again'))
  await user.type(within(section).getByLabelText('New password again'), 'my apex visa password')
  await user.click(within(section).getByRole('button', { name: 'Change password' }))
  expect(await within(section).findByRole('alert')).toHaveTextContent('accounts or banks')

  await user.clear(within(section).getByLabelText('New password'))
  await user.clear(within(section).getByLabelText('New password again'))
  await user.type(within(section).getByLabelText('New password'), 'quiet harbor lantern moss')
  await user.type(within(section).getByLabelText('New password again'), 'quiet harbor lantern moss')
  await user.click(within(section).getByRole('button', { name: 'Change password' }))

  expect(await within(section).findByRole('status')).toHaveTextContent('Password changed.')
  expect(within(section).getByLabelText('Current password')).toHaveValue('')
  expect(writes.at(-1)).toEqual({
    method: 'POST',
    url: '/api/vault/password',
    body: { currentPassword: 'the current password', newPassword: 'quiet harbor lantern moss' },
  })
})

test('Lock now locks', async () => {
  const user = userEvent.setup()
  const { writes } = renderApp('/settings', { '/api/status': status() }, () => noContent())

  const section = await vaultSection()
  await user.click(within(section).getByRole('button', { name: 'Lock now' }))

  expect(writes.map((w) => w.url)).toEqual(['/api/vault/lock'])
})

test('a changed collation version shows the step to take', async () => {
  renderApp('/settings', {
    '/api/status': status({
      collation: { recorded: '153.120', actual: '153.121', action: 'Run scripts/vault/db-refresh-collation.sh.' },
    }),
  })

  expect(within(await vaultSection()).getByRole('note')).toHaveTextContent('db-refresh-collation.sh')
})

test('with the vault off, Settings says the data is in the plain dev database and offers nothing', async () => {
  renderApp('/settings')

  const section = await screen.findByRole('region', { name: 'Data' })
  expect(section).toHaveTextContent('vault is off')
  expect(within(section).queryByRole('button')).not.toBeInTheDocument()
})
