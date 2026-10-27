import { useState, type FormEvent, type ReactNode } from 'react'
import { vault } from '../api'
import { MIN_PASSWORD_LENGTH, newPasswordProblem, vaultErrorText } from './vaultText'
import VaultScreen from './VaultScreen'

/**
 * First run (docs/plans/phase-8.md §4.5, §6): what the vault is, the password twice. On success the parent shows the
 * recovery key; the password is never kept.
 */
export default function SetupScreen({ notice, onCreated }: { notice?: ReactNode; onCreated: (key: string) => void }) {
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
      const { recoveryKey } = await vault.setup(password)
      setPassword('')
      setAgain('')
      onCreated(recoveryKey)
    } catch (err) {
      setError(vaultErrorText(err))
      setBusy(false)
    }
  }

  return (
    <VaultScreen title="Set up your vault">
      {notice}
      <p>
        Finalysis keeps your data in an encrypted vault on this computer. Only your password, or the recovery key you
        get next, can open it.
      </p>
      <form className="vault-form" onSubmit={(e) => void submit(e)}>
        <label>
          Password
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="new-password"
            aria-describedby="setup-password-help"
            autoFocus
          />
        </label>
        <label>
          Password again
          <input
            type="password"
            value={again}
            onChange={(e) => setAgain(e.target.value)}
            autoComplete="new-password"
          />
        </label>
        <p id="setup-password-help" className="muted">
          At least {MIN_PASSWORD_LENGTH} characters. A few unrelated words make a good one.
        </p>
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
        <button type="submit" className="button-primary" disabled={busy}>
          {busy ? 'Creating the vault…' : 'Create the vault'}
        </button>
      </form>
    </VaultScreen>
  )
}
