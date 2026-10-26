import { useState, type FormEvent } from 'react'
import { ApiProblem, imports, type Account, type AccountType } from '../../api'
import { ACCOUNT_TYPES, FIELD_LABELS, accountLabel } from './importText'

interface FieldError {
  field: string
  message: string
}

/** The API's field errors (a 400's `errors`), each named as the form labels it. */
function fieldErrors(problem: ApiProblem): string[] {
  const errors = Array.isArray(problem.body.errors) ? (problem.body.errors as FieldError[]) : []
  return errors.map((e) => `${FIELD_LABELS[e.field] ?? e.field}: ${e.message}`)
}

/**
 * Adds an account with the fields `POST /api/accounts` takes (name, institution, type, last four); `closedOn` is
 * never set here. The API validates; its refusal is shown as returned.
 */
function AddAccountForm({ onAdded, onCancel }: { onAdded: (account: Account) => void; onCancel: () => void }) {
  const [name, setName] = useState('')
  const [institution, setInstitution] = useState('')
  const [type, setType] = useState<AccountType>('CHECKING')
  const [lastFour, setLastFour] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<{ message: string; fields: string[] } | null>(null)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setSaving(true)
    setError(null)
    try {
      onAdded(await imports.createAccount({ name, institution, type, lastFour }))
    } catch (e) {
      const problem = e instanceof ApiProblem ? e : null
      setError({ message: (e as Error).message, fields: problem ? fieldErrors(problem) : [] })
      setSaving(false)
    }
  }

  return (
    <form className="card import-form" aria-labelledby="add-account-title" onSubmit={(e) => void submit(e)}>
      <h3 id="add-account-title">Add an account</h3>
      <label>
        Name
        <input value={name} onChange={(e) => setName(e.target.value)} autoComplete="off" />
      </label>
      <label>
        Institution
        <input value={institution} onChange={(e) => setInstitution(e.target.value)} autoComplete="off" />
      </label>
      <label>
        Type
        <select value={type} onChange={(e) => setType(e.target.value as AccountType)}>
          {ACCOUNT_TYPES.map((t) => (
            <option key={t.value} value={t.value}>
              {t.label}
            </option>
          ))}
        </select>
      </label>
      <label>
        Last four digits
        <input
          value={lastFour}
          onChange={(e) => setLastFour(e.target.value)}
          inputMode="numeric"
          maxLength={4}
          autoComplete="off"
          aria-describedby="last-four-help"
        />
      </label>
      <p id="last-four-help" className="muted">
        An OFX file’s account number must end in these digits. They stay on this computer.
      </p>
      {error && (
        <div role="alert" className="error">
          <p>{error.message}</p>
          {error.fields.length > 0 && (
            <ul>
              {error.fields.map((f) => (
                <li key={f}>{f}</li>
              ))}
            </ul>
          )}
        </div>
      )}
      <div className="import-actions">
        <button type="submit" className="button-primary" disabled={saving}>
          {saving ? 'Adding…' : 'Add account'}
        </button>
        <button type="button" className="button-secondary" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

/** The account a file is imported into: every account from `GET /api/accounts`, each with its last four. */
export default function AccountPicker({
  accounts,
  accountId,
  onChange,
  onAdded,
}: {
  accounts: Account[]
  accountId: number | null
  onChange: (id: number | null) => void
  onAdded: (account: Account) => void
}) {
  const [adding, setAdding] = useState(false)
  return (
    <div className="import-account">
      <label>
        Account
        <select
          value={accountId === null ? '' : String(accountId)}
          onChange={(e) => onChange(e.target.value === '' ? null : accounts.find((a) => String(a.id) === e.target.value)?.id ?? null)}
        >
          <option value="">Choose an account</option>
          {accounts.map((a) => (
            <option key={a.id} value={a.id}>
              {accountLabel(a)}
              {a.closedOn ? ' (closed)' : ''}
            </option>
          ))}
        </select>
      </label>
      {!adding && (
        <button type="button" className="button-secondary" onClick={() => setAdding(true)}>
          Add an account
        </button>
      )}
      {adding && (
        <AddAccountForm
          onAdded={(account) => {
            setAdding(false)
            onAdded(account)
          }}
          onCancel={() => setAdding(false)}
        />
      )}
    </div>
  )
}
