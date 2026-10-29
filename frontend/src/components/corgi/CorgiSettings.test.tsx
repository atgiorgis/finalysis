import '@testing-library/jest-dom/vitest'
import { cleanup, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, test, vi } from 'vitest'
import type { AiStatus } from '../../api'
import { fixtures } from '../../test/fixtures'
import { renderApp } from '../../test/renderApp'
import { NAME_RULE, REGION_RULE } from './CorgiSettings'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

const CLOUD_MODELS = { categorization: 'haiku-4.5', embedding: 'titan-v2', main: 'sonnet-4.6' }
const OLLAMA_MODELS = { categorization: 'ministral-3:14b', embedding: 'nomic-embed-text', main: 'ministral-3:14b' }

const LOCAL_BANNER = 'Corgi runs on this computer: nothing is sent anywhere.'
const CLOUD_BANNER =
  'Cloud AI is on (Amazon Bedrock): merchant names and amounts are sent for categorization, and chat questions with ' +
  'the figures they need are sent for answers.'
const selfHostedBanner = (name: string) =>
  `Corgi runs on your own server (${name}): merchant names and amounts are sent to it for categorization, and chat ` +
  'questions with the figures they need are sent for answers.'

function ai(overrides: Partial<AiStatus> = {}): AiStatus {
  return {
    path: 'CLOUD',
    available: ['CLOUD', 'SELF_HOSTED', 'LOCAL'],
    reachable: true,
    provider: 'bedrock',
    models: CLOUD_MODELS,
    pathModels: { CLOUD: CLOUD_MODELS, SELF_HOSTED: OLLAMA_MODELS, LOCAL: OLLAMA_MODELS },
    ...overrides,
  }
}

/** A status whose `ai` block the test can change, as a switch changes it. */
function backend(initial: AiStatus) {
  let current = initial
  return {
    set(next: AiStatus) {
      current = next
    },
    route: () => Response.json({ ...fixtures.statusOff, ai: current }),
  }
}

function card(name: string): HTMLElement {
  return screen.getByRole('radio', { name: new RegExp(`^${name}`) }).closest('.path-card') as HTMLElement
}

async function corgi(): Promise<HTMLElement> {
  return screen.findByRole('region', { name: 'Corgi' })
}

test('three cards, each with its models, its badge in its colour and the banner it would show, and Off', async () => {
  const status = backend(ai())
  renderApp('/settings', { '/api/status': status.route })
  const section = await corgi()

  expect(within(section).getAllByRole('radio').map((r) => (r as HTMLInputElement).value)).toEqual([
    'CLOUD', 'SELF_HOSTED', 'LOCAL', 'OFF',
  ])
  expect(within(section).getByRole('radio', { name: /^Cloud/ })).toBeChecked()

  const cloud = card('Cloud')
  expect(within(cloud).getByText('Cloud · Bedrock')).toHaveClass('path-badge-cloud')
  expect(within(cloud).getByText('sonnet-4.6')).toBeInTheDocument()
  expect(within(cloud).getByText('titan-v2')).toBeInTheDocument()
  expect(cloud).toHaveTextContent(CLOUD_BANNER)

  const local = card('Local')
  expect(local.querySelector('.path-badge')).toHaveClass('path-badge-local')
  expect(local.querySelector('.path-badge')).toHaveTextContent(/^Local$/)
  expect(within(local).getAllByText('ministral-3:14b')).toHaveLength(2)
  expect(within(local).getByText('nomic-embed-text')).toBeInTheDocument()
  expect(local).toHaveTextContent(LOCAL_BANNER)

  const selfHosted = card('Self-hosted')
  expect(selfHosted.querySelector('.path-badge')).toHaveClass('path-badge-self-hosted')
  expect(within(selfHosted).getByText('Corgi · chat')).toBeInTheDocument()

  expect(within(section).getByRole('radio', { name: /^Off: categorization uses rules only/ })).not.toBeChecked()
})

