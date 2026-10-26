import { useEffect, useId, useState } from 'react'
import { categorization, reads, type CategorizationRun } from '../../api'
import { AI_NAME } from '../../corgi'
import { useDataVersion } from '../../shell/ShellData'
import { HELD_BACK_TEXT, indexedText, runParts, runSummaryText, stoppedText } from './runText'

/**
 * "Categorize now" (step 7e): runs `POST /api/categorization/run` over every uncategorized transaction, shows it
 * working, then the backend's summary in words, and reloads every page. Offered only while something is
 * uncategorized: `uncategorized` when the page already has the count (null while it reloads: the last one is kept),
 * otherwise read here.
 */
export default function CategorizeNow({ uncategorized }: { uncategorized?: number | null }) {
  const { version, dataChanged } = useDataVersion()
  const [count, setCount] = useState<number | null>(uncategorized ?? null)
  const [running, setRunning] = useState(false)
  const [run, setRun] = useState<CategorizationRun | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [explain, setExplain] = useState(false)
  const tipId = useId()

  useEffect(() => {
    if (uncategorized !== undefined) {
      if (uncategorized !== null) {
        setCount(uncategorized)
      }
      return
    }
    let live = true
    reads
      .transactions({ uncategorized: true, size: 1 })
      .then((page) => live && setCount(page.total))
      .catch(() => live && setCount(null))
    return () => {
      live = false
    }
  }, [uncategorized, version])

  async function start() {
    setRunning(true)
    setError(null)
    setRun(null)
    try {
      setRun(await categorization.run())
      dataChanged({ shell: true })
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setRunning(false)
    }
  }

  const offered = count !== null && count > 0
  if (!offered && !run && !running && !error) {
    return null
  }
  const parts = run ? runParts(run) : null
  const indexed = run ? indexedText(run) : null
  const stopped = run ? stoppedText(run) : null

  return (
    <div className="categorize-now">
      {offered && (
        <div className="categorize-now-row">
          <button type="button" className="button-primary" disabled={running} onClick={() => void start()}>
            Categorize now
          </button>
          <span className="muted">Rules, then {AI_NAME}, over every uncategorized transaction, not only this list.</span>
        </div>
      )}
      {running && (
        <div className="import-progress" role="status">
          <progress aria-label="Categorizing" />
          <span>Categorizing… this can take a minute.</span>
        </div>
      )}
      {error && (
        <p role="alert" className="error">
          Categorizing failed: {error}
        </p>
      )}
      {run && parts && (
        <div className="categorize-summary" role="status" aria-label={runSummaryText(run)}>
          <p>
            {parts.before}
            {parts.heldBack && (
              <>
                {', '}
                <button
                  type="button"
                  className="link-button held-back"
                  aria-expanded={explain}
                  aria-describedby={tipId}
                  title={HELD_BACK_TEXT}
                  onClick={() => setExplain((v) => !v)}
                >
                  {parts.heldBack}
                </button>
              </>
            )}
            {', '}
            {parts.after}
          </p>
          {parts.heldBack && (
            <p id={tipId} className={explain ? 'muted held-back-text' : 'visually-hidden'}>
              {HELD_BACK_TEXT}
            </p>
          )}
          {indexed && <p className="muted">{indexed}</p>}
          {stopped && <p className="chat-note">{stopped}</p>}
        </div>
      )}
    </div>
  )
}
