import { render, screen } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import App from './App'

afterEach(() => {
  vi.unstubAllGlobals()
})

test('renders the app and shows the backend status', async () => {
  // App fetches /api/status on mount; answer it without a running backend.
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue(
      Response.json({ app: 'finalysis', status: 'ok', timestamp: '2026-01-01T00:00:00Z' }),
    ),
  )

  render(<App />)

  expect(screen.getByRole('heading', { name: 'Finalysis' })).toBeInTheDocument()
  expect(await screen.findByText('ok')).toBeInTheDocument()
})
