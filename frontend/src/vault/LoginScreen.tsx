import { useState, type FormEvent } from 'react'
import { access } from '../api'
import { vaultErrorText } from './vaultText'
import VaultScreen from './VaultScreen'

/**
 * The phone's login (docs/plans/phase-8.md §9.3): the vault's own password, over the LAN listener's HTTPS. It only
 * opens a session while the vault is unlocked on the computer; logging out is the Lock button, for the whole app.
 */
export default function LoginScreen({ onLoggedIn }: { onLoggedIn: () => void }) {
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
      await access.login(password)
      setPassword('')
      onLoggedIn()
    } catch (err) {
      setPassword('')
      setError(vaultErrorText(err))
      setBusy(false)
    }
  }

  return (
    <VaultScreen title="Log in to Finalysis">
      <p>Enter the password you use on your computer.</p>
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
          {busy ? 'Logging in…' : 'Log in'}
        </button>
      </form>
      <p className="muted">Lock logs you out here and locks Finalysis on the computer too.</p>
    </VaultScreen>
  )
}
