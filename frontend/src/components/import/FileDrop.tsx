import { useId, useRef, useState, type DragEvent } from 'react'
import { formatBytes } from '../../format'
import { canPickWithHandle, handleFromDrop, pickWithHandle, type PickedFile } from './fileHandles'
import { ACCEPTED, ACCEPTED_TEXT, PRIVACY_LINE } from './importText'

/**
 * One statement file, dropped or chosen, with its name and size shown before the import. Several files at once are
 * refused here (one import per file); the format is judged by the API, so a file it can't read gets its own answer.
 * Where the browser offers it (Chromium), the file comes with a handle to the original, so the app can delete it
 * after the import if the user asks (docs/plans/phase-8.md §7); elsewhere the plain file input is used.
 */
export default function FileDrop({
  file,
  onFile,
}: {
  file: PickedFile | null
  onFile: (picked: PickedFile | null) => void
}) {
  const inputId = useId()
  const [over, setOver] = useState(false)
  const [tooMany, setTooMany] = useState(false)
  const [pickError, setPickError] = useState<string | null>(null)
  /** Counts the choices, so a handle that arrives late attaches only to the file it belongs to. */
  const choice = useRef(0)

  /** One file, shown at once, or a refusal; a dropped file's handle, when there is one, joins it a moment later. */
  function take(files: FileList | null, handle?: Promise<PickedFile['handle']>) {
    if (!files || files.length === 0) {
      return
    }
    const mine = ++choice.current
    if (files.length > 1) {
      setTooMany(true)
      onFile(null)
      return
    }
    setTooMany(false)
    const chosen = files[0]
    onFile({ file: chosen, handle: null })
    void handle?.then((found) => {
      if (found && choice.current === mine) {
        onFile({ file: chosen, handle: found })
      }
    })
  }

  function drop(event: DragEvent) {
    event.preventDefault()
    setOver(false)
    // Asked for during the drop event itself, as the API requires.
    take(event.dataTransfer.files, handleFromDrop(event.dataTransfer.items))
  }

  async function choose() {
    setPickError(null)
    try {
      const picked = await pickWithHandle(ACCEPTED.split(','))
      if (picked) {
        choice.current++
        setTooMany(false)
        onFile(picked)
      }
    } catch (e) {
      setPickError((e as Error).message)
    }
  }

  return (
    <div className="import-file">
      <div
        className={over ? 'drop-zone drop-zone-over' : 'drop-zone'}
        data-testid="drop-zone"
        onDragOver={(e) => {
          e.preventDefault()
          setOver(true)
        }}
        onDragLeave={() => setOver(false)}
        onDrop={drop}
      >
        <p>Drop a statement file here, or</p>
        {canPickWithHandle() ? (
          <button type="button" className="button-secondary file-button" onClick={() => void choose()}>
            Choose a file
          </button>
        ) : (
          <>
            <label htmlFor={inputId} className="button-secondary file-button">
              Choose a file
            </label>
            <input
              id={inputId}
              type="file"
              accept={ACCEPTED}
              className="visually-hidden"
              onChange={(e) => {
                take(e.target.files)
                e.target.value = ''
              }}
            />
          </>
        )}
        <p className="muted">{ACCEPTED_TEXT}</p>
      </div>
      <p className="import-privacy" data-testid="privacy-line">
        {PRIVACY_LINE}
      </p>
      {pickError && (
        <p role="alert" className="error">
          {pickError}
        </p>
      )}
      {tooMany && (
        <p role="alert" className="error">
          One file at a time: drop or choose a single statement.
        </p>
      )}
      {file && (
        <p className="import-chosen" aria-live="polite">
          <span className="import-file-name">{file.file.name}</span>
          <span className="muted"> · {formatBytes(file.file.size)}</span>
        </p>
      )}
    </div>
  )
}
