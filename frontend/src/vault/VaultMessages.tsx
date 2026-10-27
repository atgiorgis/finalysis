import { useState } from 'react'
import { vault, type VaultStatus } from '../api'
import { vaultErrorText } from './vaultText'
import VaultScreen from './VaultScreen'

/** NOT_READY: the startup check's sentence above the setup or unlock form, which checks again when sent. */
export function NotReadyNotice({ status }: { status: VaultStatus }) {
  return (
    <div role="alert" className="vault-notice">
      <p>
        <strong>Finalysis can’t open its database yet.</strong>
      </p>
      <p>{status.message ?? 'The vault’s database check did not pass. See the README’s setup section.'}</p>
      <p className="muted">Fix it, then try again below: every attempt checks again.</p>
    </div>
  )
}

/** BROKEN: a key file without its image (or the reverse), or the image open elsewhere. Never repaired by the app. */
export function BrokenScreen({ status, onRetry }: { status: VaultStatus; onRetry: () => void }) {
  return (
    <VaultScreen title="The vault needs attention">
      <p role="alert">{status.message ?? 'The vault’s files are not as Finalysis left them.'}</p>
      <p className="muted">
        Finalysis never makes a new vault over an old one, so nothing was changed. The README’s vault section says
        how to restore the files from a backup.
      </p>
      <button type="button" className="button-secondary" onClick={onRetry}>
        Check again
      </button>
    </VaultScreen>
  )
}

/** LOCK_FAILED: a part of the lock failed; nothing is served until a lock finishes. */
export function LockFailedScreen({ status, onDone }: { status: VaultStatus; onDone: () => void }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function lockAgain() {
    setBusy(true)
    setError(null)
    try {
      await vault.lock()
    } catch (err) {
      setError(vaultErrorText(err))
    }
    setBusy(false)
    onDone()
  }

  return (
    <VaultScreen title="Locking didn’t finish">
      <p role="alert">{status.message ?? 'A part of the lock failed.'}</p>
      <p className="muted">Your data isn’t shown while this lasts. Try Lock again; if it keeps failing, restart the computer.</p>
      {error && <p className="error">{error}</p>}
      <button type="button" className="button-primary" onClick={() => void lockAgain()} disabled={busy}>
        {busy ? 'Locking…' : 'Lock again'}
      </button>
    </VaultScreen>
  )
}

/** LOCKING or UNLOCKING: a transition is under way (the parent checks again shortly). */
export function BusyScreen({ status }: { status: VaultStatus }) {
  return (
    <VaultScreen title={status.state === 'LOCKING' ? 'Locking…' : 'Unlocking…'}>
      <p role="status" className="muted">
        This takes a few seconds.
      </p>
    </VaultScreen>
  )
}

/** The backend didn't answer the status at all. */
export function UnreachableScreen({ error, onRetry }: { error: string; onRetry: () => void }) {
  return (
    <VaultScreen title="Finalysis isn’t answering">
      <p role="alert" className="error">
        Backend unreachable: {error}
      </p>
      <button type="button" className="button-secondary" onClick={onRetry}>
        Try again
      </button>
    </VaultScreen>
  )
}
