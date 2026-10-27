import { useState } from 'react'
import { keyGroups, RECOVERY_WARNING } from './vaultText'
import VaultScreen from './VaultScreen'

/**
 * The recovery key, shown once after setup (docs/plans/phase-8.md §4.5, §6): in its 13 groups, with Copy and the
 * warning. The user types the last group back before the app opens, so the key was at least read. The key lives only
 * in this screen's props; the parent drops it on Continue.
 */
export default function RecoveryKeyScreen({ recoveryKey, onContinue }: { recoveryKey: string; onContinue: () => void }) {
  const groups = keyGroups(recoveryKey)
  const last = groups[groups.length - 1]
  const [typed, setTyped] = useState('')
  const [copied, setCopied] = useState<'yes' | 'failed' | null>(null)
  const matches = typed.trim().toUpperCase() === last

  async function copy() {
    try {
      await navigator.clipboard.writeText(recoveryKey)
      setCopied('yes')
    } catch {
      setCopied('failed')
    }
  }

  return (
    <VaultScreen title="Your recovery key">
      <p className="vault-warning" role="note">
        <strong>{RECOVERY_WARNING}</strong>
      </p>
      <p className="muted">
        It is shown only now. If you forget your password, this key opens the vault and lets you set a new one.
      </p>
      <ol className="recovery-key" aria-label="Recovery key">
        {groups.map((group, i) => (
          <li key={i}>
            <code>{group}</code>
          </li>
        ))}
      </ol>
      <div className="vault-actions">
        <button type="button" className="button-secondary" onClick={() => void copy()}>
          Copy
        </button>
        <span role="status" className="muted">
          {copied === 'yes' && 'Copied. Paste it somewhere safe, then clear the clipboard.'}
          {copied === 'failed' && 'Copying didn’t work here; write the key down.'}
        </span>
      </div>
      <form
        className="vault-form"
        onSubmit={(e) => {
          e.preventDefault()
          if (matches) {
            onContinue()
          }
        }}
      >
        <label>
          Type the key’s last group to continue
          <input
            value={typed}
            onChange={(e) => setTyped(e.target.value)}
            maxLength={8}
            autoComplete="off"
            spellCheck={false}
            autoCapitalize="characters"
          />
        </label>
        <button type="submit" className="button-primary" disabled={!matches}>
          I’ve saved it. Continue
        </button>
      </form>
    </VaultScreen>
  )
}
