import { expect, test } from 'vitest'
import { AI_NAME, costText, pathBadge } from './corgi'

test('one name for all AI', () => {
  expect(AI_NAME).toBe('Corgi')
})

test('the path badge comes from the status: cloud or local, with the configured provider', () => {
  expect(pathBadge({ mode: 'CLOUD', provider: 'bedrock' })).toEqual({ label: 'Cloud · Bedrock', tone: 'cloud' })
  expect(pathBadge({ mode: 'LOCAL', provider: 'ollama' })).toEqual({ label: 'Local · Ollama', tone: 'local' })
  expect(pathBadge({ mode: 'CLOUD', provider: 'vertex' })).toEqual({ label: 'Cloud · vertex', tone: 'cloud' })
  expect(pathBadge({ mode: 'OFF' })).toBeNull()
})

test('a cost is $0.00 · local with a local model, else the estimate to the cent', () => {
  expect(costText('0.029515', { mode: 'CLOUD', provider: 'bedrock' })).toBe('$0.03')
  expect(costText('0.029515', { mode: 'LOCAL', provider: 'ollama' })).toBe('$0.00 · local')
  expect(costText(null, { mode: 'LOCAL' })).toBe('$0.00 · local')
})
