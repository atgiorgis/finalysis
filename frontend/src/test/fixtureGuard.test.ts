import { expect, test } from 'vitest'
import { refuseUnlessPlain } from '../../scripts/fixtureGuard.mjs'
import { fixtures } from './fixtures'

/**
 * `npm run capture-fixtures` refuses any backend but the plain dev database with AI off (docs/plans/phase-8.md §8.2):
 * a vault may hold real statements, and fixtures are committed.
 */
test('the plain dev database with AI off is accepted', () => {
  expect(refuseUnlessPlain(fixtures.statusOff)).toBe(fixtures.statusOff)
})

test('a backend with the vault on is refused, locked or unlocked', () => {
  for (const state of ['UNLOCKED', 'LOCKED', 'NOT_SET_UP'] as const) {
    const vault = { ...fixtures.statusOff, vault: { ...fixtures.statusOff.vault, storage: 'VAULT', state } }
    expect(() => refuseUnlessPlain(vault)).toThrow(/storage is VAULT, not PLAIN/)
  }
})

test('a backend that doesn’t say its storage is refused too', () => {
  const { vault: _vault, ...older } = fixtures.statusOff
  expect(() => refuseUnlessPlain(older)).toThrow(/storage is unknown/)
  expect(() => refuseUnlessPlain(null)).toThrow()
})

test('cloud AI is still refused on the plain database', () => {
  expect(() => refuseUnlessPlain({ ...fixtures.statusOff, ai: { path: 'CLOUD' } })).toThrow(/AI must be off/)
})
