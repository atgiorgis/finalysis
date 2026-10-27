import { useEffect, useRef, useState } from 'react'
import { imports, type AfterImport as AfterImportSetting } from '../../api'
import { hasVault, useVault } from '../../vault/VaultContext'
import { canDelete, deleteOriginal, type PickedFile } from './fileHandles'

type Choice = 'KEEP' | 'DELETE' | 'BOTH' | 'LEAVE'

interface Outcome {
  kept?: 'yes' | string
  deleted?: 'yes' | 'denied' | string
  left?: boolean
}

/**
 * After a successful import (docs/plans/phase-8.md §7): keep a copy in the vault, delete the original, both, or leave
 * it. The default from Settings is applied without asking, except that deleting the original always asks first: it is
 * permanent, not to the Trash. Where the browser can't delete files for the app (no file handle), the user is told to
 * delete it themselves. With the vault off there is nowhere to keep a copy.
 */
export default function AfterImport({ statementId, picked }: { statementId: number; picked: PickedFile }) {
  const { status } = useVault()
  const vault = hasVault(status)
  const deletable = canDelete(picked.handle)
  const name = picked.file.name
  const [setting, setSetting] = useState<AfterImportSetting | null>(null)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [busy, setBusy] = useState(false)
  const [outcome, setOutcome] = useState<Outcome>({})
  const applied = useRef(false)

  useEffect(() => {
    imports
      .settings()
      .then((read) => setSetting(read.afterImport))
      .catch(() => setSetting('ASK'))
  }, [])

  async function keep(): Promise<void> {
    try {
      await imports.keepFile(statementId, picked.file)
      setOutcome((o) => ({ ...o, kept: 'yes' }))
    } catch (e) {
      setOutcome((o) => ({ ...o, kept: (e as Error).message }))
    }
  }

  async function removeOriginal(): Promise<void> {
    if (!picked.handle) {
      return
    }
    try {
      const result = await deleteOriginal(picked.handle)
      setOutcome((o) => ({ ...o, deleted: result === 'deleted' ? 'yes' : 'denied' }))
    } catch (e) {
      setOutcome((o) => ({ ...o, deleted: (e as Error).message }))
    }
  }

  async function choose(choice: Choice) {
    setBusy(true)
    if (choice === 'KEEP' || choice === 'BOTH') {
      if (vault) {
        await keep()
      } else {
        setOutcome((o) => ({ ...o, kept: 'keeping a copy needs the encrypted vault, which is off here.' }))
      }
    }
    if (choice === 'DELETE' || choice === 'BOTH') {
      setConfirmDelete(true)
    }
    if (choice === 'LEAVE') {
      setOutcome({ left: true })
    }
    setBusy(false)
  }

  // A default other than ASK is applied once, as soon as it is known; deleting still waits for the confirmation.
  useEffect(() => {
    if (!setting || setting === 'ASK' || applied.current) {
      return
    }
    applied.current = true
    void choose(setting)
  }, [setting])

  const asked = setting === 'ASK' && !confirmDelete && Object.keys(outcome).length === 0
  return (
    <section className="card after-import" aria-labelledby="after-import-title">
      <h3 id="after-import-title">The file {name}</h3>
      {setting === null && <p className="muted">…</p>}
      {asked && (
        <>
          <p>What should happen to the statement file now that it is imported?</p>
          <div className="after-import-choices">
            {vault && (
              <button type="button" className="button-secondary" disabled={busy} onClick={() => void choose('KEEP')}>
                Keep a copy in the vault
              </button>
            )}
            <button type="button" className="button-secondary" disabled={busy} onClick={() => void choose('DELETE')}>
              Delete the original
            </button>
            {vault && (
              <button type="button" className="button-secondary" disabled={busy} onClick={() => void choose('BOTH')}>
                Both
              </button>
            )}
            <button type="button" className="button-secondary" disabled={busy} onClick={() => void choose('LEAVE')}>
              Leave it
            </button>
          </div>
          {!vault && <p className="muted">Keeping a copy needs the encrypted vault, which is off here.</p>}
        </>
      )}
      {outcome.kept === 'yes' && <p role="status">A copy is kept in the vault, encrypted with your data.</p>}
      {outcome.kept && outcome.kept !== 'yes' && (
        <p role="alert" className="error">
          The copy wasn’t kept: {outcome.kept}
        </p>
      )}
      {confirmDelete && !outcome.deleted && deletable && (
        <div className="after-import-confirm" role="group" aria-label="Delete the original">
          <p>
            This deletes <strong>{name}</strong> from your disk <strong>permanently</strong>: it doesn’t go to the Trash.
            {outcome.kept === 'yes' ? ' The copy in the vault stays.' : ''}
          </p>
          <div className="after-import-choices">
            <button
              type="button"
              className="button-danger"
              disabled={busy}
              onClick={() => {
                setBusy(true)
                void removeOriginal().finally(() => setBusy(false))
              }}
            >
              Delete it permanently
            </button>
            <button
              type="button"
              className="button-secondary"
              disabled={busy}
              onClick={() => {
                setConfirmDelete(false)
                setOutcome((o) => ({ ...o, left: true }))
              }}
            >
              Keep the original
            </button>
          </div>
        </div>
      )}
      {confirmDelete && !deletable && (
        <p role="status">
          This browser can’t delete files for Finalysis. Delete <strong>{name}</strong> yourself (and empty the Trash).
        </p>
      )}
      {outcome.deleted === 'yes' && <p role="status">Deleted {name} from your disk.</p>}
      {outcome.deleted === 'denied' && (
        <p role="alert" className="error">
          The browser didn’t allow it; {name} is still on your disk. Delete it yourself.
        </p>
      )}
      {outcome.deleted && outcome.deleted !== 'yes' && outcome.deleted !== 'denied' && (
        <p role="alert" className="error">
          {name} couldn’t be deleted: {outcome.deleted}
        </p>
      )}
      {outcome.left && !outcome.kept && !outcome.deleted && <p role="status">The file is left where it is.</p>}
    </section>
  )
}