test('a path the backend can’t offer is disabled with the reason', async () => {
  renderApp('/settings', {
    '/api/status': backend(ai({ path: 'LOCAL', available: ['SELF_HOSTED', 'LOCAL'], provider: 'ollama',
      pathModels: { SELF_HOSTED: OLLAMA_MODELS, LOCAL: OLLAMA_MODELS } })).route,
  })
  await corgi()

  expect(screen.getByRole('radio', { name: /^Cloud/ })).toBeDisabled()
  expect(within(card('Cloud')).getByText('Needs the aws profile.')).toBeInTheDocument()
  expect(screen.getByRole('radio', { name: /^Local/ })).toBeEnabled()
  expect(screen.getByRole('radio', { name: /^Local/ })).toBeChecked()
})

test('switching asks once, sends the path, then the badge and the banner follow without a reload', async () => {
  const user = userEvent.setup()
  const status = backend(ai())
  const { writes } = renderApp('/settings', { '/api/status': status.route }, (write) => {
    if (write.url === '/api/settings/ai') {
      const next = ai({ path: 'LOCAL', provider: 'ollama', models: OLLAMA_MODELS })
      status.set(next)
      return Response.json(next)
    }
  })
  await corgi()
  expect(await screen.findByText(CLOUD_BANNER, { selector: '.corgi-banner' })).toBeInTheDocument()

  await user.click(screen.getByRole('radio', { name: /^Local/ }))
  const confirm = screen.getByRole('group', { name: 'Switch Corgi to Local?' })
  expect(writes).toEqual([])
  await user.click(within(confirm).getByRole('button', { name: 'Switch' }))

  expect(await screen.findByText(LOCAL_BANNER, { selector: '.corgi-banner' })).toBeInTheDocument()
  expect(writes).toEqual([{ method: 'PUT', url: '/api/settings/ai', body: { path: 'LOCAL' } }])
  await waitFor(() => expect(screen.getByRole('radio', { name: /^Local/ })).toBeChecked())
  expect(screen.queryByRole('group', { name: /^Switch Corgi/ })).not.toBeInTheDocument()
})

test('Cancel sends nothing', async () => {
  const user = userEvent.setup()
  const { writes } = renderApp('/settings', { '/api/status': backend(ai()).route })
  await corgi()

  await user.click(screen.getByRole('radio', { name: /^Off/ }))
  await user.click(within(screen.getByRole('group', { name: 'Switch Corgi to Off?' })).getByRole('button', { name: 'Cancel' }))

  expect(writes).toEqual([])
  expect(screen.getByRole('radio', { name: /^Cloud/ })).toBeChecked()
})

test('a 409 ai-busy is shown as its detail, and the path stays', async () => {
  const user = userEvent.setup()
  const detail = 'Corgi is busy: a categorization run or a chat answer is still running after 60 seconds. Try again ' +
    'when it has finished.'
  renderApp('/settings', { '/api/status': backend(ai()).route }, () =>
    Response.json({ type: 'urn:finalysis:problem:ai-busy', title: 'Corgi is busy', status: 409, detail,
      reason: 'AI work still running' }, { status: 409 }))
  const section = await corgi()

  await user.click(screen.getByRole('radio', { name: /^Local/ }))
  await user.click(screen.getByRole('button', { name: 'Switch' }))

  expect(await within(section).findByRole('alert')).toHaveTextContent(detail)
  expect(screen.getByRole('radio', { name: /^Cloud/ })).toBeChecked()
})

test('a refused field is named, as the backend says it', async () => {
  const user = userEvent.setup()
  renderApp('/settings', { '/api/status': backend(ai()).route }, () =>
    Response.json({ title: 'Invalid request', status: 400, detail: 'One or more fields are invalid',
      errors: [{ field: 'path', message: 'CLOUD isn’t available: Cloud needs the aws profile' }] }, { status: 400 }))
  const section = await corgi()

  await user.click(screen.getByRole('radio', { name: /^Off/ }))
  await user.click(screen.getByRole('button', { name: 'Switch' }))

  expect(await within(section).findByRole('alert')).toHaveTextContent('path CLOUD isn’t available: Cloud needs the aws profile')
})

