import { expect, test } from 'vitest'
import type { ChatUsage } from './api'
import {
  AI_NAME,
  bannerText,
  costText,
  LOCAL_COST,
  offText,
  pathBadge,
  SELF_HOSTED_COST,
  turnCostText,
  validRegion,
  validServerName,
} from './corgi'

test('one name for all AI', () => {
  expect(AI_NAME).toBe('Corgi')
})

// ---- badges and banners (docs/plans/phase-9.md §3.5) ----------------------------------------------------------------

test('the badge per path: Cloud amber with its provider, Self-hosted blue with its name, Local green', () => {
  expect(pathBadge({ path: 'CLOUD', provider: 'bedrock' })).toEqual({ label: 'Cloud · Bedrock', tone: 'cloud' })
  expect(pathBadge({ path: 'CLOUD', provider: 'vertex' })).toEqual({ label: 'Cloud · vertex', tone: 'cloud' })
  expect(pathBadge({ path: 'SELF_HOSTED', provider: 'ollama', server: 'finalysis-selfhost' })).toEqual({
    label: 'Self-hosted · finalysis-selfhost',
    tone: 'self-hosted',
  })
  expect(pathBadge({ path: 'LOCAL', provider: 'ollama' })).toEqual({ label: 'Local', tone: 'local' })
})

test('no badge while Off or locked; "Corgi is off" in muted ink when the path can’t run', () => {
  expect(pathBadge({ path: 'OFF' })).toBeNull()
  expect(pathBadge({ reachable: false, reason: 'Vault locked' })).toBeNull()
  expect(pathBadge({ path: 'LOCAL', reachable: false, reason: 'Ollama unreachable' })).toEqual({
    label: 'Corgi is off',
    tone: 'off',
  })
})

test('the banners are SEC-01’s wordings, word for word, and Off has none', () => {
  expect(bannerText('CLOUD', { path: 'CLOUD', provider: 'bedrock' })).toBe(
    'Cloud AI is on (Amazon Bedrock): merchant names and amounts are sent for categorization, and chat questions ' +
      'with the figures they need are sent for answers.',
  )
  expect(bannerText('SELF_HOSTED', { path: 'SELF_HOSTED', server: 'finalysis-selfhost' })).toBe(
    'Corgi runs on your own server (finalysis-selfhost): merchant names and amounts are sent to it for ' +
      'categorization, and chat questions with the figures they need are sent for answers.',
  )
  expect(bannerText('LOCAL')).toBe('Corgi runs on this computer: nothing is sent anywhere.')
  expect(bannerText('OFF')).toBeNull()
})

test('the off text says why and how to get Corgi back; Off itself is a choice, not a fault', () => {
  expect(offText({ path: 'LOCAL', reachable: false, reason: 'model ministral-3:14b not pulled' })).toBe(
    'Corgi is off: model ministral-3:14b not pulled. Install Ollama or choose a path in Settings.',
  )
  expect(offText({ path: 'SELF_HOSTED', reachable: false })).toBe(
    'Corgi is off. Install Ollama or choose a path in Settings.',
  )
  expect(offText({ path: 'LOCAL', reachable: true })).toBeNull()
  expect(offText({ path: 'OFF', reachable: false })).toBeNull()
  expect(offText({ reachable: false, reason: 'Vault locked' })).toBeNull()
})

// ---- costs per path (§4.2) ------------------------------------------------------------------------------------------

test('a summary cost: the estimate on Cloud, $0.00 · local on Local, no token cost on Self-hosted', () => {
  expect(costText('0.029515', { path: 'CLOUD', provider: 'bedrock' })).toBe('$0.03')
  expect(costText('0.029515', { path: 'LOCAL', provider: 'ollama' })).toBe(LOCAL_COST)
  expect(costText(null, { path: 'LOCAL' })).toBe('$0.00 · local')
  expect(costText(null, { path: 'SELF_HOSTED' })).toBe(SELF_HOSTED_COST)
  expect(SELF_HOSTED_COST).toBe('No token cost on Self-hosted.')
})

test('a turn’s cost is on the path it ran on, the status’s for turns stored before paths', () => {
  const format = (v: string | null | undefined) => (v ? `$${v} estimated` : 'no price')
  const usage = (more: Partial<ChatUsage>): ChatUsage => ({ model: 'm', rounds: 1, estimatedCost: '0.012000', ...more })
  const note = 'Self-hosted cost is the server’s running time; it isn’t computed yet (step 9c)'

  expect(turnCostText(usage({ path: 'CLOUD' }), { path: 'LOCAL' }, format)).toBe('$0.012000 estimated')
  expect(turnCostText(usage({ path: 'LOCAL', estimatedCost: '0.000000' }), { path: 'CLOUD' }, format)).toBe(LOCAL_COST)
  expect(turnCostText(usage({ path: 'SELF_HOSTED', estimatedCost: null, costNote: note }), { path: 'CLOUD' }, format))
    .toBe(note)
  expect(turnCostText(usage({}), { path: 'LOCAL' }, format)).toBe(LOCAL_COST)
  expect(turnCostText(usage({}), { path: 'CLOUD' }, format)).toBe('$0.012000 estimated')
})

// ---- Self-hosted's fields, validated like the backend -------------------------------------------------------------

test('a Tailscale name is letters, digits, hyphens and dots; never a web address', () => {
  for (const good of ['finalysis-selfhost', 'gpu1.tail1234.ts.net', '127.0.0.1', 'A-b-9']) {
    expect(validServerName(good), good).toBe(true)
  }
  for (const bad of ['', 'http://x', 'x:11434', 'a..b', '-a', 'a-', 'user@host', 'two words', 'host/path',
    'a'.repeat(64)]) {
    expect(validServerName(bad), bad).toBe(false)
  }
})

test('a region is an AWS region id', () => {
  expect(validRegion('us-east-1')).toBe(true)
  expect(validRegion('us-gov-west-1')).toBe(true)
  for (const bad of ['', 'US-EAST-1', 'us_east_1', 'useast1', 'us-east-1;']) {
    expect(validRegion(bad), bad).toBe(false)
  }
})
