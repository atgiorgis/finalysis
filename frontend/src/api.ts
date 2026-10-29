/** Where Corgi runs (docs/plans/phase-9.md §3.1): the user's choice, switched in Settings without a restart. */
export type AiPath = 'OFF' | 'CLOUD' | 'SELF_HOSTED' | 'LOCAL'

/**
 * The `ai` block of `GET /api/status` (docs/plans/phase-9.md §3.3). `path` is absent while the vault is locked;
 * `reachable` says whether Corgi can run now and `reason` why not; `server` is Self-hosted's Tailscale name only.
 * `provider` and `models` are present when the path's providers are built.
 */
export interface AiStatus {
  path?: AiPath
  available?: AiPath[]
  reachable?: boolean
  reason?: string
  provider?: string
  server?: string
  models?: Record<string, string>
}

export type VaultState =
  | 'NOT_SET_UP'
  | 'NOT_READY'
  | 'LOCKED'
  | 'UNLOCKING'
  | 'UNLOCKED'
  | 'LOCKING'
  | 'LOCK_FAILED'
  | 'BROKEN'

/** The vault on `GET /api/status` (docs/plans/phase-8.md §5.2); never a path. `PLAIN` is the dev database, vault off. */
export interface VaultStatus {
  state: VaultState
  storage: 'VAULT' | 'PLAIN'
  /** A vault exists: offer unlock, not setup (matters when NOT_READY). */
  setUp: boolean
  reason: string | null
  message: string | null
  idleLockMinutes: number
  lanAccess: boolean
  lastLockForced: boolean
  collation: { recorded: string; actual: string; action: string } | null
}

export interface StatusResponse {
  app: string
  status: string
  timestamp: string
  ai: AiStatus
  vault: VaultStatus
}

// ---------------------------------------------------------------------------- the vault's 423 (Phase 8 §6)

type LockListener = () => void
const lockListeners = new Set<LockListener>()
const loginListeners = new Set<LockListener>()

/** Called whenever any request answers 423 (the vault is locked); returns the unsubscribe. */
export function onVaultLocked(listener: LockListener): () => void {
  lockListeners.add(listener)
  return () => {
    lockListeners.delete(listener)
  }
}

/** Called whenever a request on the phone answers 401 `login-required` (no session, or it ended). */
export function onLoginRequired(listener: LockListener): () => void {
  loginListeners.add(listener)
  return () => {
    loginListeners.delete(listener)
  }
}

/**
 * Every request of the app goes through here, so a 423 anywhere switches the app to the unlock screen, and a 401
 * `login-required` (the phone, Phase 8 §9.3) to the login screen.
 */
async function apiFetch(input: string, init?: RequestInit): Promise<Response> {
  const res = await fetch(input, init)
  if (res.status === 423) {
    lockListeners.forEach((listener) => listener())
  } else if (res.status === 401) {
    const type = await res
      .clone()
      .json()
      .then((body: { type?: string }) => body.type)
      .catch(() => undefined)
    if (type === 'urn:finalysis:problem:login-required') {
      loginListeners.forEach((listener) => listener())
    }
  }
  return res
}

// ---------------------------------------------------------------------------- shared shapes
//
// Money, percents and ratios are strings exactly as the API returns them (`"-1850.00"`, `"93.8"`): the UI formats
// them for display and never does arithmetic on them (docs/plans/phase-7.md §4). Months are `yyyy-MM`, dates
// `yyyy-MM-dd`.

export type Money = string
export type Percent = string
export type YearMonth = string
export type IsoDate = string

export type Coverage = 'COMPLETE' | 'PARTIAL' | 'NONE'
export type CategoryKind = 'EXPENSE' | 'INCOME' | 'TRANSFER'
export type TxnBucket = 'LINKED_TRANSFER' | 'UNLINKED_TRANSFER' | 'REFUND' | 'EXPENSE' | 'INCOME' | 'UNCATEGORIZED'
export type MonthFlagType = 'RECONCILIATION_FAILED' | 'BALANCE_UNVERIFIED' | 'PARTIAL_COVERAGE'
export type AccountType = 'CHECKING' | 'SAVINGS' | 'CREDIT_CARD'
export type CategorySource = 'RULE' | 'AI' | 'USER'

export interface MonthRange {
  from: YearMonth
  to: YearMonth
}

/** A sum of txns: money out (≤ 0), money in (≥ 0), their net, and how many txns. */
export interface MoneyFlow {
  moneyOut: Money
  moneyIn: Money
  net: Money
  count: number
}

export interface CategoryRef {
  id: number
  name: string
  kind: CategoryKind
}

export interface MonthFlag {
  type: MonthFlagType
  accountId: number
  statementId: number | null
  reviewedAt: string | null
}

// ---------------------------------------------------------------------------- analytics (ANA-01–ANA-08)

export interface DataQuality {
  countedTxns: number
  uncategorizedCount: number
  reviewPendingCount: number
  unlinkedTransferCount: number
  uncategorizedPercentOfCount: Percent | null
  reviewPendingPercentOfCount: Percent | null
  uncategorizedPercentOfSpending: Percent | null
  flags: MonthFlag[]
}

