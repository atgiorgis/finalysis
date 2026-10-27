import { useEffect, useMemo, useState } from 'react'
import { useLocation, useSearchParams } from 'react-router-dom'
import {
  ApiProblem,
  imports,
  reads,
  type Account,
  type ImportPreview,
  type ReviewQueueResponse,
  type StatementListItem,
} from '../api'
import AccountPicker from '../components/import/AccountPicker'
import AfterImport from '../components/import/AfterImport'
import FileDrop from '../components/import/FileDrop'
import type { PickedFile } from '../components/import/fileHandles'
import ImportResult, { type ImportResultState, type SignChoice } from '../components/import/ImportResult'
import ReviewSections from '../components/import/ReviewSections'
import StatementsList, { type StatementsByAccount } from '../components/import/StatementsList'
import { useDataVersion, useShellData } from '../shell/ShellData'

type Load<T> = { state: 'loading' } | { state: 'error'; message: string } | { state: 'done'; value: T }

/** The problem's own preview when it carries one (a mismatch, without rows). */
function problemPreview(problem: ApiProblem): ImportPreview | null {
  const preview = problem.body.preview
  return preview && typeof preview === 'object' ? (preview as ImportPreview) : null
}

/** Every account's statements, one read per account, read again after every write. */
function useStatements(accounts: Account[], version: number): StatementsByAccount | null {
  const [statements, setStatements] = useState<StatementsByAccount | null>(null)
  const key = accounts.map((a) => a.id).join(',')
  useEffect(() => {
    let live = true
    Promise.all(
      accounts.map((a) =>
        reads.statements(a.id).then(
          (list): [number, StatementListItem[] | { error: string }] => [a.id, list],
          (e: Error): [number, StatementListItem[] | { error: string }] => [a.id, { error: e.message }],
        ),
      ),
    ).then((entries) => live && setStatements(new Map(entries)))
    return () => {
      live = false
    }
    // The accounts are compared by their ids, through the key.
  }, [key, version])
  return statements
}

/**
 * Import (UI-02, docs/plans/phase-7.md step 7d): pick or add an account, drop one statement file, import it and see
 * the result as the API returns it; every statement by account, with delete; and the review queue's statements,
 * uploads and transfer matches. Writes: an account, an import, a CSV mapping (the suggested one, when the API asks for
 * one), and the review and delete actions that already existed.
 */