test('a 423 locks the app, as every write does', async () => {
  const user = userEvent.setup()
  let locked = false
  renderApp('/settings', {
    '/api/status': () => Response.json({ ...fixtures.statusOff, ai: ai(),
      vault: { ...fixtures.statusOff.vault, storage: 'VAULT', setUp: true, state: locked ? 'LOCKED' : 'UNLOCKED' } }),
  }, () => {
    locked = true
    return Response.json({ type: 'urn:finalysis:problem:vault-locked', title: 'Locked', status: 423 }, { status: 423 })
  })
  await corgi()

  await user.click(screen.getByRole('radio', { name: /^Local/ }))
  await user.click(screen.getByRole('button', { name: 'Switch' }))

  expect(await screen.findByRole('heading', { name: 'Finalysis is locked' })).toBeInTheDocument()
})

test('Self-hosted needs a Tailscale name, never a web address, and a region like the backend takes it', async () => {
  const user = userEvent.setup()
  const status = backend(ai())
  const { writes } = renderApp('/settings', { '/api/status': status.route }, (write) => {
    const next = ai({ path: 'SELF_HOSTED', provider: 'ollama', server: 'finalysis-selfhost', models: OLLAMA_MODELS })
    status.set(next)
    return Response.json(next)
  })
  await corgi()
  const selfHosted = card('Self-hosted')

  await user.click(screen.getByRole('radio', { name: /^Self-hosted/ }))
  expect(within(selfHosted).getByRole('alert')).toHaveTextContent('Enter the server’s Tailscale name first.')

  for (const bad of ['http://finalysis-selfhost:11434', 'two words', 'a..b', 'user@host']) {
    await user.clear(within(selfHosted).getByLabelText('Tailscale name'))
    await user.type(within(selfHosted).getByLabelText('Tailscale name'), bad)
    await user.click(screen.getByRole('radio', { name: /^Self-hosted/ }))
    expect(within(selfHosted).getByRole('alert')).toHaveTextContent(NAME_RULE)
  }
  await user.clear(within(selfHosted).getByLabelText('Tailscale name'))
  await user.type(within(selfHosted).getByLabelText('Tailscale name'), 'finalysis-selfhost')
  await user.type(within(selfHosted).getByLabelText('AWS region'), 'US_EAST_1')
  await user.click(screen.getByRole('radio', { name: /^Self-hosted/ }))
  expect(within(selfHosted).getByRole('alert')).toHaveTextContent(REGION_RULE)
  expect(writes).toEqual([])

  await user.clear(within(selfHosted).getByLabelText('AWS region'))
  await user.type(within(selfHosted).getByLabelText('AWS region'), 'us-east-1')
  expect(selfHosted).toHaveTextContent(selfHostedBanner('finalysis-selfhost'))
  expect(within(selfHosted).getByText('Self-hosted · finalysis-selfhost')).toBeInTheDocument()
  await user.click(screen.getByRole('radio', { name: /^Self-hosted/ }))
  await user.click(screen.getByRole('button', { name: 'Switch' }))

  expect(await screen.findByText(selfHostedBanner('finalysis-selfhost'), { selector: '.corgi-banner' }))
    .toBeInTheDocument()
  expect(writes).toEqual([{ method: 'PUT', url: '/api/settings/ai',
    body: { path: 'SELF_HOSTED', selfhostName: 'finalysis-selfhost', selfhostRegion: 'us-east-1' } }])
})

test('the server controls are there but not available yet', async () => {
  renderApp('/settings', { '/api/status': backend(ai()).route })
  await corgi()
  const controls = within(card('Self-hosted')).getByRole('group', { name: 'Server controls' })

  for (const name of ['Test', 'Start', 'Stop']) {
    expect(within(controls).getByRole('button', { name })).toBeDisabled()
  }
  expect(controls).toHaveTextContent('Not available yet.')
  expect(controls.textContent).not.toMatch(/9c|step/i)
})

test('a path that can’t run says why, and how to get Corgi back', async () => {
  renderApp('/settings', {
    '/api/status': backend(ai({ path: 'LOCAL', provider: 'ollama', models: OLLAMA_MODELS, reachable: false,
      reason: 'Ollama unreachable' })).route,
  })
  const section = await corgi()

  expect(within(section).getByText('Corgi is off: Ollama unreachable. Install Ollama or choose a path in Settings.'))
    .toBeInTheDocument()
  expect(screen.getByRole('radio', { name: /^Local/ })).toBeChecked()
})
