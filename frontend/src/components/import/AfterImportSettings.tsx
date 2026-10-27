import { useEffect, useState, type FormEvent } from 'react'
import { imports, type AfterImport } from '../../api'
import { hasVault, useVault } from '../../vault/VaultContext'

const CHOICES: { value: AfterImport; label: string; needsVault?: boolean }[] = [
  { value: 'ASK', label: 'Ask each time' },
  { value: 'KEEP', label: 'Keep a copy in the vault', needsVault: true },
  { value: 'DELETE', label: 'Delete the original (asks first)' },
  { value: 'BOTH', label: 'Both (deleting asks first)', needsVault: true },
  { value: 'LEAVE', label: 'Leave it' },
]

/** Settings → Statement files (docs/plans/phase-8.md §6, §7): what the Import tab does after a successful import. */
export default function AfterImportSettings() {
  const { status } = useVault()
  const vault = hasVault(status)
  const [value, setValue] = useState<AfterImport | null>(null)
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    imports
      .settings()
      .then((read) => setValue(read.afterImport))
      .catch((e: Error) => setMessage({ ok: false, text: e.message }))
  }, [])

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!value) {
      return
    }
    setBusy(true)
    setMessage(null)
    try {
      await imports.saveSettings(value)
      setMessage({ ok: true, text: 'Saved.' })
    } catch (err) {
      setMessage({ ok: false, text: (err as Error).message })
    }
    setBusy(false)
  }

  return (
    <section className="card" aria-labelledby="settings-statement-files">
      <h2 id="settings-statement-files">Statement files</h2>
      <form className="settings-form" onSubmit={(e) => void submit(e)}>
        <label>
          After a successful import
          <select
            value={value ?? 'ASK'}
            disabled={value === null}
            onChange={(e) => setValue(e.target.value as AfterImport)}
          >
            {CHOICES.filter((c) => vault || !c.needsVault || c.value === value).map((c) => (
              <option key={c.value} value={c.value}>
                {c.label}
              </option>
            ))}
          </select>
        </label>
        <p className="muted">
          A kept copy is encrypted in the vault and goes when its statement is deleted. Deleting the original works
          in Chrome and Edge, for a file chosen or dropped in Finalysis, and is permanent; other browsers say which
          file to delete yourself.
        </p>
        {message && (
          <p role={message.ok ? 'status' : 'alert'} className={message.ok ? undefined : 'error'}>
            {message.text}
          </p>
        )}
        <button type="submit" className="button-secondary" disabled={busy || value === null}>
          Save
        </button>
      </form>
    </section>
  )
}
