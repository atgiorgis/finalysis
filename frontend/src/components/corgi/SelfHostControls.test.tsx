import '@testing-library/jest-dom/vitest'
import { act, cleanup, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, test, vi } from 'vitest'
import type { AiStatus, SelfHostStatus } from '../../api'
import { fixtures, selfHostFixture } from '../../test/fixtures'
import { renderApp } from '../../test/renderApp'
import { POLL_MS } from './SelfHostControls'

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

const OLLAMA_MODELS = { categorization: 'ministral-3:14b', embedding: 'nomic-embed-text', main: 'ministral-3:14b' }

const AI: AiStatus = {
  path: 'SELF_HOSTED',
  available: ['SELF_HOSTED', 'LOCAL'],
  reachable: false,
  reason: 'Ollama unreachable',
  provider: 'ollama',
  server: 'finalysis-selfhost',
  models: OLLAMA_MODELS,
  pathModels: { SELF_HOSTED: OLLAMA_MODELS, LOCAL: OLLAMA_MODELS },
}

const STORAGE = 'While stopped, its 80 GB disk is still billed (gp3, per GB-month).'

function server(more: Partial<SelfHostStatus>): SelfHostStatus {
  return {
    controls: true,
    reason: null,
    state: 'STOPPED',
    progress: null,
    instanceType: 'g6.xlarge',
    minutesRunning: null,
    hourlyRate: '0.8048',
    estimatedCost: null,
    line: null,
    storageNote: STORAGE,
    note: null,
    ...more,
  }
}

const STOPPED = server({})
const STARTING = server({ state: 'STARTING', progress: 'instance starting', minutesRunning: 0, estimatedCost: '0.00',
  line: 'running 0 min · ~$0.00' })
const WAITING = server({ ...STARTING, progress: 'waiting for Tailscale', minutesRunning: 1, line: 'running 1 min · ~$0.01' })
const LOADING = server({ ...STARTING, progress: 'loading the model', minutesRunning: 2, line: 'running 2 min · ~$0.03' })
const RUNNING = server({ state: 'RUNNING', minutesRunning: 42, estimatedCost: '0.56', line: 'running 42 min · ~$0.56' })
const STOPPING = server({ state: 'STOPPING', minutesRunning: 42, line: 'stopping after 42 min · ~$0.56' })

/** `GET /api/selfhost` answering each state in turn, the last one again and again. */
function states(...sequence: SelfHostStatus[]) {
  let index = 0
  return {
    route: () => Response.json(sequence[Math.min(index++, sequence.length - 1)]),
    reads: () => index,
  }
}

async function controls(): Promise<HTMLElement> {
  return screen.findByRole('group', { name: 'Server controls' })
}

/** The controls' own block (the reason also shows in Chat costs). */
async function block(): Promise<HTMLElement> {
  return (await controls()).closest('.selfhost-controls') as HTMLElement
}

function button(name: string): HTMLElement {
  return screen.getByRole('button', { name })
}

test('without the aws profile, Start and Stop are disabled with the backend’s reason; Test still works', async () => {
  const user = userEvent.setup()
  const { writes } = renderApp('/settings', { '/api/status': { ...fixtures.statusOff, ai: AI } }, (write) =>
    write.url === '/api/selfhost/test'
      ? Response.json({ reachable: true, version: '0.12.6', message: 'Reachable, Ollama 0.12.6' })
      : undefined)
  const own = await block()

  expect(await within(own).findByText(selfHostFixture.reason)).toHaveClass('path-card-reason')
  expect(button('Start')).toBeDisabled()
  expect(button('Stop')).toBeDisabled()
  expect(button('Start')).toHaveAttribute('title', selfHostFixture.reason)
  expect(screen.queryByText(/Not available yet/)).not.toBeInTheDocument()

  await user.click(button('Test'))

  expect(await screen.findByText('Reachable, Ollama 0.12.6')).toHaveAttribute('role', 'status')
  expect(writes).toEqual([{ method: 'POST', url: '/api/selfhost/test', body: { name: 'finalysis-selfhost' } }])
})

test('Test sends the name as typed, trimmed, and shows the reason when the server can’t be reached', async () => {
  const user = userEvent.setup()
  const { writes } = renderApp('/settings', { '/api/status': { ...fixtures.statusOff, ai: AI } }, () =>
    Response.json({ reachable: false, version: null,
      message: 'Not resolvable: check the name, and that Tailscale is running on this Mac.' }))
  await controls()
  const nameField = screen.getByRole('textbox', { name: 'Tailscale name' })

  await user.clear(nameField)
  expect(button('Test')).toBeDisabled()
  await user.type(nameField, 'http://evil.example')
  expect(button('Test')).toBeDisabled()
  await user.clear(nameField)
  await user.type(nameField, ' other-box.tail1234.ts.net ')
  await user.click(button('Test'))

  expect(await screen.findByText(/Not resolvable/)).toHaveClass('error')
  expect(writes.map((w) => w.body)).toEqual([{ name: 'other-box.tail1234.ts.net' }])
})

test('stopped: the state, the rate’s type, the stopped-disk note; Start on, Stop off', async () => {
  renderApp('/settings', { '/api/status': { ...fixtures.statusOff, ai: AI }, '/api/selfhost': STOPPED })
  await controls()

  const status = await screen.findByLabelText('Server status')
  expect(within(status).getByText('Stopped')).toBeInTheDocument()
  expect(screen.getByText(STORAGE)).toBeInTheDocument()
  expect(button('Start')).toBeEnabled()
  expect(button('Stop')).toBeDisabled()
  expect(screen.queryByText(/running \d+ min/)).not.toBeInTheDocument()
})