/** One month's totals, signed as stored: spending ≤ 0, income ≥ 0. */
export interface MonthTotals {
  month: YearMonth
  coverage: Coverage
  income: Money
  spending: Money
  net: Money
  savingsRatePercent: Percent | null
  refunds: MoneyFlow
  uncategorized: MoneyFlow
  unlinkedTransfers: MoneyFlow
  linkedTransfers: MoneyFlow
  netChange: Money
  txnCount: number
  dataQuality: DataQuality
}

export interface MonthlyTotalsResponse {
  range: MonthRange | null
  accountId: number | null
  latestMonth: YearMonth | null
  latestCompleteMonth: YearMonth | null
  months: MonthTotals[]
}

export interface CategoryTotalsResponse {
  range: MonthRange | null
  accountId: number | null
  categoryId: number | null
  uncategorized: boolean
  coverage: { month: YearMonth; coverage: Coverage }[]
  rows: {
    bucket: TxnBucket
    category: CategoryRef | null
    total: MoneyFlow
    months: { month: YearMonth; flow: MoneyFlow; reviewPending: number | null }[]
    spendingSharePercent: Percent | null
  }[]
  spending: Money | null
}

export interface DateSpan {
  start: IsoDate
  end: IsoDate
}

export interface CoverageResponse {
  lastCoveredDay: IsoDate | null
  latestMonth: YearMonth | null
  latestCompleteMonth: YearMonth | null
  accounts: {
    accountId: number
    name: string
    firstDay: IsoDate | null
    closedOn: IsoDate | null
    covered: DateSpan[]
    gaps: DateSpan[]
    flaggedStatements: {
      statementId: number
      periodStart: IsoDate
      periodEnd: IsoDate
      flag: MonthFlagType
      reviewedAt: string | null
    }[]
    truncated: boolean
  }[]
}

export interface MerchantTotalsResponse {
  range: MonthRange | null
  accountId: number | null
  categoryId: number | null
  limit: number
  items: {
    merchant: string
    spent: Money
    count: number
    firstDate: IsoDate
    lastDate: IsoDate
    category: CategoryRef | null
  }[]
  othersSpent: Money
  othersCount: number
  totalSpent: Money
  totalCount: number
}

export interface Average {
  amount: Money | null
  monthsUsed: number
  monthsWanted: number
}

/** A trend line for one month; amounts are positive magnitudes. */
export interface TrendRow {
  line: 'CATEGORY' | 'UNCATEGORIZED' | 'TOTAL_SPENDING' | 'TOTAL_INCOME'
  bucket: TxnBucket | null
  category: CategoryRef | null
  measure: 'SPENT' | 'RECEIVED'
  amount: Money
  previousMonth: Money | null
  changeAmount: Money | null
  changePercent: Percent | null
  trailing3: Average
  trailing6: Average
  sameMonthLastYear: Money | null
  monthCoverage: Coverage
}

export interface TrendsResponse {
  month: YearMonth | null
  accountId: number | null
  categoryId: number | null
  rows: TrendRow[]
}

/** The txns behind a total; `total` is over every matching txn, not only this page. */
export interface DrillDownResponse {
  range: MonthRange | null
  total: MoneyFlow
  page: number
  size: number
  totalPages: number
  items: {
    id: number
    accountId: number
    txnDate: IsoDate
    amount: Money
    merchant: string | null
    category: CategoryRef | null
    bucket: TxnBucket
  }[]
}

export interface RecurringCharge {
  accountId: number
  merchant: string
  category: CategoryRef | null
  cadence: 'MONTHLY'
  typicalAmount: Money
  lastAmount: Money
  fixedPrice: boolean
  occurrences: number
  firstDate: IsoDate
  lastDate: IsoDate
  nextExpectedDate: IsoDate
  status: 'ACTIVE' | 'ENDED'
}

export interface RecurringChargesResponse {
  accountId: number | null
  status: 'ACTIVE' | 'ENDED' | 'ALL'
  limit: number
  items: RecurringCharge[]
  truncated: boolean
}

// ---------------------------------------------------------------------------- alerts (ANA-04)

export type AlertType =
  | 'CATEGORY_SPIKE'
  | 'LARGE_TRANSACTION'
  | 'POSSIBLE_DUPLICATE'
  | 'RECURRING_PRICE_INCREASE'
  | 'NEW_RECURRING'

/** A value with its unit (`MONEY`, `PERCENT`, `RATIO`, `COUNT`, `DAYS`), as shown in the basis. */
export interface Quantity {
  unit: string
  value: string
}

export interface TxnRef {
  id: number
  accountId: number
  txnDate: IsoDate
  amount: Money
  merchant: string | null
}

export interface Alert {
  key: string
  type: AlertType
  month: YearMonth
  basis: {
    observed: Quantity
    baseline: Quantity | null
    threshold: Quantity
    periodStart: IsoDate
    periodEnd: IsoDate
    settings: { name: string; value: Quantity }[]
  }
  /** Type-specific facts, `type` naming which; the fields differ per type. */
  details: { type: AlertType } & Record<string, unknown>
  txns: TxnRef[]
  dataFlags: MonthFlag[]
  dismissed: boolean
  dismissedAt: string | null
  note: string | null
}

