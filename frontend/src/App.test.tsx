import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import App from './App'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

// App fetches /api/status on mount; answer it without a running backend.
function stubStatus(ai: object) {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue(
      Response.json({ app: 'finalysis', status: 'ok', timestamp: '2026-01-01T00:00:00Z', ai }),
    ),
  )
}

test('renders the app and shows the backend status', async () => {
  stubStatus({ mode: 'OFF' })

  render(<App />)

  expect(screen.getByRole('heading', { name: 'Finalysis' })).toBeInTheDocument()
  expect(await screen.findByText('ok')).toBeInTheDocument()
})

test('shows no cloud AI banner when AI is off', async () => {
  stubStatus({ mode: 'OFF' })

  render(<App />)

  expect(await screen.findByText('ok')).toBeInTheDocument()
  expect(screen.queryByText(/Cloud AI is on/)).not.toBeInTheDocument()
})

test('shows the cloud AI banner while cloud AI is on', async () => {
  stubStatus({
    mode: 'CLOUD',
    provider: 'bedrock',
    models: { categorization: 'chat-model', embedding: 'embedding-model' },
  })

  render(<App />)

  expect(await screen.findByRole('status')).toHaveTextContent(
    'Cloud AI is on (Amazon Bedrock): merchant names and amounts are sent for categorization, and chat ' +
      'questions with the figures they need are sent for answers.',
  )
})

test('names an unknown cloud provider as the backend reports it', async () => {
  stubStatus({ mode: 'CLOUD', provider: 'othercloud', models: {} })

  render(<App />)

  expect(await screen.findByRole('status')).toHaveTextContent('Cloud AI is on (othercloud)')
})

test('shows the cloud AI banner above the chat page while cloud AI is on', async () => {
  stubStatus({ mode: 'CLOUD', provider: 'bedrock', models: { main: 'chat-model' } })

  render(<App />)

  expect(await screen.findByRole('region', { name: 'Chat' })).toBeInTheDocument()
  expect(screen.getByRole('status')).toHaveTextContent(/chat questions with the figures they need are sent/)
})

test('shows the chat page without a banner while AI is off', async () => {
  stubStatus({ mode: 'OFF' })

  render(<App />)

  expect(await screen.findByRole('region', { name: 'Chat' })).toBeInTheDocument()
  expect(screen.queryByRole('status')).not.toBeInTheDocument()
})