test('Start, then each progress line read every 5 seconds, then running with its line; polling stops', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
  const reads = states(STOPPED, WAITING, LOADING, RUNNING)
  const { writes, requests } = renderApp('/settings', {
    '/api/status': { ...fixtures.statusOff, ai: AI },
    '/api/selfhost': reads.route,
  }, (write) => (write.url === '/api/selfhost/start' ? Response.json(STARTING, { status: 202 }) : undefined))
  await controls()
  await screen.findByText('Stopped')

  await user.click(button('Start'))

  expect(writes).toEqual([{ method: 'POST', url: '/api/selfhost/start', body: undefined }])
  expect(await screen.findByText(': instance starting…')).toBeInTheDocument()
  expect(screen.getByText('running 0 min · ~$0.00')).toBeInTheDocument()
  expect(button('Start')).toBeDisabled()
  expect(button('Stop')).toBeEnabled()

  for (const [progress, line] of [
    ['waiting for Tailscale', 'running 1 min · ~$0.01'],
    ['loading the model', 'running 2 min · ~$0.03'],
  ]) {
    await act(async () => {
      await vi.advanceTimersByTimeAsync(POLL_MS)
    })
    expect(await screen.findByText(`: ${progress}…`)).toBeInTheDocument()
    expect(screen.getByText(line)).toBeInTheDocument()
  }

  const statusReadsBefore = requests.filter((r) => r === '/api/status').length
  await act(async () => {
    await vi.advanceTimersByTimeAsync(POLL_MS)
  })
  expect(await screen.findByText('Running')).toBeInTheDocument()
  expect(screen.getByText('running 42 min · ~$0.56')).toBeInTheDocument()
  expect(screen.getByText(/g6\.xlarge at \$0\.8048 an hour/)).toBeInTheDocument()
  expect(requests.filter((r) => r === '/api/status').length, 'the badge is read again once it settles')
    .toBeGreaterThan(statusReadsBefore)

  const readsWhenRunning = reads.reads()
  await act(async () => {
    await vi.advanceTimersByTimeAsync(POLL_MS * 4)
  })
  expect(reads.reads(), 'no polling while nothing changes').toBe(readsWhenRunning)
})

test('Stop from running, read again until stopped', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
  const reads = states(RUNNING, STOPPED)
  const { writes } = renderApp('/settings', {
    '/api/status': { ...fixtures.statusOff, ai: { ...AI, reachable: true, reason: undefined } },
    '/api/selfhost': reads.route,
  }, (write) => (write.url === '/api/selfhost/stop' ? Response.json(STOPPING, { status: 202 }) : undefined))
  await controls()
  await screen.findByText('Running')
  expect(button('Start')).toBeDisabled()

  await user.click(button('Stop'))

  expect(writes.map((w) => w.url)).toEqual(['/api/selfhost/stop'])
  expect(await screen.findByText('Stopping')).toBeInTheDocument()
  expect(screen.getByText('stopping after 42 min · ~$0.56')).toBeInTheDocument()
  await act(async () => {
    await vi.advanceTimersByTimeAsync(POLL_MS)
  })
  expect(await screen.findByText('Stopped')).toBeInTheDocument()
  expect(button('Start')).toBeEnabled()
})

test('a refused Start shows the backend’s reason', async () => {
  const user = userEvent.setup()
  renderApp('/settings', { '/api/status': { ...fixtures.statusOff, ai: AI }, '/api/selfhost': STOPPED }, () =>
    Response.json({
      type: 'urn:finalysis:problem:selfhost-unavailable',
      title: 'Self-hosted server unavailable',
      status: 409,
      detail: 'AWS refused StartInstances (UnauthorizedOperation): check the IAM statements from create.sh in '
        + 'finalysis-dev-policy.',
    }, { status: 409 }))
  const own = await block()
  await within(own).findByText('Stopped')

  await user.click(button('Start'))

  expect(await within(own).findByRole('alert')).toHaveTextContent('AWS refused StartInstances (UnauthorizedOperation)')
})

test('a note after a start (the model didn’t load) is shown beside the running state', async () => {
  renderApp('/settings', {
    '/api/status': { ...fixtures.statusOff, ai: AI },
    '/api/selfhost': { ...RUNNING, note: 'The model didn’t load: Timed out: the server may be stopped or still starting.' },
  })
  await controls()

  expect(await screen.findByText(/The model didn’t load/)).toHaveClass('selfhost-note')
  expect(screen.getByText('Running')).toBeInTheDocument()
})

test('no tagged server: the reason in plain words, no state, both buttons off', async () => {
  const reason = 'No Self-hosted server tagged finalysis:role=selfhost in us-east-1. Create one with '
    + 'scripts/selfhost/create.sh.'
  renderApp('/settings', {
    '/api/status': { ...fixtures.statusOff, ai: AI },
    '/api/selfhost': { ...selfHostFixture, reason },
  })
  await controls()

  expect(await screen.findByText(reason)).toBeInTheDocument()
  expect(screen.queryByLabelText('Server status')).not.toBeInTheDocument()
  expect(button('Start')).toBeDisabled()
  expect(button('Stop')).toBeDisabled()
})
