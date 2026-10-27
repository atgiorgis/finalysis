import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, test, vi } from 'vitest'
import type { AfterImport as Setting, VaultStatus } from '../../api'
import { fixtures } from '../../test/fixtures'
import { VaultContext } from '../../vault/VaultContext'
import AfterImport from './AfterImport'
import FileDrop from './FileDrop'
import type { OriginalFile, PickedFile } from './fileHandles'

/**
 * What happens to a statement file after its import (docs/plans/phase-8.md §7, §10 "Statement files"): keep a copy in
 * the vault, delete the original (Chromium, with a file handle; permanent, asked first), both, or leave it; a default
 * from Settings; the browser that can't delete; the vault off.
 */

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

const FILE = new File(['OFXHEADER:100'], 'checking april.ofx')

function vaultStatus(storage: 'VAULT' | 'PLAIN'): VaultStatus {
  return { ...fixtures.statusOff.vault, storage, state: 'UNLOCKED', setUp: storage === 'VAULT' }
}

/** A handle like Chromium's: `remove` and a permission prompt, both recorded. */
function handle(permission: 'granted' | 'denied' = 'granted') {
  const calls: string[] = []
  const original: OriginalFile = {
    name: FILE.name,
    getFile: async () => FILE,
    requestPermission: async (d) => {
      calls.push(`permission:${d.mode}`)
      return permission
    },
    remove: async () => {
      calls.push('remove')
    },
  }
  return { original, calls }
}

function setup(setting: Setting, picked: PickedFile, storage: 'VAULT' | 'PLAIN' = 'VAULT', keep?: () => Response) {
  const requests: { method: string; url: string; body: unknown }[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      requests.push({ method: init?.method ?? 'GET', url, body: init?.body })
      if (url === '/api/imports/settings') {
        return Response.json({ afterImport: setting })
      }
      return keep ? keep() : Response.json({ statementId: 7, fileKept: true })
    }),
  )
  render(
    <VaultContext.Provider value={{ status: vaultStatus(storage), lock: async () => {}, refresh: async () => {} }}>
      <AfterImport statementId={7} picked={picked} />
    </VaultContext.Provider>,
  )
  return requests
}

test('ask: the four choices; Keep sends the same file to the statement and says where it is', async () => {
  const user = userEvent.setup()
  const requests = setup('ASK', { file: FILE, handle: null })

  const card = await screen.findByRole('region', { name: `The file ${FILE.name}` })
  expect(within(card).getAllByRole('button').map((b) => b.textContent)).toEqual([
    'Keep a copy in the vault',
    'Delete the original',
    'Both',
    'Leave it',
  ])
  await user.click(within(card).getByRole('button', { name: 'Keep a copy in the vault' }))

  expect(await within(card).findByRole('status')).toHaveTextContent('A copy is kept in the vault')
  const put = requests.find((r) => r.method === 'PUT')!
  expect(put.url).toBe('/api/statements/7/file')
  const sent = (put.body as FormData).get('file') as File
  expect(sent.name).toBe(FILE.name)
  expect(await sent.text()).toBe(await FILE.text())
})

test('delete the original: asked first, permanent and not to the Trash; then the browser’s prompt and remove()', async () => {
  const user = userEvent.setup()
  const { original, calls } = handle()
  setup('ASK', { file: FILE, handle: original })

  await user.click(await screen.findByRole('button', { name: 'Delete the original' }))
  const confirm = screen.getByRole('group', { name: 'Delete the original' })
  expect(confirm).toHaveTextContent('permanently')
  expect(confirm).toHaveTextContent('doesn’t go to the Trash')
  expect(calls).toEqual([])

  await user.click(within(confirm).getByRole('button', { name: 'Delete it permanently' }))

  expect(await screen.findByText(`Deleted ${FILE.name} from your disk.`)).toBeInTheDocument()
  expect(calls).toEqual(['permission:readwrite', 'remove'])
})

test('both: the copy first, then the same confirmation, which can still keep the original', async () => {
  const user = userEvent.setup()
  const { original, calls } = handle()
  setup('ASK', { file: FILE, handle: original })

  await user.click(await screen.findByRole('button', { name: 'Both' }))

  expect(await screen.findByText(/A copy is kept in the vault/)).toBeInTheDocument()
  const confirm = screen.getByRole('group', { name: 'Delete the original' })
  expect(confirm).toHaveTextContent('The copy in the vault stays.')
  await user.click(within(confirm).getByRole('button', { name: 'Keep the original' }))
  expect(calls).toEqual([])
  expect(screen.queryByRole('group', { name: 'Delete the original' })).not.toBeInTheDocument()
})