export default function ImportPage() {
  const [params, setParams] = useSearchParams()
  const { accounts } = useShellData()
  const { version, dataChanged } = useDataVersion()
  const asked = params.get('account')
  const accountId = accounts.find((a) => String(a.id) === asked)?.id ?? null
  const [file, setFile] = useState<PickedFile | null>(null)
  /** The file just imported, for the choice of what happens to it (Phase 8 §7); dropped with the next import. */
  const [imported, setImported] = useState<{ statementId: number; picked: PickedFile } | null>(null)
  const [running, setRunning] = useState(false)
  const [result, setResult] = useState<ImportResultState | null>(null)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [deleted, setDeleted] = useState<string | null>(null)
  const [queue, setQueue] = useState<Load<ReviewQueueResponse>>({ state: 'loading' })
  const statements = useStatements(accounts, version)

  useEffect(() => {
    let live = true
    reads
      .reviewQueue()
      .then((value) => live && setQueue({ state: 'done', value }))
      .catch((e: Error) => live && setQueue({ state: 'error', message: e.message }))
    return () => {
      live = false
    }
  }, [version])

  // `/import#review` (the Transactions tab's link) lands on the review queue once it has loaded.
  const { hash } = useLocation()
  useEffect(() => {
    if (hash === '#review' && queue.state === 'done') {
      document.getElementById('review')?.scrollIntoView?.({ block: 'start' })
    }
  }, [hash, queue.state])

  const statementsById = useMemo(() => {
    const byId = new Map<number, StatementListItem>()
    statements?.forEach((list) => Array.isArray(list) && list.forEach((s) => byId.set(s.id, s)))
    return byId
  }, [statements])

  const selectAccount = (id: number | null) => {
    setParams((current) => {
      const updated = new URLSearchParams(current)
      if (id === null) {
        updated.delete('account')
      } else {
        updated.set('account', String(id))
      }
      return updated
    })
    setResult(null)
  }

  async function runImport(target: number, picked: PickedFile) {
    const chosen = picked.file
    setRunning(true)
    setResult(null)
    setImported(null)
    setSaveError(null)
    try {
      const outcome = await imports.importFile(target, chosen)
      setResult(
        outcome.kind === 'imported'
          ? { kind: 'imported', fileName: chosen.name, summary: outcome.summary }
          : { kind: 'unparsed', fileName: chosen.name, upload: outcome.upload },
      )
      if (outcome.kind === 'imported') {
        setImported({ statementId: outcome.summary.statementId, picked })
      }
      setFile(null)
      dataChanged({ shell: true })
    } catch (e) {
      if (!(e instanceof ApiProblem)) {
        setResult({ kind: 'failed', fileName: chosen.name, message: (e as Error).message })
      } else if (e.slug === 'already-imported') {
        setResult({ kind: 'duplicate', fileName: chosen.name, problem: e })
      } else if (e.slug === 'mapping-required' || e.slug === 'mapping-mismatch') {
        // The preview (it writes nothing) shows the file's first rows next to the suggested mapping.
        let preview = problemPreview(e)
        let previewError: string | null = null
        try {
          preview = await imports.preview(target, chosen)
        } catch (p) {
          previewError = (p as Error).message
        }
        setResult({
          kind: 'mapping',
          fileName: chosen.name,
          problem: e,
          required: e.slug === 'mapping-required',
          preview,
          previewError,
        })
      } else {
        setResult({ kind: 'refused', fileName: chosen.name, problem: e })
      }
    } finally {
      setRunning(false)
    }
  }

  /**
   * "Use this mapping", or one of the two sign buttons: save the suggested mapping for the account, with the sign the
   * user chose when the suggestion only guessed it, then import the same file again.
   */
  async function applySuggestedMapping(sign?: SignChoice) {
    if (result?.kind !== 'mapping' || !result.preview?.suggestedMapping || accountId === null || !file) {
      return
    }
    setSaving(true)
    setSaveError(null)
    try {
      const suggested = result.preview.suggestedMapping.mapping
      await imports.saveMapping(accountId, sign ? { ...suggested, sign } : suggested)
    } catch (e) {
      const problem = e instanceof ApiProblem ? e : null
      const fields = Array.isArray(problem?.body.errors)
        ? (problem.body.errors as { field: string; message: string }[]).map((f) => `${f.field} ${f.message}`)
        : []
      setSaveError([(e as Error).message, ...fields].join('. '))
      setSaving(false)
      return
    }
    setSaving(false)
    await runImport(accountId, file)
  }

  const added = (account: Account) => {
    dataChanged({ shell: true })
    selectAccount(account.id)
  }

  return (
    <section className="page import-page">
      <h1>Import</h1>
      <section className="card" aria-labelledby="import-title">
        <h2 id="import-title">Import a statement</h2>
        <AccountPicker accounts={accounts} accountId={accountId} onChange={selectAccount} onAdded={added} />
        <FileDrop
          file={file}
          onFile={(f) => {
            setFile(f)
            setResult(null)
            setImported(null)
          }}
        />
        <div className="import-actions">
          <button
            type="button"
            className="button-primary"
            disabled={running || accountId === null || file === null}
            onClick={() => accountId !== null && file && void runImport(accountId, file)}
          >
            Import
          </button>
          {accountId === null && <span className="muted">Choose an account first.</span>}
        </div>
        {running && (
          <div className="import-progress" role="status">
            <progress aria-label="Importing" />
            <span>Importing {file?.file.name}…</span>
          </div>
        )}
      </section>
      {result && <ImportResult result={result} saving={saving} saveError={saveError} onUseMapping={(sign) => void applySuggestedMapping(sign)} />}
      {imported && <AfterImport key={imported.statementId} statementId={imported.statementId} picked={imported.picked} />}
      <ReviewSections queue={queue} statements={statementsById} />
      <StatementsList accounts={accounts} statements={statements} deleted={deleted} onDeleted={setDeleted} />
    </section>
  )
}
