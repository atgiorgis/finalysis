import { useId, useState, type DragEvent } from 'react'
import { formatBytes } from '../../format'
import { ACCEPTED, ACCEPTED_TEXT, PRIVACY_LINE } from './importText'

/**
 * One statement file, dropped or chosen, with its name and size shown before the import. Several files at once are
 * refused here (one import per file); the format is judged by the API, so a file it can't read gets its own answer.
 */
export default function FileDrop({ file, onFile }: { file: File | null; onFile: (file: File | null) => void }) {
  const inputId = useId()
  const [over, setOver] = useState(false)
  const [tooMany, setTooMany] = useState(false)

  function take(files: FileList | null) {
    if (!files || files.length === 0) {
      return
    }
    if (files.length > 1) {
      setTooMany(true)
      onFile(null)
      return
    }
    setTooMany(false)
    onFile(files[0])
  }

  function drop(event: DragEvent) {
    event.preventDefault()
    setOver(false)
    take(event.dataTransfer.files)
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
        <p className="muted">{ACCEPTED_TEXT}</p>
      </div>
      <p className="import-privacy" data-testid="privacy-line">
        {PRIVACY_LINE}
      </p>
      {tooMany && (
        <p role="alert" className="error">
          One file at a time: drop or choose a single statement.
        </p>
      )}
      {file && (
        <p className="import-chosen" aria-live="polite">
          <span className="import-file-name">{file.name}</span>
          <span className="muted"> · {formatBytes(file.size)}</span>
        </p>
      )}
    </div>
  )
}
