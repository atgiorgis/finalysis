import { readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'
import { expect, test } from 'vitest'

/**
 * The frontend never computes a total, average or percent (docs/plans/phase-7.md §6.2): screens show the API's
 * strings through format.ts. This guard fails when a screen file turns a value into a number. Chart components may,
 * for drawing only (bar heights, slice angles), and live under `components/charts/`.
 */
const SCREEN_DIRS = ['shell', 'pages', 'components']
const ALLOWED_DIR = join('components', 'charts')
const NUMERIC = [/\bparseFloat\s*\(/, /\bparseInt\s*\(/, /\bNumber\s*\(/, /\bMath\.\w+\s*\(/, /\.toFixed\s*\(/]

const SRC = dirname(fileURLToPath(import.meta.url))

function sourceFiles(dir: string): string[] {
  let entries: string[]
  try {
    entries = readdirSync(dir)
  } catch {
    return []
  }
  return entries.flatMap((name) => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) {
      return sourceFiles(path)
    }
    return /\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name) ? [path] : []
  })
}

/** The lines of `text` that turn a value into a number, as `name:line: text`. */
function numericLines(name: string, text: string): string[] {
  return text
    .split('\n')
    .flatMap((line, index) => (NUMERIC.some((pattern) => pattern.test(line)) ? [`${name}:${index + 1}: ${line.trim()}`] : []))
}

test('screen files never parse API values into numbers', () => {
  const offenders = SCREEN_DIRS.flatMap((dir) => sourceFiles(join(SRC, dir)))
    .filter((path) => !relative(SRC, path).startsWith(ALLOWED_DIR))
    .flatMap((path) => numericLines(relative(SRC, path), readFileSync(path, 'utf8')))

  expect(offenders).toEqual([])
})

test('the guard catches each way of making a number', () => {
  const text = [
    'const total = parseFloat(a.amount) + parseFloat(b.amount)',
    'const n = parseInt(row.count, 10)',
    'const v = Number(month.net)',
    'const r = Math.round(share)',
    'label = value.toFixed(2)',
    'const shown = formatMoney(month.net)',
  ].join('\n')

  expect(numericLines('Example.tsx', text)).toEqual([
    'Example.tsx:1: const total = parseFloat(a.amount) + parseFloat(b.amount)',
    'Example.tsx:2: const n = parseInt(row.count, 10)',
    'Example.tsx:3: const v = Number(month.net)',
    'Example.tsx:4: const r = Math.round(share)',
    'Example.tsx:5: label = value.toFixed(2)',
  ])
})
