import '@testing-library/jest-dom/vitest'
import { cleanup, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { renderApp } from './test/renderApp'
import { applyTheme, readTheme, THEME_KEY } from './theme'

const CSS = readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'theme.css'), 'utf8')

/** The brief's two sets (step 7c), with the light shades adjusted for contrast (theme.css explains which). */
const DARK = {
  bg: '#0a0b10', surface: '#13151d', surface2: '#1b1e2a', text: '#f3f4f8', muted: '#9ca2b8', border: '#262a3b',
  accent: '#8b7cff', income: '#2dd4bf', spend: '#fb7185', s1: '#8b7cff', s2: '#2dd4bf', s3: '#fbbf24',
  s4: '#f472b6', s5: '#fb923c', s6: '#38bdf8', 's-other': '#5b6075',
}
const LIGHT = {
  bg: '#f6f6fa', surface: '#ffffff', surface2: '#f0f0f7', text: '#15171f', muted: '#636a80', border: '#e5e6ee',
  accent: '#5b47e0', income: '#0f766e', spend: '#be123c', s1: '#5b47e0', s2: '#0d9488', s3: '#d97706',
  s4: '#db2777', s5: '#ea580c', s6: '#0284c7', 's-other': '#9aa0b4',
}

let style: HTMLStyleElement

beforeEach(() => {
  localStorage.clear()
  delete document.documentElement.dataset.theme
  style = document.createElement('style')
  style.textContent = CSS.replace(/@import[^;]+;/g, '')
  document.head.append(style)
})

afterEach(() => {
  cleanup()
  style.remove()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  localStorage.clear()
  delete document.documentElement.dataset.theme
})

function token(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(`--${name}`).trim().toLowerCase()
}

test('dark by default when nothing is stored', () => {
  expect(readTheme()).toBe('dark')
})

test('each theme applies its whole token set', () => {
  for (const [theme, set] of [
    ['dark', DARK],
    ['light', LIGHT],
  ] as const) {
    applyTheme(theme)
    expect(document.documentElement).toHaveAttribute('data-theme', theme)
    for (const [name, value] of Object.entries(set)) {
      expect(token(name), `${theme} --${name}`).toBe(value)
    }
  }
})

test('the switch changes the theme at once and remembers it', async () => {
  const user = userEvent.setup()
  applyTheme(readTheme())
  renderApp('/settings')

  const toggle = await screen.findByRole('switch', { name: 'Dark theme' })
  expect(toggle).toHaveAttribute('aria-checked', 'true')

  await user.click(toggle)
  expect(toggle).toHaveAttribute('aria-checked', 'false')
  expect(document.documentElement.dataset.theme).toBe('light')
  expect(token('bg')).toBe(LIGHT.bg)
  expect(localStorage.getItem(THEME_KEY)).toBe('light')
  expect(readTheme()).toBe('light')

  await user.click(toggle)
  expect(document.documentElement.dataset.theme).toBe('dark')
  expect(localStorage.getItem(THEME_KEY)).toBe('dark')
})

test('a stored light theme is read back; anything else is dark', () => {
  localStorage.setItem(THEME_KEY, 'light')
  expect(readTheme()).toBe('light')
  localStorage.setItem(THEME_KEY, 'sepia')
  expect(readTheme()).toBe('dark')
})

test('storage that can’t be read or written: dark, and the switch still works', async () => {
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
    throw new DOMException('blocked', 'SecurityError')
  })
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw new DOMException('blocked', 'SecurityError')
  })
  expect(readTheme()).toBe('dark')

  const user = userEvent.setup()
  applyTheme(readTheme())
  renderApp('/settings')
  const toggle = await screen.findByRole('switch', { name: 'Dark theme' })
  await user.click(toggle)

  expect(document.documentElement.dataset.theme).toBe('light')
  expect(toggle).toHaveAttribute('aria-checked', 'false')
})

// ---- contrast: every text colour on every background it is used on, at 4.5:1 (WCAG AA) ----

type Rgb = [number, number, number]

