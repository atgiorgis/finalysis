import type { Account, AccountType, BalanceCheck, StatementStatus, UnparsedReason } from '../../api'

/**
 * Under the drop zone (SEC-01, UI-02): what happens to the file, in the banner's wording family. The file never leaves
 * the machine; with cloud AI on, categorization sends only merchant names and amounts.
 */
export const PRIVACY_LINE =
  'The file is read on this computer. With cloud AI on, only merchant names and amounts leave it, for categorization.'

/** The formats the import accepts (the backend's `UploadCheck.EXTENSIONS`). Image scans wait for OCR (Phase 8). */
export const ACCEPTED = '.ofx,.qfx,.csv,.txt,.pdf'
export const ACCEPTED_TEXT = 'OFX, QFX, CSV, TXT or PDF, up to 10 MB. Scanned images can’t be read yet.'

export const ACCOUNT_TYPES: { value: AccountType; label: string }[] = [
  { value: 'CHECKING', label: 'Checking' },
  { value: 'SAVINGS', label: 'Savings' },
  { value: 'CREDIT_CARD', label: 'Credit card' },
]

/** `Northfield Bank Checking ···1001`; the last four only as the API returned it. */
export function accountLabel(account: Pick<Account, 'name' | 'lastFour'>): string {
  return account.lastFour ? `${account.name} ···${account.lastFour}` : account.name
}

export const STATUS_TEXT: Record<StatementStatus, string> = {
  RECONCILED: 'Reconciled',
  NEEDS_REVIEW: 'Needs review',
  IMPORTED: 'Imported',
}

export const BALANCE_TEXT: Record<BalanceCheck, string> = {
  PASSED: 'Balances check out',
  FAILED: 'Balances don’t add up',
  UNVERIFIED: 'Balances not checked',
}

export const REASON_TEXT: Record<UnparsedReason, string> = {
  UNRECOGNIZED_LAYOUT: 'No supported statement layout was recognized.',
  NO_TEXT_LAYER: 'The PDF has no text (most likely a scan); scans can’t be read yet.',
}

/** The API's field names in the add-account and mapping errors, as the form labels them. */
export const FIELD_LABELS: Record<string, string> = {
  name: 'Name',
  institution: 'Institution',
  type: 'Type',
  lastFour: 'Last four',
}

/** `1 transaction`, `8 transactions`: the count as returned, the noun by whether it is one. */
export function plural(count: number, noun: string, nouns = `${noun}s`): string {
  return count === 1 ? `1 ${noun}` : `${count} ${nouns}`
}

/** `same day`, `1 day apart`, `3 days apart`, from the API's `daysApart`. */
export function daysApartText(days: number): string {
  return days === 0 ? 'same day' : days === 1 ? '1 day apart' : `${days} days apart`
}
