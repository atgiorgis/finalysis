import { expect, test } from 'vitest'
import { AI_NAME, costText, pathBadge } from './corgi'

test('one name for all AI', () => {
  expect(AI_NAME).toBe('Corgi')
})

test('the path badge comes from the status: cloud or local, with the configured provider', () => {
  expect(pathBadge({ path: 'CLOUD', provider: 'bedrock' })).toEqual({ label: 'Cloud · Bedrock', tone: 'cloud' })
  expect(pathBadge({ path: 'LOCAL', provider: 'ollama' })).toEqual({ label: 'Local · Ollama', tone: 'local' })
  expect(pathBadge({ path: 'CLOUD', provider: 'vertex' })).toEqual({ label: 'Cloud · vertex', tone: 'cloud' })
  expect(pathBadge({ path: 'OFF' })).toBeNull()
})

test('Self-hosted gets its own label; a locked vault has no path and no badge', () => {
  expect(pathBadge({ path: 'SELF_HOSTED', provider: 'ollama' })).toEqual({ label: 'Self-hosted · Ollama', tone: 'cloud' })
  expect(pathBadge({ reachable: false, reason: 'Vault locked' })).toBeNull()
})

test('a cost is $0.00 · local with a local model, else the estimate to the cent', () => {
  expect(costText('0.029515', { path: 'CLOUD', provider: 'bedrock' })).toBe('$0.03')
  expect(costText('0.029515', { path: 'LOCAL', provider: 'ollama' })).toBe('$0.00 · local')
  expect(costText(null, { path: 'LOCAL' })).toBe('$0.00 · local')
})