test('a browser without file handles: the app says which file to delete yourself', async () => {
  const user = userEvent.setup()
  setup('ASK', { file: FILE, handle: null })

  await user.click(await screen.findByRole('button', { name: 'Delete the original' }))

  expect(screen.getByText(/can’t delete files for Finalysis/)).toHaveTextContent(`Delete ${FILE.name} yourself`)
  expect(screen.queryByRole('button', { name: 'Delete it permanently' })).not.toBeInTheDocument()
})

test('a refused permission leaves the file and says so', async () => {
  const user = userEvent.setup()
  const { original, calls } = handle('denied')
  setup('ASK', { file: FILE, handle: original })

  await user.click(await screen.findByRole('button', { name: 'Delete the original' }))
  await user.click(screen.getByRole('button', { name: 'Delete it permanently' }))

  expect(await screen.findByRole('alert')).toHaveTextContent('still on your disk')
  expect(calls).toEqual(['permission:readwrite'])
})

test('a file the statement wasn’t imported from: the backend’s words', async () => {
  const user = userEvent.setup()
  setup('ASK', { file: FILE, handle: null }, 'VAULT', () =>
    Response.json(
      { type: 'urn:finalysis:problem:file-mismatch', detail: 'That file isn’t the one this statement was imported from.' },
      { status: 409 },
    ),
  )

  await user.click(await screen.findByRole('button', { name: 'Keep a copy in the vault' }))

  expect(await screen.findByRole('alert')).toHaveTextContent('isn’t the one this statement was imported from')
})

test('a default of Keep applies without asking', async () => {
  const requests = setup('KEEP', { file: FILE, handle: null })

  expect(await screen.findByRole('status')).toHaveTextContent('A copy is kept in the vault')
  expect(screen.queryByRole('button', { name: 'Leave it' })).not.toBeInTheDocument()
  expect(requests.filter((r) => r.method === 'PUT')).toHaveLength(1)
})

test('a default of Delete still asks before deleting', async () => {
  const { original, calls } = handle()
  setup('DELETE', { file: FILE, handle: original })

  expect(await screen.findByRole('button', { name: 'Delete it permanently' })).toBeInTheDocument()
  expect(calls).toEqual([])
})

test('with the vault off there is nowhere to keep a copy', async () => {
  setup('ASK', { file: FILE, handle: null }, 'PLAIN')

  const card = await screen.findByRole('region', { name: `The file ${FILE.name}` })
  expect(within(card).queryByRole('button', { name: 'Keep a copy in the vault' })).not.toBeInTheDocument()
  expect(within(card).queryByRole('button', { name: 'Both' })).not.toBeInTheDocument()
  expect(card).toHaveTextContent('needs the encrypted vault')
})

test('Chromium: the app’s own picker gives the file with its handle', async () => {
  const user = userEvent.setup()
  const { original } = handle()
  const picker = vi.fn(async () => [original])
  vi.stubGlobal('showOpenFilePicker', picker)
  const onFile = vi.fn()
  render(<FileDrop file={null} onFile={onFile} />)

  await user.click(screen.getByRole('button', { name: 'Choose a file' }))

  await waitFor(() => expect(onFile).toHaveBeenCalledWith({ file: FILE, handle: original }))
  expect(picker).toHaveBeenCalledWith(expect.objectContaining({ multiple: false }))
})

test('a drop shows the file at once and adds its handle when the browser gives one', async () => {
  const { original } = handle()
  const onFile = vi.fn()
  render(<FileDrop file={null} onFile={onFile} />)

  fireEvent.drop(screen.getByTestId('drop-zone'), {
    dataTransfer: { files: [FILE], items: [{ getAsFileSystemHandle: async () => original }] },
  })

  expect(onFile).toHaveBeenNthCalledWith(1, { file: FILE, handle: null })
  await waitFor(() => expect(onFile).toHaveBeenNthCalledWith(2, { file: FILE, handle: original }))
})
