import { useEffect, useState } from 'react'
import { reads, type AiStatus, type ChatDailyUsage } from '../api'
import { AI_NAME, costText } from '../corgi'
import CorgiSettings from '../components/corgi/CorgiSettings'
import { formatCount, formatDate, formatMoney, formatMonth } from '../format'
import { useShellData } from '../shell/ShellData'
import PhoneAccessSettings from '../vault/PhoneAccessSettings'
import VaultSettings from '../vault/VaultSettings'
import AfterImportSettings from '../components/import/AfterImportSettings'

function cap(value: string | null): string {
  return value === null ? 'Off' : formatMoney(value)
}

/**
 * The chat costs (docs/plans/phase-9.md §4.2): Cloud's estimates and caps as before; on Local `$0.00 · local`, on
 * Self-hosted "No token cost on Self-hosted.", and no caps on either (they apply on Cloud only).
 */
function UsageSection({ usage, error, ai }: { usage: ChatDailyUsage | null; error: string | null; ai: AiStatus | null }) {
  const cloud = !ai?.path || ai.path === 'CLOUD' || ai.path === 'OFF'
  return (
    <section className="card" aria-labelledby="settings-usage">
      <h2 id="settings-usage">Chat costs</h2>
      {error && <p className="error">Couldn’t read the chat usage: {error}</p>}
      {!error && !usage && <p className="muted">Loading…</p>}
      {usage && (
        <dl className="facts">
          <dt>Spent today ({formatDate(usage.day, true)})</dt>
          <dd>{cloud ? formatMoney(usage.spentToday) : costText(usage.spentToday, ai)}</dd>
          <dt>This month ({formatMonth(usage.month)})</dt>
          <dd>
            {costText(usage.spentThisMonth, ai)}
            {' · '}
            {usage.questionsThisMonth === 1 ? '1 question' : `${formatCount(usage.questionsThisMonth)} questions`}
            {cloud && <span className="muted"> · estimated from list prices</span>}
          </dd>
          {cloud && (
            <>
              <dt>Daily limit</dt>
              <dd>{cap(usage.maxDailyCost)}</dd>
              <dt>Limit per question</dt>
              <dd>{cap(usage.maxTurnCost)}</dd>
            </>
          )}
        </dl>
      )}
      <p className="muted">
        Estimated from each model call’s tokens. The daily spend starts again at midnight.
      </p>
    </section>
  )
}

/**
 * Settings: the vault first (editable since Phase 8 step 3: auto-lock, password, Lock now), then where Corgi runs
 * (Phase 9: a choice, switched without a restart) and the chat costs, read-only (docs/plans/phase-7.md §2.8).
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
      {status && <CorgiSettings ai={status.ai} />}
      <UsageSection usage={usage} error={error} ai={status?.ai ?? null} />
    </section>
  )
}