function hex(value: string): Rgb {
  const h = value.replace('#', '')
  return [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16)) as Rgb
}

/** The values a theme block declares, by name, with `color-mix(in srgb, …)` resolved. */
function tokens(theme: 'dark' | 'light'): Record<string, Rgb> {
  const block = (selector: RegExp) => CSS.match(selector)?.[1] ?? ''
  const own = theme === 'dark' ? block(/:root,\s*:root\[data-theme='dark'\]\s*\{([^}]*)\}/) : block(/:root\[data-theme='light'\]\s*\{([^}]*)\}/)
  const roles = block(/\n:root\s*\{([^}]*)\}/)
  const raw: Record<string, string> = {}
  for (const [, name, value] of `${own}\n${roles}`.matchAll(/--([\w-]+):\s*([^;]+);/g)) {
    raw[name] ??= value.trim()
  }
  const resolve = (value: string): Rgb | null => {
    const v = value.trim()
    if (/^#[0-9a-f]{6}$/i.test(v)) {
      return hex(v)
    }
    const ref = /^var\(--([\w-]+)\)$/.exec(v)
    if (ref) {
      return raw[ref[1]] ? resolve(raw[ref[1]]) : null
    }
    const mix = /^color-mix\(in srgb, (var\(--[\w-]+\)) (\d+)%, (var\(--[\w-]+\))\)$/.exec(v)
    if (mix) {
      const a = resolve(mix[1])!
      const b = resolve(mix[3])!
      const p = Number(mix[2]) / 100
      return a.map((c, i) => Math.round(c * p + b[i] * (1 - p))) as Rgb
    }
    return null
  }
  const out: Record<string, Rgb> = {}
  for (const name of Object.keys(raw)) {
    const rgb = resolve(raw[name])
    if (rgb) {
      out[name] = rgb
    }
  }
  return out
}

function luminance([r, g, b]: Rgb): number {
  const [lr, lg, lb] = [r, g, b].map((c) => {
    const s = c / 255
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
  })
  return 0.2126 * lr + 0.7152 * lg + 0.0722 * lb
}

function contrast(a: Rgb, b: Rgb): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}

/** Text tokens and the backgrounds each appears on in index.css. */
const PAIRS: [string, string[]][] = [
  ['text', ['bg', 'surface', 'surface2', 'selected-bg', 'positive-bg']],
  ['muted', ['bg', 'surface', 'surface2']],
  ['accent', ['bg', 'surface', 'surface2', 'selected-bg']],
  ['income', ['bg', 'surface', 'surface2', 'positive-bg']],
  ['spend', ['bg', 'surface', 'surface2', 'danger-bg']],
  ['warning', ['surface', 'surface2', 'warning-bg']],
  ['danger', ['surface', 'surface2', 'danger-bg']],
  ['s3-ink', ['surface', 'surface2']],
  ['s5-ink', ['surface', 'surface2']],
  ['s6-ink', ['surface', 'surface2']],
  ['path-cloud-ink', ['surface', 'surface2']],
  ['path-self-hosted-ink', ['surface', 'surface2']],
  ['path-local-ink', ['surface', 'surface2']],
  ['on-accent', ['accent']],
]

for (const theme of ['dark', 'light'] as const) {
  test(`${theme}: every text colour on its backgrounds is at least 4.5:1`, () => {
    const t = tokens(theme)
    const failures: string[] = []
    for (const [fg, backgrounds] of PAIRS) {
      for (const bg of backgrounds) {
        expect(t[fg], `--${fg}`).toBeDefined()
        expect(t[bg], `--${bg}`).toBeDefined()
        const ratio = contrast(t[fg], t[bg])
        if (ratio < 4.5) {
          failures.push(`--${fg} on --${bg}: ${ratio.toFixed(2)}`)
        }
      }
    }
    expect(failures).toEqual([])
  })
}

test('the contrast check catches a failing pair (the mockup’s light income on surface2)', () => {
  expect(contrast(hex('#0d9488'), hex('#f0f0f7'))).toBeLessThan(4.5)
})
