import { cleanup, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterEach, expect, test, vi } from 'vitest'
import { fixtures } from './test/fixtures'
import { renderApp } from './test/renderApp'

/**
 * What the browser keeps on disk (docs/plans/phase-8.md §8.2, SEC-12): the app writes only its theme to
 * `localStorage`, and nothing to `sessionStorage` or IndexedDB, so no figure, name or secret outlives the page. API
 * responses are `no-store`, so the HTTP cache keeps none either (the backend's NoStoreTest).
 */

const SRC = dirname(fileURLToPath(import.meta.url))
const THEME_KEY = 'finalysis.theme'

function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) {
      return name === 'test' ? [] : sourceFiles(path)
    }
    return /\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name) ? [path] : []
  })
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  localStorage.clear()
  sessionStorage.clear()
})

test('only theme.ts touches localStorage, and nothing touches sessionStorage, IndexedDB or the Cache API', () => {
  const users: string[] = []
  for (const file of sourceFiles(SRC)) {
    const text = readFileSync(file, 'utf8')
    const name = relative(SRC, file)
    expect(text, name).not.toMatch(/\bsessionStorage\b|\bindexedDB\b|\bcaches\.open\b|\bdocument\.cookie\b/)
    if (/\blocalStorage\b/.test(text)) {
      users.push(name)
    }
  }
  expect(users).toEqual(['theme.ts'])
  expect(readFileSync(join(SRC, 'theme.ts'), 'utf8')).toContain(`'${THEME_KEY}'`)
})

test('using the app, the vault screens included, writes nothing but the theme', async () => {
  const user = userEvent.setup()
  const writes: string[] = []
  const setItem = Storage.prototype.setItem
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (this: Storage, key: string, value: string) {
    writes.push(`${this === sessionStorage ? 'session' : 'local'}:${key}`)
    setItem.call(this, key, value)
  })
  let state = 'LOCKED'
  const status = () =>
    Response.json({ ...fixtures.statusOff, vault: { ...fixtures.statusOff.vault, storage: 'VAULT', setUp: true, state } })
  renderApp('/overview?month=2026-09', { '/api/status': status }, () => {
    state = 'UNLOCKED'
    return new Response(null, { status: 204 })
  })

  await user.type(await screen.findByLabelText('Password'), 'a password typed once')
  await user.click(screen.getByRole('button', { name: 'Unlock' }))
  await screen.findByRole('banner')
  await user.click(screen.getByRole('switch', { name: 'Dark theme' }))
  await user.click(screen.getByRole('button', { name: 'Open menu' }))
  await user.click(screen.getByRole('link', { name: 'Transactions' }))
  await screen.findByRole('heading', { level: 1, name: 'Transactions' })

  expect(writes).toEqual([`local:${THEME_KEY}`])
  expect(Object.keys(localStorage)).toEqual([THEME_KEY])
  expect(sessionStorage.length).toBe(0)
})
