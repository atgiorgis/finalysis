import { Link } from 'react-router-dom'
import type {
  ApiProblem,
  CsvMapping,
  Direction,
  SignExample,
  ImportPreview,
  ImportSummary,
  MappingConfidence,
  UnparsedUpload,
} from '../../api'
import { formatMoney, formatPeriod } from '../../format'
import CategorizeNow from '../categorize/CategorizeNow'
import { BALANCE_TEXT, REASON_TEXT, STATUS_TEXT } from './importText'

/** What an import came back with, as the page shows it. */
export type ImportResultState =
  | { kind: 'imported'; fileName: string; summary: ImportSummary }
  | { kind: 'unparsed'; fileName: string; upload: UnparsedUpload }
  | { kind: 'duplicate'; fileName: string; problem: ApiProblem }
  | {
      kind: 'mapping'
      fileName: string
      problem: ApiProblem
      /** True for `mapping-required` (the account has none), false for `mapping-mismatch`. */
      required: boolean
      preview: ImportPreview | null
      previewError: string | null
    }
  | { kind: 'refused'; fileName: string; problem: ApiProblem }
  | { kind: 'failed'; fileName: string; message: string }

const CONFIDENCE_TEXT: Record<MappingConfidence, string> = {
  MATCHED: 'found',
  GUESSED: 'guessed, check it',
  MISSING: 'not found',
}

/** The mapping's fields as rows: label, value as the API suggested it, and how sure the suggestion is. */
function mappingRows(
  mapping: CsvMapping,
  signGuessed: boolean,
): { field: keyof CsvMapping; label: string; value: string }[] {
  const rows: { field: keyof CsvMapping; label: string; value: string | null }[] = [
    { field: 'dateColumn', label: 'Date', value: mapping.dateColumn },
    { field: 'dateFormat', label: 'Date format', value: mapping.dateFormat },
    { field: 'postDateColumn', label: 'Post date', value: mapping.postDateColumn },
    { field: 'descriptionColumns', label: 'Description', value: mapping.descriptionColumns.join(' + ') || null },
  ]
  if (mapping.amountMode === 'DEBIT_CREDIT') {
    rows.push(
      { field: 'debitColumn', label: 'Money out', value: mapping.debitColumn },
      { field: 'creditColumn', label: 'Money in', value: mapping.creditColumn },
    )
  } else {
    rows.push({ field: 'amountColumn', label: 'Amount', value: mapping.amountColumn })
  }
  rows.push(
    {
      field: 'sign',
      label: 'Amounts',
      value: signGuessed
        ? 'You choose below which way purchases are printed'
        : mapping.sign === 'INVERTED'
          ? 'Money out is printed positive: every amount is flipped'
          : mapping.sign === 'AS_IS'
            ? 'As printed: money out is negative'
            : null,
    },
    { field: 'balanceColumn', label: 'Balance', value: mapping.balanceColumn },
  )
  return rows
    .filter((row) => row.value !== null || row.field !== 'postDateColumn')
    .map((row) => ({ ...row, value: row.value ?? 'not set' }))
}

export type SignChoice = 'AS_IS' | 'INVERTED'

/** The suggester guessed the sign: the user says which way the file prints purchases (step 7e). */
export function signGuessed(preview: ImportPreview | null): boolean {
  return preview?.suggestedMapping?.confidence.sign === 'GUESSED'
}

const DIRECTION_TEXT: Record<Direction, string> = { MONEY_OUT: 'money out', MONEY_IN: 'money in' }

/** "$52.48 at SQ *CORNER BEAN CAFE → money out": the backend's example row read one way. */
export function signExampleText(example: SignExample, choice: SignChoice): string {
  const where = example.description ? ` at ${example.description}` : ` (row ${example.row})`
  return `${formatMoney(example.amount)}${where} → ${DIRECTION_TEXT[choice === 'AS_IS' ? example.asIs : example.inverted]}`
}

const SIGN_BUTTONS: { choice: SignChoice; label: string }[] = [
  { choice: 'AS_IS', label: 'Purchases are negative in this file' },
  { choice: 'INVERTED', label: 'Purchases are positive in this file' },
]

