import { useState, type FormEvent, type ReactNode } from 'react'
import { vault } from '../api'
import { AI_NAME } from '../corgi'
import { newPasswordProblem, vaultErrorText } from './vaultText'
import VaultScreen from './VaultScreen'

/**
 * The locked app (docs/plans/phase-8.md §6): the password, or "Forgot your password?" with the recovery key and a new
 * password twice. Errors in plain words; after too many attempts, the time to try again. Fields are cleared once sent.
 */
export default function UnlockScreen({ notice, onUnlocked }: { notice?: ReactNode; onUnlocked: () => void }) {
  const [mode, setMode] = useState<'password' | 'recovery'>('password')
  return mode === 'password' ? (
    <PasswordForm notice={notice} onUnlocked={onUnlocked} onForgot={() => setMode('recovery')} />
  ) : (
    <RecoveryForm notice={notice} onUnlocked={onUnlocked} onBack={() => setMode('password')} />
  )
}

function PasswordForm({
  notice,
  onUnlocked,
  onForgot,
}: {
  notice?: ReactNode
  onUnlocked: () => void
  onForgot: () => void
}) {
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!password) {
      return
    }
    setBusy(true)
    setError(null)
    try {
      await vault.unlock(password)
      setPassword('')
      onUnlocked()
    } catch (err) {
      setPassword('')
      setError(vaultErrorText(err))
      setBusy(false)
    }
  }

  return (
    <VaultScreen title="Finalysis is locked">
      {notice}
      <p>Enter your password to open your data.</p>
      <form className="vault-form" onSubmit={(e) => void submit(e)}>
        <label>
          Password
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
            autoFocus
          />
        </label>
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
        <button type="submit" className="button-primary" disabled={busy || !password}>
          {busy ? 'Unlocking…' : 'Unlock'}
        </button>
        {/* Unlock checks the path Corgi runs on (Phase 9): with Ollama selected, it can take a few seconds more. */}
        {busy && (
          <p className="muted vault-checking" role="status">
            Checking {AI_NAME}…
          </p>
        )}
      </form>
      <button type="button" className="link-button vault-switch" onClick={onForgot}>
        Forgot your password? Use your recovery key
      </button>
    </VaultScreen>
  )
}

function RecoveryForm({
  notice,
  onUnlocked,
  onBack,
}: {
  notice?: ReactNode
  onUnlocked: () => void
  onBack: () => void
}) {
  const [key, setKey] = useState('')
  const [password, setPassword] = useState('')
  const [again, setAgain] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    const problem = newPasswordProblem(password, again)
    if (problem) {
      setError(problem)
      return
    }
    setBusy(true)
    setError(null)
    try {
      await vault.recover(key, password)
      setKey('')
      setPassword('')
      setAgain('')
      onUnlocked()
    } catch (err) {
      setError(vaultErrorText(err))
      setBusy(false)
    }
  }

  return (
    <VaultScreen title="Unlock with your recovery key">
      {notice}
      <p>The recovery key opens the vault; then choose a new password. The old password stops working.</p>
      <form className="vault-form" onSubmit={(e) => void submit(e)}>
        <label>
          Recovery key
          <input
            value={key}
            onChange={(e) => setKey(e.target.value)}
            autoComplete="off"
            spellCheck={false}
            autoCapitalize="characters"
            aria-describedby="recovery-key-help"
            autoFocus
          />
        </label>
        <p id="recovery-key-help" className="muted">
          13 groups of four, as shown at setup. Dashes, spaces and case don’t matter.
        </p>
        <label>
          New password
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="new-password"
          />
        </label>
        <label>
          New password again
          <input
            type="password"
            value={again}
            onChange={(e) => setAgain(e.target.value)}
            autoComplete="new-password"
          />
        </label>
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
        <button type="submit" className="button-primary" disabled={busy || !key}>
          {busy ? 'Unlocking…' : 'Unlock and set the new password'}
        </button>
      </form>
      <button type="button" className="link-button vault-switch" onClick={onBack}>
        Back to the password
      </button>
    </VaultScreen>
  )
}
