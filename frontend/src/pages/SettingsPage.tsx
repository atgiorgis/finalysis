import { useEffect, useState } from 'react'
import { reads, type AiStatus, type ChatDailyUsage } from '../api'
import { AI_NAME, costText } from '../corgi'
import { formatCount, formatDate, formatMoney, formatMonth } from '../format'
import { providerName } from '../shell/CloudAiBanner'
import { useShellData } from '../shell/ShellData'
import PhoneAccessSettings from '../vault/PhoneAccessSettings'
import VaultSettings from '../vault/VaultSettings'
import AfterImportSettings from '../components/import/AfterImportSettings'

/** Corgi's roles by what it does; the search embedding isn't Corgi talking, so it keeps its own name. */
const ROLE_LABELS: Record<string, string> = {
  main: `${AI_NAME} · chat`,
  categorization: `${AI_NAME} · categorization`,
  embedding: 'Search (embedding) model',
}

/** Chat first, as the brief lists them. */
const ROLE_ORDER = ['main', 'categorization', 'embedding']

const MODE_TEXT: Record<AiStatus['mode'], string> = {
  OFF: 'Off. Categorization uses rules only, and chat is unavailable.',
  CLOUD: 'Cloud',
  LOCAL: 'Local',
}

function AiSection({ ai }: { ai: AiStatus }) {
  const models = ai.models ?? {}
  const roles = [
    ...ROLE_ORDER.filter((role) => role in models),
    ...Object.keys(models).filter((role) => !ROLE_ORDER.includes(role)),
  ].map((role) => [role, models[role]] as const)
  return (
    <section className="card" aria-labelledby="settings-ai">
      <h2 id="settings-ai">{AI_NAME}</h2>
      <p className="muted">{AI_NAME} is the name Finalysis gives its AI. The models behind it are listed below.</p>
      <dl className="facts">
        <dt>Mode</dt>
        <dd>{ai.mode === 'CLOUD' ? `Cloud (${providerName(ai)})` : MODE_TEXT[ai.mode]}</dd>
        {roles.map(([role, model]) => (
          <div key={role} className="facts-row">
            <dt>{ROLE_LABELS[role] ?? role}</dt>
            <dd>
              <code>{model}</code>
            </dd>
          </div>
        ))}
      </dl>
    </section>
  )
}

function cap(value: string | null): string {
  return value === null ? 'Off' : formatMoney(value)
}

function UsageSection({ usage, error, ai }: { usage: ChatDailyUsage | null; error: string | null; ai: AiStatus | null }) {
  const local = ai?.mode === 'LOCAL'
  return (
    <section className="card" aria-labelledby="settings-usage">
      <h2 id="settings-usage">Chat costs</h2>
      {error && <p className="error">Couldn’t read the chat usage: {error}</p>}
      {!error && !usage && <p className="muted">Loading…</p>}
      {usage && (
        <dl className="facts">
          <dt>Spent today ({formatDate(usage.day, true)})</dt>
          <dd>{local ? costText(usage.spentToday, ai) : formatMoney(usage.spentToday)}</dd>
          <dt>This month ({formatMonth(usage.month)})</dt>
          <dd>
            {costText(usage.spentThisMonth, ai)}
            {' · '}
            {usage.questionsThisMonth === 1 ? '1 question' : `${formatCount(usage.questionsThisMonth)} questions`}
            {!local && <span className="muted"> · estimated from list prices</span>}
          </dd>
          <dt>Daily limit</dt>
          <dd>{cap(usage.maxDailyCost)}</dd>
          <dt>Limit per question</dt>
          <dd>{cap(usage.maxTurnCost)}</dd>
        </dl>
      )}
      <p className="muted">
        Estimated from each model call’s tokens. The daily spend starts again at midnight.
      </p>
    </section>
  )
}

/**
 * Settings: the vault first (editable since Phase 8 step 3: auto-lock, password, Lock now), then Corgi's mode and
 * models and the chat costs, read-only (docs/plans/phase-7.md §2.8).
 */
export default function SettingsPage() {
  const { status } = useShellData()
  const [usage, setUsage] = useState<ChatDailyUsage | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    reads
      .chatUsage()
      .then(setUsage)
      .catch((e: Error) => setError(e.message))
  }, [])

  return (
    <section className="page">
      <h1>Settings</h1>
      <VaultSettings />
      <PhoneAccessSettings />
      <AfterImportSettings />
      <p className="muted">{AI_NAME}’s models and the spending limits below are set in the backend’s configuration.</p>
      {status && <AiSection ai={status.ai} />}
      <UsageSection usage={usage} error={error} ai={status?.ai ?? null} />
    </section>
  )
}
