import { useEffect, useState } from 'react'
import { reads, type AiStatus, type ChatDailyUsage } from '../api'
import { formatDate, formatMoney } from '../format'
import { providerName } from '../shell/CloudAiBanner'
import { useShellData } from '../shell/ShellData'

const ROLE_LABELS: Record<string, string> = {
  main: 'Chat',
  categorization: 'Categorization',
  embedding: 'Search (embedding)',
}

const MODE_TEXT: Record<AiStatus['mode'], string> = {
  OFF: 'Off. Categorization uses rules only, and chat is unavailable.',
  CLOUD: 'Cloud',
  LOCAL: 'Local',
}

function AiSection({ ai }: { ai: AiStatus }) {
  const roles = Object.entries(ai.models ?? {})
  return (
    <section className="card" aria-labelledby="settings-ai">
      <h2 id="settings-ai">AI</h2>
      <dl className="facts">
        <dt>Mode</dt>
        <dd>{ai.mode === 'CLOUD' ? `Cloud (${providerName(ai)})` : MODE_TEXT[ai.mode]}</dd>
        {roles.map(([role, model]) => (
          <div key={role} className="facts-row">
            <dt>{ROLE_LABELS[role] ?? role} model</dt>
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

function UsageSection({ usage, error }: { usage: ChatDailyUsage | null; error: string | null }) {
  return (
    <section className="card" aria-labelledby="settings-usage">
      <h2 id="settings-usage">Chat costs</h2>
      {error && <p className="error">Couldn’t read the chat usage: {error}</p>}
      {!error && !usage && <p className="muted">Loading…</p>}
      {usage && (
        <dl className="facts">
          <dt>Spent today ({formatDate(usage.day, true)})</dt>
          <dd>{formatMoney(usage.spentToday)}</dd>
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

/** Settings, read-only in Phase 7 (docs/plans/phase-7.md §2.8): the AI mode and models, the chat costs. */
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
      <p className="muted">Read-only: these are set in the backend’s configuration.</p>
      {status && <AiSection ai={status.ai} />}
      <UsageSection usage={usage} error={error} />
    </section>
  )
}
