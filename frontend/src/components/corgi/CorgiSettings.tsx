import { useEffect, useState } from 'react'
import { aiSettings, ApiProblem, type AiPath, type AiStatus } from '../../api'
import {
  AI_NAME,
  PATH_NAMES,
  PATHS,
  badgeFor,
  bannerText,
  offText,
  pathTone,
  validRegion,
  validServerName,
} from '../../corgi'
import { useDataVersion } from '../../shell/ShellData'

/** Corgi's roles by what it does; the search embedding isn't Corgi talking, so it keeps its own name. */
const ROLE_LABELS: Record<string, string> = {
  main: `${AI_NAME} · chat`,
  categorization: `${AI_NAME} · categorization`,
  embedding: 'Search (embedding) model',
}

/** Chat first, as the brief lists them. */
const ROLE_ORDER = ['main', 'categorization', 'embedding']

const WHAT_IT_IS: Record<Exclude<AiPath, 'OFF'>, string> = {
  CLOUD: 'Amazon Bedrock, in the cloud.',
  SELF_HOSTED: 'Your own server, reached over Tailscale.',
  LOCAL: 'This computer, through Ollama.',
}

const OFF_TEXT = 'Off: categorization uses rules only, and chat is unavailable.'

export const NAME_RULE = 'Letters, digits, hyphens and dots, as Tailscale names it: not a web address.'
export const REGION_RULE = 'An AWS region id, like us-east-1.'

function orderedRoles(models: Record<string, string>): (readonly [string, string])[] {
  return [
    ...ROLE_ORDER.filter((role) => role in models),
    ...Object.keys(models).filter((role) => !ROLE_ORDER.includes(role)),
  ].map((role) => [role, models[role]] as const)
}

function Badge({ path, ai }: { path: AiPath; ai: AiStatus | null }) {
  const badge = badgeFor(path, ai)
  return (
    <span className={`path-badge path-badge-${badge.tone}`}>
      <span className="path-badge-dot" aria-hidden="true" />
      {badge.label}
    </span>
  )
}

/** A switch the user asked for, waiting for their yes. */
interface Pending {
  path: AiPath
  selfhostName?: string
  selfhostRegion?: string
}

/**
 * Settings → Corgi (docs/plans/phase-9.md §3.4): where Corgi runs, as three radio cards and Off. Each card shows its
 * models, its badge and the banner wording it would show; a path the backend can't offer is disabled with the reason.
 * Choosing one asks once, switches through `PUT /api/settings/ai`, then reads the status again, so the badge and the
 * banner change without a reload. A 409 (Corgi still busy) is shown as its detail; a 423 locks the app, as every write
 * does.
 */