export interface AlertListResponse {
  range: MonthRange | null
  accountId: number | null
  type: AlertType | null
  includeDismissed: boolean
  limit: number
  items: Alert[]
  counts: Partial<Record<AlertType, number>>
  truncated: boolean
}

// ---------------------------------------------------------------------------- accounts, categories, transactions

export interface Account {
  id: number
  name: string
  institution: string | null
  type: AccountType
  lastFour: string | null
  createdAt: string
  closedOn: IsoDate | null
}

export interface Category {
  id: number
  name: string
  kind: CategoryKind
  parentId: number | null
}

export interface Transaction {
  id: number
  accountId: number
  accountName: string
  accountType: AccountType
  statementId: number
  txnDate: IsoDate
  postDate: IsoDate | null
  amount: Money
  sourceSection: string | null
  rawDescription: string
  merchant: string | null
  counterparty: string | null
  category: Category | null
  categorySource: CategorySource | null
  ruleId: number | null
  categorizedAt: string | null
  categoryModel: string | null
  categoryConfidence: number | null
  categoryPromptVersion: string | null
  aiReviewReason: string | null
  isTransfer: boolean
  transferExcluded: boolean
  /** The statement it was imported from (Phase 7 step 3b). */
  statement: { id: number; periodStart: IsoDate; periodEnd: IsoDate }
  /** The line it was imported from, as printed; null when the import kept none. */
  sourceLine: { number: number | null; text: string | null } | null
}

export interface TransactionPage {
  items: Transaction[]
  page: number
  size: number
  total: number
}

export type ReviewItemKind =
  | 'STATEMENT'
  | 'UNPARSED_UPLOAD'
  | 'TRANSFER_CANDIDATES'
  | 'COMPETING_CANDIDATE'
  | 'POSSIBLE_MATCH'
  | 'SUGGESTED_ONE_SIDED_TRANSFER'
  | 'AI_CATEGORY_REVIEW'
  | 'NOT_TRANSFER_UNCATEGORIZED'

export interface ReviewQueueResponse {
  counts: {
    statements: number
    unparsedUploads: number
    transferCandidates: number
    competingCandidates: number
    possibleMatches: number
    suggestedOneSidedTransfers: number
    aiCategoryReviews: number
    notTransferUncategorized: number
  }
  /** Each item's fields depend on its `kind`; the review screen (step 4) reads the ones it shows. */
  items: ({ kind: ReviewItemKind; since: string } & Record<string, unknown>)[]
}

// ---------------------------------------------------------------------------- corrections (CAT-04, CAT-08; Phase 4)
//
// With alert dismissal (step 5) and the Import tab (`imports`, step 7d), the writes in the UI (docs/plans/phase-7.md §2.4).

export type RuleAction = 'CREATED' | 'UPDATED' | 'UNCHANGED' | 'REMOVED' | 'NONE'

/** `PUT /api/transactions/{id}/category`: the txn, its merchant's correction rule, and the merchant's other txns. */
export interface CorrectionResult {
  txn: Transaction
  ruleId: number | null
  ruleAction: RuleAction
  otherTxnsUpdated: number
  otherTxnsKeptUser: number
  otherTxnsKeptOtherRule: number
}

/** `DELETE /api/transactions/{id}/category?removeRule=true`: the txn after the rules ran, the rule deleted. */
export interface UndoResult {
  txn: Transaction
  ruleId: number | null
  ruleAction: RuleAction
  otherTxnsUpdated: number
}

export interface NotTransferResult {
  txnId: number
  transferExcluded: boolean
  removedLinkId: number | null
}

