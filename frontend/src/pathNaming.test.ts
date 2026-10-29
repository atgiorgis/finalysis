import { readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'
import { expect, test } from 'vitest'

/**
 * Where Corgi runs is its **path** (Cloud, Self-hosted, Local); "tier" is a model's size (docs/plans/phase-9.md §13,
 * §16 "Decided at 9b-1"). This guard fails on "tier" used for a path: the word within a few words of Cloud,
 * Self-hosted or Local, either way round. "Tier" alone, for a model's size, is fine.
 */
const SRC = dirname(fileURLToPath(import.meta.url))
const SELF = fileURLToPath(import.meta.url)

const PATH_WORD = '(?:cloud|self[- ]hosted|local)'
const TIER_FOR_A_PATH = new RegExp(
  `\\b${PATH_WORD}\\b(?:\\W+\\w+){0,3}?\\W+tiers?\\b|\\btiers?\\b(?:\\W+\\w+){0,3}?\\W+${PATH_WORD}\\b`,
  'i',
)

function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) {
      return sourceFiles(path)
    }
    return /\.(tsx?|css|json)$/.test(name) && path !== SELF ? [path] : []
  })
}

/** The lines of `text` that use "tier" for a path, as `name:line: text`. */
function tierForAPath(name: string, text: string): string[] {
  return text
    .split('\n')
    .flatMap((line, index) => (TIER_FOR_A_PATH.test(line) ? [`${name}:${index + 1}: ${line.trim()}`] : []))
}

test('no file under src/ calls a path a tier', () => {
  const found = sourceFiles(SRC).flatMap((file) => tierForAPath(relative(SRC, file), readFileSync(file, 'utf8')))
  expect(found).toEqual([])
})

test('the guard finds "tier" next to a path, either way round, and leaves model sizes alone', () => {
  for (const line of ['Choose a tier: Cloud or Local', 'the Local tier', 'Self-hosted tier', 'tiers: cloud, local',
    'The cloud model tier']) {
    expect(tierForAPath('x', line), line).toHaveLength(1)
  }
  for (const line of ['the standard tier and the deep tier', 'a larger model tier', 'Local · Ollama', 'path: Cloud']) {
    expect(tierForAPath('x', line), line).toEqual([])
  }
})