export default function CorgiSettings({ ai }: { ai: AiStatus }) {
  const { statusChanged } = useDataVersion()
  const [pending, setPending] = useState<Pending | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [name, setName] = useState(ai.server ?? '')
  const [region, setRegion] = useState('')
  const [nameError, setNameError] = useState<string | null>(null)
  const selected = ai.path ?? 'OFF'
  const available = ai.available ?? []
  const off = offText(ai)

  useEffect(() => {
    if (ai.server) {
      setName(ai.server)
    }
  }, [ai.server])

  function ask(path: AiPath) {
    setError(null)
    setNameError(null)
    if (path === 'SELF_HOSTED') {
      const trimmed = name.trim()
      const regionTrimmed = region.trim()
      if (!validServerName(trimmed)) {
        setNameError(trimmed ? NAME_RULE : 'Enter the server’s Tailscale name first.')
        return
      }
      if (regionTrimmed && !validRegion(regionTrimmed)) {
        setNameError(REGION_RULE)
        return
      }
      setPending({ path, selfhostName: trimmed, selfhostRegion: regionTrimmed || undefined })
      return
    }
    setPending({ path })
  }

  async function confirm() {
    if (!pending) {
      return
    }
    setBusy(true)
    setError(null)
    try {
      await aiSettings.switchTo(pending)
      setPending(null)
      await statusChanged()
    } catch (e) {
      if (e instanceof ApiProblem) {
        const errors = e.body.errors as { field: string; message: string }[] | undefined
        setError(errors?.length ? errors.map((f) => `${f.field} ${f.message}`).join('; ') : e.message)
      } else {
        setError((e as Error).message)
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="card corgi-settings" aria-labelledby="settings-ai">
      <h2 id="settings-ai">{AI_NAME}</h2>
      <p className="muted">
        {AI_NAME} is the name Finalysis gives its AI. Choose where it runs; you can change it at any time.
      </p>
      {off && (
        <p className="corgi-off" role="status">
          {off}
        </p>
      )}
      <fieldset className="path-cards">
        <legend className="visually-hidden">Where {AI_NAME} runs</legend>
        {PATHS.map((path) => {
          const unavailable = !available.includes(path)
          const models = ai.pathModels?.[path] ?? {}
          const isSelected = selected === path
          return (
            <div
              key={path}
              className={`path-card path-card-${pathTone(path)}${isSelected ? ' path-card-selected' : ''}${
                unavailable ? ' path-card-unavailable' : ''
              }`}
            >
              <label className="path-card-choice">
                <input
                  type="radio"
                  name="corgi-path"
                  value={path}
                  checked={isSelected}
                  disabled={unavailable || busy}
                  onChange={() => ask(path)}
                />
                <span className="path-card-title">{PATH_NAMES[path]}</span>
                <Badge path={path} ai={path === 'SELF_HOSTED' && name ? { ...ai, server: name } : ai} />
              </label>
              <p className="muted path-card-what">{WHAT_IT_IS[path]}</p>
              {unavailable && <p className="path-card-reason">Needs the aws profile.</p>}
              {Object.keys(models).length > 0 && (
                <dl className="facts path-card-models">
                  {orderedRoles(models).map(([role, model]) => (
                    <div key={role} className="facts-row">
                      <dt>{ROLE_LABELS[role] ?? role}</dt>
                      <dd>
                        <code>{model}</code>
                      </dd>
                    </div>
                  ))}
                </dl>
              )}
              <p className="path-card-banner">
                <span className="muted">Banner: </span>
                {bannerText(path, path === 'SELF_HOSTED' ? { ...ai, server: name || undefined } : ai)}
              </p>
              {path === 'SELF_HOSTED' && (
                <div className="path-card-server">
                  <label>
                    Tailscale name
                    <input
                      type="text"
                      value={name}
                      autoComplete="off"
                      spellCheck={false}
                      placeholder="finalysis-selfhost"
                      onChange={(e) => setName(e.target.value)}
                    />
                  </label>
                  <label>
                    AWS region
                    <input
                      type="text"
                      value={region}
                      autoComplete="off"
                      spellCheck={false}
                      placeholder="us-east-1"
                      onChange={(e) => setRegion(e.target.value)}
                    />
                  </label>
                  <p className="muted">{NAME_RULE}</p>
                  {nameError && (
                    <p className="error" role="alert">
                      {nameError}
                    </p>
                  )}
                  {isSelected && (
                    <button type="button" className="button-secondary" disabled={busy} onClick={() => ask('SELF_HOSTED')}>
                      Use this server
                    </button>
                  )}
                  <div className="path-card-later" role="group" aria-label="Server controls">
                    {['Test', 'Start', 'Stop'].map((control) => (
                      <button key={control} type="button" className="button-secondary" disabled title="Not available yet">
                        {control}
                      </button>
                    ))}
                    <span className="muted">Not available yet.</span>
                  </div>
                </div>
              )}
            </div>
          )
        })}
        <label className="path-off">
          <input
            type="radio"
            name="corgi-path"
            value="OFF"
            checked={selected === 'OFF'}
            disabled={busy}
            onChange={() => ask('OFF')}
          />
          {OFF_TEXT}
        </label>
      </fieldset>
      {pending && (
        <div className="corgi-confirm" role="group" aria-label={`Switch ${AI_NAME} to ${PATH_NAMES[pending.path]}?`}>
          <span>
            Switch {AI_NAME} to {PATH_NAMES[pending.path]}?
          </span>
          <button type="button" className="button-primary" disabled={busy} onClick={() => void confirm()}>
            {busy ? 'Switching…' : 'Switch'}
          </button>
          <button type="button" className="button-secondary" disabled={busy} onClick={() => setPending(null)}>
            Cancel
          </button>
        </div>
      )}
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
    </section>
  )
}