/** Two buttons in place of "Use this mapping" when the sign is a guess; each shows the example read its way. */
function SignButtons({
  example,
  saving,
  onChoose,
}: {
  example: SignExample | undefined
  saving: boolean
  onChoose: (sign: SignChoice) => void
}) {
  return (
    <div className="sign-choice" role="group" aria-labelledby="sign-choice-title">
      <p id="sign-choice-title">
        Which way does this file print purchases? Nothing is assumed: your answer is saved with the mapping.
      </p>
      <div className="sign-choice-buttons">
        {SIGN_BUTTONS.map(({ choice, label }) => (
          <button
            key={choice}
            type="button"
            className="button-secondary sign-choice-button"
            disabled={saving}
            onClick={() => onChoose(choice)}
          >
            <span className="sign-choice-label">{label}</span>
            {example && <span className="sign-choice-example muted">{signExampleText(example, choice)}</span>}
          </button>
        ))}
      </div>
      {saving && <p className="muted">Saving and importing…</p>}
    </div>
  )
}

/** The file's first rows and the suggested mapping, as the preview returns them. */
function MappingPreview({ preview }: { preview: ImportPreview }) {
  const suggestion = preview.suggestedMapping
  return (
    <>
      {preview.headers && (
        <div className="table-scroll" role="region" aria-label="First rows of the file" tabIndex={0}>
          <table className="preview-table">
            <thead>
              <tr>
                {preview.headers.map((h, i) => (
                  <th key={i} scope="col">
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            {preview.rows && (
              <tbody>
                {preview.rows.map((row, r) => (
                  <tr key={r}>
                    {row.map((cell, c) => (
                      <td key={c}>{cell}</td>
                    ))}
                  </tr>
                ))}
              </tbody>
            )}
          </table>
        </div>
      )}
      {suggestion && (
        <section aria-labelledby="suggested-mapping">
          <h4 id="suggested-mapping">Suggested mapping</h4>
          <dl className="facts">
            {mappingRows(suggestion.mapping, signGuessed(preview)).map((row) => (
              <div className="facts-row" key={row.field}>
                <dt>{row.label}</dt>
                <dd>
                  {row.value}
                  {suggestion.confidence[row.field] && !(row.field === 'sign' && signGuessed(preview)) && (
                    <span className="muted"> · {CONFIDENCE_TEXT[suggestion.confidence[row.field]!]}</span>
                  )}
                </dd>
              </div>
            ))}
          </dl>
          {suggestion.notes.length > 0 && (
            <ul className="import-notes">
              {suggestion.notes.map((note, i) => (
                <li key={i}>{note}</li>
              ))}
            </ul>
          )}
        </section>
      )}
    </>
  )
}

/** A problem as the API returned it: its title, detail and, for a parse error, the line. */
function ProblemText({ problem }: { problem: ApiProblem }) {
  const line = typeof problem.body.line === 'number' ? problem.body.line : null
  return (
    <>
      {problem.title && <p className="import-result-title">{problem.title}</p>}
      <p>{problem.message}</p>
      {line !== null && <p>Line {line} of the file.</p>}
    </>
  )
}

/**
 * The import's result (UI-02): the statement period, rows parsed, new rows and duplicates skipped, status and every
 * warning, each as the API returned it; nothing is added up here. An unparsed upload, an already-imported file and a
 * missing CSV mapping each get their own plain answer.
 */
export default function ImportResult({
  result,
  saving,
  saveError,
  onUseMapping,
}: {
  result: ImportResultState
  saving: boolean
  saveError: string | null
  /** Saves the suggested mapping, with the sign the user chose when the suggestion only guessed it. */
  onUseMapping: (sign?: SignChoice) => void
}) {
  switch (result.kind) {
    case 'imported': {
      const s = result.summary
      return (
        <section className="card import-result" aria-labelledby="import-result-title" aria-live="polite">
          <h3 id="import-result-title">Imported {result.fileName}</h3>
          <dl className="facts">
            <div className="facts-row">
              <dt>Statement period</dt>
              <dd>{formatPeriod(s.periodStart, s.periodEnd)}</dd>
            </div>
            <div className="facts-row">
              <dt>Rows parsed</dt>
              <dd>{s.parsed}</dd>
            </div>
            <div className="facts-row">
              <dt>New transactions</dt>
              <dd>{s.inserted}</dd>
            </div>
            <div className="facts-row">
              <dt>Duplicates skipped</dt>
              <dd>{s.duplicates}</dd>
            </div>
            <div className="facts-row">
              <dt>Status</dt>
              <dd>
                {STATUS_TEXT[s.status]} · {BALANCE_TEXT[s.balanceCheck]}
              </dd>
            </div>
          </dl>
          {s.warnings.length > 0 && (
            <section aria-labelledby="import-warnings">
              <h4 id="import-warnings">Warnings</h4>
              <ul className="import-warnings">
                {s.warnings.map((w, i) => (
                  <li key={i}>{w}</li>
                ))}
              </ul>
            </section>
          )}
          <p className="import-next">
            <Link to={`/transactions?statement=${s.statementId}`}>Open its transactions</Link>
            {' · '}
            <a href="#review">Review queue</a>
            {' · '}
            <Link to="/transactions?view=review">Transactions that need a category</Link>
          </p>
          <p className="muted">
            Categorization rules ran with the import. Anything they couldn’t place waits under “Needs a category”.
          </p>
          <CategorizeNow />
        </section>
      )
    }
    case 'unparsed':
      return (
        <section className="card import-result" aria-labelledby="import-result-title" aria-live="polite">
          <h3 id="import-result-title">{result.fileName} was not imported</h3>
          <p>{result.upload.detail}</p>
          <p>{REASON_TEXT[result.upload.reason]}</p>
          <p className="import-next">
            <a href="#review">It waits in the review queue under “Uploads not recognized”</a>
          </p>
        </section>
      )
    case 'duplicate': {
      const id = typeof result.problem.body.statementId === 'number' ? result.problem.body.statementId : null
      return (
        <section className="card import-result import-notice" aria-labelledby="import-result-title" aria-live="polite">
          <h3 id="import-result-title">{result.problem.title ?? 'Already imported'}</h3>
          <p>{result.problem.message}</p>
          <p className="muted">Nothing was imported again.</p>
          {id !== null && (
            <p className="import-next">
              <Link to={`/transactions?statement=${id}`}>Open statement {id}’s transactions</Link>
            </p>
          )}
        </section>
      )
    }
    case 'mapping':
      return (
        <section className="card import-result" aria-labelledby="import-result-title">
          <h3 id="import-result-title">{result.problem.title ?? 'CSV mapping'}</h3>
          {result.required ? (
            <p>
              This account has no CSV mapping yet, so the importer doesn’t know which column is which. Check how the
              suggested mapping reads {result.fileName}; using it saves it for this account and imports the file.
            </p>
          ) : (
            <>
              <p>{result.problem.message}</p>
              <p className="muted">
                The account’s saved mapping reads another layout. Check the account, or change its mapping through the
                API.
              </p>
            </>
          )}
          {result.previewError && <p className="error">Couldn’t read the preview: {result.previewError}</p>}
          {result.preview && <MappingPreview preview={result.preview} />}
          {result.required && result.preview?.suggestedMapping && (
            <>
              {saveError && (
                <p role="alert" className="error">
                  {saveError}
                </p>
              )}
              {signGuessed(result.preview) ? (
                <SignButtons example={result.preview.signExample} saving={saving} onChoose={onUseMapping} />
              ) : (
                <div className="import-actions">
                  <button type="button" className="button-primary" disabled={saving} onClick={() => onUseMapping()}>
                    {saving ? 'Saving and importing…' : 'Use this mapping'}
                  </button>
                </div>
              )}
            </>
          )}
        </section>
      )
    case 'refused':
      return (
        <section className="card import-result" aria-labelledby="import-result-title">
          <h3 id="import-result-title">{result.fileName} was not imported</h3>
          <div role="alert" className="error">
            <ProblemText problem={result.problem} />
          </div>
        </section>
      )
    case 'failed':
      return (
        <section className="card import-result" aria-labelledby="import-result-title">
          <h3 id="import-result-title">{result.fileName} was not imported</h3>
          <p role="alert" className="error">
            {result.message}
          </p>
        </section>
      )
  }
}
