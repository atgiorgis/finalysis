import '@testing-library/jest-dom/vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterEach, expect, test } from 'vitest'
import CorgiAvatar from './CorgiAvatar'

const HERE = dirname(fileURLToPath(import.meta.url))

afterEach(cleanup)

test('the drawing: an image labelled Corgi, at the size asked, within its element budget', () => {
  render(<CorgiAvatar size={24} />)

  const face = screen.getByRole('img', { name: 'Corgi' })
  expect(face.tagName.toLowerCase()).toBe('svg')
  expect(face).toHaveAttribute('width', '24')
  expect(face).toHaveAttribute('height', '24')
  expect(face).toHaveAttribute('viewBox', '0 0 64 64')
  expect(face.querySelectorAll('*').length + 1).toBeLessThanOrEqual(40)
  expect(face.querySelectorAll('linearGradient, radialGradient, image, text')).toHaveLength(0)
})

test('the fur is the fixed corgi tokens; only the background and the ring are the theme’s', () => {
  const source = readFileSync(join(HERE, 'CorgiAvatar.tsx'), 'utf8')
  const theme = readFileSync(join(HERE, '..', '..', 'theme.css'), 'utf8')

  expect(source).not.toMatch(/#[0-9a-f]{3,6}\b/i)
  for (const token of ['fur', 'fur-shade', 'white', 'ear-inner', 'ink', 'tongue', 'blush']) {
    expect(theme, token).toMatch(new RegExp(`--corgi-${token}: #[0-9a-f]{6};`))
  }
  expect(theme).toContain('--corgi-bg: var(--surface2);')
  expect(theme).toContain('--corgi-ring: var(--border);')
})

test('each avatar on a page has its own clip, so several can share it', () => {
  render(
    <>
      <CorgiAvatar size={24} />
      <CorgiAvatar size={40} />
    </>,
  )

  const ids = [...document.querySelectorAll('clipPath')].map((c) => c.id)
  expect(ids).toHaveLength(2)
  expect(new Set(ids).size).toBe(2)
  document.querySelectorAll('svg.corgi-avatar g[clip-path]').forEach((g, i) => {
    expect(g.getAttribute('clip-path')).toBe(`url(#${ids[i]})`)
  })
})

test('an image placed in public/ is shown instead, cropped to a circle, with Corgi as its alt text', () => {
  render(<CorgiAvatar size={40} src="/corgi-avatar.png" />)

  const image = screen.getByRole('img', { name: 'Corgi' })
  expect(image.tagName.toLowerCase()).toBe('img')
  expect(image).toHaveAttribute('src', '/corgi-avatar.png')
  expect(image).toHaveAttribute('alt', 'Corgi')
  expect(image).toHaveClass('corgi-avatar', 'corgi-avatar-image')
  expect(image).toHaveAttribute('width', '40')
})

test('with no image in public/, the drawing is the default', () => {
  expect(__CORGI_AVATAR__).toBeNull()
  render(<CorgiAvatar />)

  expect(screen.getByRole('img', { name: 'Corgi' }).tagName.toLowerCase()).toBe('svg')
})
