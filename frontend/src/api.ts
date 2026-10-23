export type AiMode = 'OFF' | 'CLOUD' | 'LOCAL'

/** Where models run; `provider` and `models` are present only when AI is on. */
export interface AiStatus {
  mode: AiMode
  provider?: string
  models?: Record<string, string>
}

export interface StatusResponse {
  app: string
  status: string
  timestamp: string
  ai: AiStatus
}

export async function fetchStatus(): Promise<StatusResponse> {
  const res = await fetch('/api/status')
  if (!res.ok) {
    throw new Error(`Request failed: ${res.status}`)
  }
  return res.json()
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
// With alert dismissal (step 5), the only writes in the UI (docs/plans/phase-7.md §2.4).

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
  const res = await fetch(path, {
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

/** Alert dismissal (ANA-04, V20): with the corrections, the only writes in the UI. */
export const dismissals = {
  /** Dismisses a current alert, with an optional note; answers the alert as it now is. */
  dismiss: (key: string, note: string | null) =>
    send<Alert>('PUT', `/api/alerts/${encodeURIComponent(key)}/dismissal`, note ? { note } : {}) as Promise<Alert>,
  /** Shows a dismissed alert again (204). */
  restore: (key: string) => send<null>('DELETE', `/api/alerts/${encodeURIComponent(key)}/dismissal`),
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
  const res = await fetch(withParams(path, params))
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
  updatedAt: string
}

export interface Conversation {
  id: number
  title: string | null
  createdAt: string
  updatedAt: string
  turns: StoredTurn[]
}

/** An RFC 9457 problem from the API, with its `detail` as the message. */
export class ApiProblem extends Error {
  constructor(
    readonly status: number,
    readonly type: string | undefined,
    detail: string,
  ) {
    super(detail)
  }
}

async function problemOf(res: Response): Promise<ApiProblem> {
  try {
    const body = await res.json()
    return new ApiProblem(res.status, body.type, body.detail ?? `Request failed: ${res.status}`)
  } catch {
    return new ApiProblem(res.status, undefined, `Request failed: ${res.status}`)
  }
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
  const res = await fetch('/api/chat/conversations', { method: 'POST' })
  if (!res.ok) {
    throw await problemOf(res)
  }
  return res.json()
}

export async function askTurn(conversationId: number, message: string): Promise<ChatTurn> {
  const res = await fetch(`/api/chat/conversations/${conversationId}/turns`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message }),
  })
  if (!res.ok) {
    throw await problemOf(res)
  }
  return res.json()
}