async function send<T>(method: 'PUT' | 'POST' | 'DELETE', path: string, body?: unknown): Promise<T | null> {
  const res = await apiFetch(path, {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!res.ok) {
    throw await problemOf(res)
  }
  return res.status === 204 ? null : res.json()
}

export const corrections = {
  /** Sets your category; with `applyToMerchant` (the default) the merchant's other txns follow. */
  setCategory: (id: number, categoryId: number, applyToMerchant: boolean) =>
    send<CorrectionResult>('PUT', `/api/transactions/${id}/category`, { categoryId, applyToMerchant }) as Promise<
      CorrectionResult
    >,
  /** Clears your category and its merchant rule; the rules run again. */
  undoCategory: (id: number) =>
    send<UndoResult>('DELETE', `/api/transactions/${id}/category?removeRule=true`) as Promise<UndoResult>,
  notTransfer: (id: number) =>
    send<NotTransferResult>('POST', `/api/transactions/${id}/not-transfer`) as Promise<NotTransferResult>,
  undoNotTransfer: (id: number) => send<null>('DELETE', `/api/transactions/${id}/not-transfer`),
}

/** Alert dismissal (ANA-04, V20). */
export const dismissals = {
  /** Dismisses a current alert, with an optional note; answers the alert as it now is. */
  dismiss: (key: string, note: string | null) =>
    send<Alert>('PUT', `/api/alerts/${encodeURIComponent(key)}/dismissal`, note ? { note } : {}) as Promise<Alert>,
  /** Shows a dismissed alert again (204). */
  restore: (key: string) => send<null>('DELETE', `/api/alerts/${encodeURIComponent(key)}/dismissal`),
}

// ---------------------------------------------------------------------------- imports (UI-02, step 7d)
//
// The Import page's writes: add an account, import a file, save a CSV mapping (the suggested one, to retry an import
// the API refused for want of one), delete a statement, and the review queue's statement, upload and transfer actions.

export type StatementStatus = 'IMPORTED' | 'NEEDS_REVIEW' | 'RECONCILED'
export type BalanceCheck = 'PASSED' | 'FAILED' | 'UNVERIFIED'
export type UnparsedReason = 'UNRECOGNIZED_LAYOUT' | 'NO_TEXT_LAYER'

/** One statement in `GET /api/accounts/{id}/statements`; `transactionCount` is the rows it owns. */
export interface StatementListItem {
  id: number
  parser: string
  periodStart: IsoDate
  periodEnd: IsoDate
  openingBalance: Money | null
  closingBalance: Money | null
  status: StatementStatus
  balanceCheck: BalanceCheck
  /** Rows in the file; null for statements imported before reconciliation existed. */
  parsedCount: number | null
  transactionCount: number
  warningCount: number
  importedAt: string
  reviewedAt: string | null
}

/** `GET /api/statements/{id}`: the list item with its account and warnings. */
export interface StatementDetail extends Omit<StatementListItem, 'warningCount'> {
  accountId: number
  warnings: string[]
}

/** `201` from an import: what was read, saved and skipped, and the warnings as returned. */
export interface ImportSummary {
  statementId: number
  parser: string
  periodStart: IsoDate
  periodEnd: IsoDate
  openingBalance: Money | null
  closingBalance: Money | null
  parsed: number
  inserted: number
  duplicates: number
  status: StatementStatus
  balanceCheck: BalanceCheck
  warnings: string[]
}

/** `202` from an import: a PDF no parser recognized, held for review (ING-13). */
export interface UnparsedUpload {
  id: number
  accountId: number
  reason: UnparsedReason
  fileName: string
  createdAt: string
  state: 'OPEN' | 'RESOLVED' | 'DISMISSED'
  resolvedAt: string | null
  resolvedStatementId: number | null
  detail: string
}

export type ImportOutcome = { kind: 'imported'; summary: ImportSummary } | { kind: 'unparsed'; upload: UnparsedUpload }

/** A CSV mapping as `PUT /api/accounts/{id}/csv-mapping` takes it; the preview suggests a complete one. */
export interface CsvMapping {
  headers: string[] | null
  columnCount: number | null
  delimiter: string | null
  encoding: string | null
  headerRow: boolean | null
  skipRows: number | null
  dateColumn: string | null
  dateFormat: string | null
  postDateColumn: string | null
  descriptionColumns: string[]
  amountMode: 'SINGLE' | 'DEBIT_CREDIT' | null
  amountColumn: string | null
  debitColumn: string | null
  creditColumn: string | null
  sign: 'AS_IS' | 'INVERTED' | null
  balanceColumn: string | null
  checkNumberColumn: string | null
}

export type MappingConfidence = 'MATCHED' | 'GUESSED' | 'MISSING'

export interface SuggestedCsvMapping {
  mapping: CsvMapping
  confidence: Partial<Record<keyof CsvMapping, MappingConfidence>>
  notes: string[]
}

/**
 * `POST /api/accounts/{id}/imports/preview` (writes nothing): a CSV's headers, first rows and suggested mapping; other
 * formats their period and count; an unrecognized PDF its reason. A mapping-mismatch problem carries it without rows.
 */
export interface ImportPreview {
  format: string
  delimiter?: string
  encoding?: string
  skipRows?: number
  headers?: string[]
  rows?: string[][] | null
  mappingStatus?: 'NONE' | 'MATCHES' | 'MISMATCH'
  suggestedMapping?: SuggestedCsvMapping
  /** When the suggested sign is a guess (step 7e): the first row with an amount, read both ways by the backend. */
  signExample?: SignExample
  periodStart?: IsoDate
  periodEnd?: IsoDate
  transactionCount?: number
  recognized?: boolean
  reason?: UnparsedReason
}

export type Direction = 'MONEY_OUT' | 'MONEY_IN'

/** One row of a CSV read with each sign: the amount without its sign, and its direction either way. */
export interface SignExample {
  row: number
  description: string | null
  amount: Money
  /** Read as printed: purchases are negative in the file. */
  asIs: Direction
  /** Every amount flipped: purchases are positive in the file. */
  inverted: Direction
}

export interface StatementDeleteResult {
  statementId: number
  transactionsDeleted: number
  transferLinksRemoved: number
}

/** One side of a transfer as the review queue shows it. */
export interface TxnSummary {
  id: number
  accountId: number
  accountName: string
  accountType: AccountType
  statementId: number
  txnDate: IsoDate
  postDate: IsoDate | null
  amount: Money
  sourceSection: string | null
  rawDescription: string
}

export interface PairSummary {
  outTxnId: number
  inTxnId: number
  daysApart: number
}

/** The review-queue kinds the Import page resolves, typed (ING-07, docs/plans/phase-3c.md §4.1). */
export interface StatementReviewItem {
  kind: 'STATEMENT'
  statementId: number
  accountId: number
  accountName: string
  fileName: string
  periodStart: IsoDate
  periodEnd: IsoDate
  balanceCheck: BalanceCheck
  warnings: string[]
  since: string
}

export interface UnparsedUploadItem {
  kind: 'UNPARSED_UPLOAD'
  uploadId: number
  accountId: number
  accountName: string
  fileName: string
  reason: UnparsedReason
  since: string
}

export interface TransferCandidatesItem {
  kind: 'TRANSFER_CANDIDATES'
  key: string
  txns: TxnSummary[]
  pairs: PairSummary[]
  since: string
}

export interface CompetingCandidateItem {
  kind: 'COMPETING_CANDIDATE'
  key: string
  linkId: number
  out: TxnSummary
  in: TxnSummary
  competitors: TxnSummary[]
  pairs: PairSummary[]
  since: string
}

export interface PossibleMatchItem {
  kind: 'POSSIBLE_MATCH'
  key: string
  outLinkId: number
  inLinkId: number
  out: TxnSummary
  in: TxnSummary
  daysApart: number
  since: string
}

export interface ImportReviewItems {
  statements: StatementReviewItem[]
  uploads: UnparsedUploadItem[]
  candidates: TransferCandidatesItem[]
  competing: CompetingCandidateItem[]
  possibleMatches: PossibleMatchItem[]
}

/** The queue's items of the kinds the Import page resolves, by kind, in the queue's order. */
export function importReviewItems(queue: ReviewQueueResponse): ImportReviewItems {
  const of = <T,>(kind: ReviewItemKind) => queue.items.filter((item) => item.kind === kind) as unknown as T[]
  return {
    statements: of<StatementReviewItem>('STATEMENT'),
    uploads: of<UnparsedUploadItem>('UNPARSED_UPLOAD'),
    candidates: of<TransferCandidatesItem>('TRANSFER_CANDIDATES'),
    competing: of<CompetingCandidateItem>('COMPETING_CANDIDATE'),
    possibleMatches: of<PossibleMatchItem>('POSSIBLE_MATCH'),
  }
}

/** The add-account form's body (`closedOn` is never set here). */
export interface NewAccount {
  name: string
  institution: string
  type: AccountType
  lastFour: string
}

/** The multipart body the import and preview take: one part, `file`. */
function fileBody(file: File): FormData {
  const form = new FormData()
  form.append('file', file, file.name)
  return form
}

export const imports = {
  createAccount: (account: NewAccount) => send<Account>('POST', '/api/accounts', account) as Promise<Account>,
  /** `201` is a statement, `202` an unparsed upload; a refusal throws its problem (409 already-imported included). */
  importFile: async (accountId: number, file: File): Promise<ImportOutcome> => {
    const res = await apiFetch(`/api/accounts/${accountId}/imports`, { method: 'POST', body: fileBody(file) })
    if (!res.ok) {
      throw await problemOf(res)
    }
    return res.status === 202 ? { kind: 'unparsed', upload: await res.json() } : { kind: 'imported', summary: await res.json() }
  },
  preview: async (accountId: number, file: File): Promise<ImportPreview> => {
    const res = await apiFetch(`/api/accounts/${accountId}/imports/preview`, { method: 'POST', body: fileBody(file) })
    if (!res.ok) {
      throw await problemOf(res)
    }
    return res.json()
  },
  /** Saves a mapping for an account that has none (201); the page never replaces a used one. */
  saveMapping: (accountId: number, mapping: CsvMapping) =>
    send<unknown>('PUT', `/api/accounts/${accountId}/csv-mapping`, mapping),
  deleteStatement: (id: number) =>
    send<StatementDeleteResult>('DELETE', `/api/statements/${id}`) as Promise<StatementDeleteResult>,
  /** Accepts a NEEDS_REVIEW statement as it is. */
  reviewStatement: (id: number) => send<StatementDetail>('POST', `/api/statements/${id}/review`),
  dismissUpload: (id: number) => send<UnparsedUpload>('POST', `/api/unparsed-uploads/${id}/dismiss`),
  /** Links two txns by hand. */
  link: (pair: { outTxnId: number; inTxnId: number }) => send<unknown>('POST', '/api/transfers', pair),
  join: (outLinkId: number, inLinkId: number) => send<unknown>('POST', '/api/transfers/join', { outLinkId, inLinkId }),
  dismissJoin: (outLinkId: number, inLinkId: number) =>
    send<null>('POST', '/api/transfers/join/dismiss', { outLinkId, inLinkId }),
  confirmLink: (linkId: number) => send<unknown>('POST', `/api/transfers/${linkId}/confirm`),
  unlink: (linkId: number) => send<null>('DELETE', `/api/transfers/${linkId}`),
  /** Keeps a copy of the statement's file in the vault (Phase 8 §7): the same file again; another is refused. */
  keepFile: async (statementId: number, file: File): Promise<{ statementId: number; fileKept: boolean }> => {
    const res = await apiFetch(`/api/statements/${statementId}/file`, { method: 'PUT', body: fileBody(file) })
    if (!res.ok) {
      throw await problemOf(res)
    }
    return res.json()
  },
  settings: () => getJson<{ afterImport: AfterImport }>('/api/imports/settings'),
  saveSettings: (afterImport: AfterImport) => send<null>('PUT', '/api/imports/settings', { afterImport }),
}

/** What happens to a statement file after a successful import (Phase 8 §7); ASK offers the choices each time. */
export type AfterImport = 'ASK' | 'KEEP' | 'DELETE' | 'BOTH' | 'LEAVE'

// ---------------------------------------------------------------------------- categorization run (step 7e)

export type AiRunStatus = 'OFF' | 'DONE' | 'CAP_REACHED' | 'STOPPED_ON_ERROR'

/** `POST /api/categorization/run`: the stages' counts and a summary the backend added up. */
export interface CategorizationRun {
  merchantsCleaned: number
  ruleCategorized: number
  ruleCleared: number
  memoryApplied: number
  ai: { status: AiRunStatus; itemsRemaining: number; [count: string]: unknown }
  searchIndex: { status: AiRunStatus; embedded: number; [count: string]: unknown }
  summary: {
    categorized: number
    toReview: number
    heldBack: number
    modelCalls: number
    merchantsIndexed: number
  }
}

export const categorization = {
  /** Rules, memory and, when AI is on, Corgi, over every transaction; then the search index. Can take a while. */
  run: () => send<CategorizationRun>('POST', '/api/categorization/run') as Promise<CategorizationRun>,
}

// ---------------------------------------------------------------------------- reads

type Params = Record<string, string | number | boolean | null | undefined>

/** `path` with the params that have a value, in the order given. */
export function withParams(path: string, params: Params = {}): string {
  const query = new URLSearchParams()
  for (const [name, value] of Object.entries(params)) {
    if (value !== null && value !== undefined && value !== '') {
      query.append(name, String(value))
    }
  }
  const text = query.toString()
  return text ? `${path}?${text}` : path
}

/** GET a JSON body; a non-2xx answer throws its problem. */
export async function getJson<T>(path: string, params: Params = {}): Promise<T> {
  const res = await apiFetch(withParams(path, params))
  if (!res.ok) {
    throw await problemOf(res)
  }
  return res.json()
}

// ---------------------------------------------------------------------------- dashboard (Phase 7 §3.1)

/** A change from last month: this month minus last, spending and income as magnitudes, net signed. */
export interface DashboardChange {
  previous: Money
  difference: Money
  changePercent: Percent | null
}

export type SliceKind = 'CATEGORY' | 'OTHER' | 'UNCATEGORIZED'

export interface DashboardSlice {
  kind: SliceKind
  category: CategoryRef | null
  /** Signed: money out is negative. */
  amount: Money
  spendingSharePercent: Percent | null
  members: { category: CategoryRef; amount: Money; spendingSharePercent: Percent | null }[]
}

export interface DashboardResponse {
  month: YearMonth | null
  totals: MonthTotals | null
  categorizedPercent: Percent | null
  vsLastMonth: {
    previousMonth: YearMonth
    spending: DashboardChange
    income: DashboardChange
    net: DashboardChange
    savingsRatePoints: Percent | null
  } | null
  cashFlow: { month: YearMonth; coverage: Coverage; income: Money; spending: Money; net: Money }[]
  spendingSlices: { spending: Money; rows: DashboardSlice[] }
  alerts: { range: MonthRange | null; counts: Partial<Record<AlertType, number>>; newest: Alert[] }
  recurring: { active: number; truncated: boolean; items: RecurringCharge[] }
}

/** `GET /api/dashboard/details`: one Overview total's panel (Phase 7 §3.2). */
export interface DashboardDetails {
  month: YearMonth
  coverage: Coverage
  kind: 'CATEGORY' | 'UNCATEGORIZED' | 'INCOME'
  category: CategoryRef | null
  bucket: TxnBucket
  /** Signed as stored: the figure the Overview showed. */
  total: Money
  spendingSharePercent: Percent | null
  /** `total` as a positive magnitude, as the trends measure it. */
  amount: Money
  average: {
    amount: Money | null
    monthsUsed: number
    monthsWanted: number
    differenceFromAverage: Money | null
    differencePercent: Percent | null
  }
  trend: { month: YearMonth; coverage: Coverage; amount: Money }[]
  topMerchants: MerchantTotalsResponse | null
  /** `GET {path}` with exactly these params lists the rows behind `total`. */
  drillDown: { path: string; params: Record<string, string>; total: MoneyFlow }
}

/** Which details panel: a category, Uncategorized money out, or the income line. */
export type DetailsTarget = { categoryId: number } | { uncategorized: true } | { line: 'INCOME' }

/** `GET /api/chat/usage`: today's estimated chat spend and the caps (null when a cap is off). */
export interface ChatDailyUsage {
  day: IsoDate
  spentToday: string
  maxTurnCost: string | null
  maxDailyCost: string | null
  resetsAt: string
  /** This month's spend and questions from the same ledger (step 7e). */
  month: YearMonth
  spentThisMonth: string
  questionsThisMonth: number
}

export const reads = {
  status: () => getJson<StatusResponse>('/api/status'),
  chatUsage: () => getJson<ChatDailyUsage>('/api/chat/usage'),
  dashboard: (month?: YearMonth | null) => getJson<DashboardResponse>('/api/dashboard', { month }),
  details: (month: YearMonth, target: DetailsTarget) =>
    getJson<DashboardDetails>('/api/dashboard/details', { month, ...target }),
  coverage: () => getJson<CoverageResponse>('/api/analytics/coverage'),
  months: (params: { from?: YearMonth; to?: YearMonth; accountId?: number } = {}) =>
    getJson<MonthlyTotalsResponse>('/api/analytics/months', params),
  categoryTotals: (params: { from?: YearMonth; to?: YearMonth; accountId?: number; categoryId?: number } = {}) =>
    getJson<CategoryTotalsResponse>('/api/analytics/categories', params),
  merchants: (params: { from?: YearMonth; to?: YearMonth; categoryId?: number; limit?: number } = {}) =>
    getJson<MerchantTotalsResponse>('/api/analytics/merchants', params),
  trends: (params: { month?: YearMonth; categoryId?: number } = {}) =>
    getJson<TrendsResponse>('/api/analytics/trends', params),
  drillDown: (params: Params) => getJson<DrillDownResponse>('/api/analytics/transactions', params),
  recurring: (params: { status?: 'ACTIVE' | 'ENDED' | 'ALL'; accountId?: number; limit?: number } = {}) =>
    getJson<RecurringChargesResponse>('/api/analytics/recurring', params),
  alerts: (
    params: { from?: YearMonth; to?: YearMonth; type?: AlertType; includeDismissed?: boolean; limit?: number } = {},
  ) => getJson<AlertListResponse>('/api/alerts', params),
  alert: (key: string) => getJson<Alert>(`/api/alerts/${encodeURIComponent(key)}`),
  accounts: () => getJson<Account[]>('/api/accounts'),
  categories: () => getJson<Category[]>('/api/categories'),
  transactions: (params: Params = {}) => getJson<TransactionPage>('/api/transactions', params),
  transaction: (id: number | string) => getJson<Transaction>(`/api/transactions/${encodeURIComponent(id)}`),
  reviewQueue: (params: { kind?: ReviewItemKind; accountId?: number } = {}) =>
    getJson<ReviewQueueResponse>('/api/review-queue', params),
  /** One account's statements, newest period first (step 7d). */
  statements: (accountId: number) => getJson<StatementListItem[]>(`/api/accounts/${accountId}/statements`),
  statement: (id: number | string) => getJson<StatementDetail>(`/api/statements/${encodeURIComponent(id)}`),
}

// ---------------------------------------------------------------------------- chat (CHT-01–CHT-06, Phase 6)

/** A transaction a citation points at, re-resolved on every read: `GONE` once its statement was deleted. */
export interface CitedTxn {
  status: 'FOUND' | 'GONE'
  fingerprint: string
  id: number | null
  accountId: number | null
  accountName: string | null
  txnDate: IsoDate | null
  postDate: IsoDate | null
  amount: Money | null
  merchant: string | null
  category: string | null
  statement: { id: number; periodStart: IsoDate; periodEnd: IsoDate } | null
  sourceLine: { number: number | null; text: string | null } | null
}

/** The rows behind a total: `GET {path}` with exactly these params lists them (UI-04). */
export interface CitationDrillDown {
  path: string
  params: Record<string, string>
  total: MoneyFlow
  count: number
  sampleTxns: CitedTxn[]
}

/** One citation of an answer, by the marker its text shows (`[1]`). Links are built from these fields only. */
export type Citation =
  | { kind: 'TRANSACTION'; marker: number; txn: CitedTxn }
  | {
      kind: 'TOTAL'
      marker: number
      label: string
      figures: {
        field: string
        value: string
        relation: 'SUM' | 'DERIVED'
        component: 'NET' | 'MONEY_OUT' | 'MONEY_IN' | 'COUNT' | null
        terms: { sign: 1 | -1; drillDown: CitationDrillDown }[]
      }[]
      drillDowns: CitationDrillDown[]
    }
  | { kind: 'ALERT'; marker: number; alertKey: string; type: AlertType; month: YearMonth; txns: CitedTxn[] }
  | { kind: 'SERIES'; marker: number; accountId: number; merchant: string; drillDown: CitationDrillDown }

export type LimitHit = 'ROUNDS' | 'TOOL_CALLS' | 'TOKENS' | 'TIME' | 'COST'
export type AnswerSource = 'MODEL' | 'FALLBACK' | 'ERROR'

export interface ChatUsage {
  model: string | null
  rounds: number
  inputTokens?: number | null
  outputTokens?: number | null
  cacheReadTokens?: number | null
  cacheWriteTokens?: number | null
  callsWithoutUsage?: number
  /** Dollars, six decimals; null when the model has no price. */
  estimatedCost: string | null
}

export interface ChatGrounding {
  status: 'GROUNDED' | 'NO_FIGURES' | 'FALLBACK'
  figuresChecked: number
  retried: boolean
  ungrounded: number
}

/** One answered turn, as `POST /api/chat/conversations/{id}/turns` returns it. */
export interface ChatTurn {
  conversationId: number
  turn: number
  /** The answer as shown, with markers `[1]`; for a fallback, the Java-built list of the figures. */
  answer: string
  answerSource: AnswerSource
  grounding?: ChatGrounding
  citations: Citation[]
  dataNotes?: { month: YearMonth; type: MonthFlagType; accountId: number }[]
  usage: ChatUsage
  limitHit: LimitHit | null
}

/** A stored turn, as a conversation's read returns it; `answer` is null for a turn that failed. */
export interface StoredTurn {
  turn: number
  question: string
  outboundQuestion: string | null
  answer: string | null
  answerSource: AnswerSource
  grounding: ChatGrounding | null
  citations: Citation[]
  limitHit: LimitHit | null
  usage: ChatUsage | null
  createdAt: string
}

export interface ConversationItem {
  id: number
  title: string | null
  turns: number
  /** Its turns' estimated costs, summed by the backend (six decimals); null when no turn has a price. */
  totalCost: string | null
  updatedAt: string
}

export interface Conversation {
  id: number
  title: string | null
  /** As on the list. */
  totalCost: string | null
  createdAt: string
  updatedAt: string
  turns: StoredTurn[]
}

/**
 * An RFC 9457 problem from the API, with its `detail` as the message. `body` is the whole problem as returned, for
 * the members some problems add (`statementId`, `line`, `preview`, `errors`).
 */
export class ApiProblem extends Error {
  constructor(
    readonly status: number,
    readonly type: string | undefined,
    detail: string,
    readonly body: Record<string, unknown> = {},
  ) {
    super(detail)
  }

  /** The problem's `title`, if it has one. */
  get title(): string | undefined {
    return typeof this.body.title === 'string' ? this.body.title : undefined
  }

  /** Seconds to wait, from a 429's `Retry-After`; null otherwise. */
  retryAfter: number | null = null

  /** The type's last segment (`urn:finalysis:problem:already-imported` → `already-imported`). */
  get slug(): string | undefined {
    return this.type?.split(':').at(-1)
  }
}

async function problemOf(res: Response): Promise<ApiProblem> {
  let problem: ApiProblem
  try {
    const body = await res.json()
    problem = new ApiProblem(res.status, body.type, body.detail ?? `Request failed: ${res.status}`, body)
  } catch {
    problem = new ApiProblem(res.status, undefined, `Request failed: ${res.status}`)
  }
  const retryAfter = Number(res.headers?.get?.('Retry-After'))
  problem.retryAfter = Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : null
  return problem
}

// ---------------------------------------------------------------------------- phone access (Phase 8 §9)

export interface AccessStatus {
  enabled: boolean
  address: string | null
  port: number
  open: boolean
  /** Why it isn't open: `address-gone`, `too-many-failures`, `listener-failed`, `locked`, `no-server`. */
  reason: string | null
  url: string | null
  caFingerprint: string | null
  availableAddresses: string[]
  sessions: number
}

export const access = {
  /** Whether this browser is logged in; `lan` is true on the phone (the LAN listener). */
  session: () => getJson<{ authenticated: boolean; lan: boolean }>('/api/access/session'),
  login: (password: string) => send<null>('POST', '/api/access/login', { password }),
  status: () => getJson<AccessStatus>('/api/access'),
  save: (setting: { enabled: boolean; address: string | null; port: number }) =>
    send<AccessStatus>('PUT', '/api/access', setting) as Promise<AccessStatus>,
  /** The local CA, to save and send to the phone. */
  caUrl: '/api/access/ca.cer',
}

// ---------------------------------------------------------------------------- the vault (Phase 8 §5.2)

/** Setup, unlock, recovery, lock, password change and settings. Passwords are sent once and never kept. */
export const vault = {
  /** Creates the vault; answers the recovery key, shown once. */
  setup: (password: string) =>
    send<{ recoveryKey: string }>('POST', '/api/vault/setup', { password }) as Promise<{ recoveryKey: string }>,
  unlock: (password: string) => send<null>('POST', '/api/vault/unlock', { password }),
  recover: (recoveryKey: string, newPassword: string) =>
    send<null>('POST', '/api/vault/recover', { recoveryKey, newPassword }),
  lock: () => send<null>('POST', '/api/vault/lock'),
  changePassword: (currentPassword: string, newPassword: string) =>
    send<null>('POST', '/api/vault/password', { currentPassword, newPassword }),
  saveSettings: (idleLockMinutes: number) => send<null>('PUT', '/api/vault/settings', { idleLockMinutes }),
  /** The "user is here" signal for the idle lock; failures are ignored (a 423 still reaches the listeners). */
  activity: () => send<null>('POST', '/api/vault/activity').catch(() => null),
}

export const chat = {
  list: (page = 0, size = 50) =>
    getJson<{ items: ConversationItem[]; page: number; size: number; totalPages: number }>('/api/chat/conversations', {
      page,
      size,
    }),
  get: (id: number | string) => getJson<Conversation>(`/api/chat/conversations/${encodeURIComponent(id)}`),
  delete: (id: number) => send<null>('DELETE', `/api/chat/conversations/${id}`),
}

export async function createConversation(): Promise<{ id: number }> {
  const res = await apiFetch('/api/chat/conversations', { method: 'POST' })
  if (!res.ok) {
    throw await problemOf(res)
  }
  return res.json()
}

export async function askTurn(conversationId: number, message: string): Promise<ChatTurn> {
  const res = await apiFetch(`/api/chat/conversations/${conversationId}/turns`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message }),
  })
  if (!res.ok) {
    throw await problemOf(res)
  }
  return res.json()
}
