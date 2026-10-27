import { useState, type FormEvent } from 'react'
import { vault } from '../api'
import { useVault } from './VaultContext'
import { newPasswordProblem, vaultErrorText } from './vaultText'

/**
 * Settings → Vault (docs/plans/phase-8.md §6): where the data is, auto-lock minutes, change password, Lock now. With
 * the vault off (the plain dev database) it says so and offers nothing. Statement files and phone access join it in
 * steps 4 and 5.
 */
export default function VaultSettings() {
  const { status } = useVault()
  if (!status) {
    return null
  }
  if (status.storage === 'PLAIN') {
    return (
      <section className="card" aria-labelledby="settings-vault">
        <h2 id="settings-vault">Data</h2>
        <p className="muted">
          The plain development database: the vault is off (FINALYSIS_VAULT_ENABLED=false). Use it with sample data only.
        </p>
      </section>
    )
  }
  return (
    <section className="card" aria-labelledby="settings-vault">
      <h2 id="settings-vault">Vault</h2>
      <p className="muted">Your data is in an encrypted vault on this computer, open while you use Finalysis.</p>
      {status.collation && (
        <p role="note" className="vault-notice">
          {status.collation.action}
        </p>
      )}
      <AutoLockForm minutes={status.idleLockMinutes} />
      <PasswordForm />
      <LockNow />
    </section>
  )
}

function AutoLockForm({ minutes }: { minutes: number }) {
  const { refresh } = useVault()
  const [value, setValue] = useState(String(minutes))
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    const next = Number(value)
    if (!Number.isInteger(next) || next < 1 || next > 240) {
      setMessage({ ok: false, text: 'Auto-lock must be between 1 and 240 minutes.' })
      return
    }
    setBusy(true)
    setMessage(null)
    try {
      await vault.saveSettings(next)
      setMessage({ ok: true, text: `Saved: the vault locks after ${next} idle minutes.` })
      await refresh()
    } catch (err) {
      setMessage({ ok: false, text: vaultErrorText(err) })
    }
    setBusy(false)
  }

  return (
    <form className="settings-form" aria-labelledby="auto-lock-title" noValidate onSubmit={(e) => void submit(e)}>
      <h3 id="auto-lock-title">Auto-lock</h3>
      <label>
        Lock after this many idle minutes
        <input
          type="number"
          inputMode="numeric"
          min={1}
          max={240}
          value={value}
          onChange={(e) => setValue(e.target.value)}
        />
      </label>
      <p className="muted">It also locks when the computer wakes from sleep and when the backend stops.</p>
      {message && (
        <p role={message.ok ? 'status' : 'alert'} className={message.ok ? undefined : 'error'}>
          {message.text}
        </p>
      )}
      <button type="submit" className="button-secondary" disabled={busy}>
        Save
      </button>
    </form>
  )
}

function PasswordForm() {
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [again, setAgain] = useState('')
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    const problem = newPasswordProblem(next, again)
    if (problem) {
      setMessage({ ok: false, text: problem })
      return
    }
    setBusy(true)
    setMessage(null)
    try {
      await vault.changePassword(current, next)
      setCurrent('')
      setNext('')
      setAgain('')
      setMessage({ ok: true, text: 'Password changed. Your recovery key still works.' })
    } catch (err) {
      setMessage({ ok: false, text: vaultErrorText(err) })
    }
    setBusy(false)
  }

  return (
    <form className="settings-form" aria-labelledby="password-title" onSubmit={(e) => void submit(e)}>
      <h3 id="password-title">Change password</h3>
      <label>
        Current password
        <input
          type="password"
          value={current}
          onChange={(e) => setCurrent(e.target.value)}
          autoComplete="current-password"
        />
      </label>
      <label>
        New password
        <input type="password" value={next} onChange={(e) => setNext(e.target.value)} autoComplete="new-password" />
      </label>
      <label>
        New password again
        <input type="password" value={again} onChange={(e) => setAgain(e.target.value)} autoComplete="new-password" />
      </label>
      {message && (
        <p role={message.ok ? 'status' : 'alert'} className={message.ok ? undefined : 'error'}>
          {message.text}
        </p>
      )}
      <button type="submit" className="button-secondary" disabled={busy || !current}>
        {busy ? 'Changing…' : 'Change password'}
      </button>
    </form>
  )
}

function LockNow() {
  const { lock } = useVault()
  return (
    <div className="settings-form">
      <h3>Lock now</h3>
      <p className="muted">Closes the vault until you enter your password again.</p>
      <button type="button" className="button-primary" onClick={() => void lock()}>
        Lock now
      </button>
    </div>
  )
}
