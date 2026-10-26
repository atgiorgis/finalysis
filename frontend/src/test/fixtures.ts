/**
 * API fixtures captured from the sample data with AI off (`npm run capture-fixtures`, docs/plans/phase-7.md §6.1),
 * typed against api.ts. `typed` checks each file's structure at compile time (`npm test` runs `tsc` first): a field
 * the type requires but the JSON lacks, or a field of the wrong type, fails the build. JSON imports give enum values
 * as plain strings, so string unions are compared as strings.
 */
import type {
  Account,
  AlertListResponse,
  Category,
  CategoryTotalsResponse,
  CoverageResponse,
  DashboardDetails,
  DashboardResponse,
  DrillDownResponse,
  MerchantTotalsResponse,
  MonthlyTotalsResponse,
  RecurringChargesResponse,
  CategorizationRun,
  ImportPreview,
  ImportSummary,
  ReviewQueueResponse,
  StatementDeleteResult,
  StatementDetail,
  StatementListItem,
  StatusResponse,
  Transaction,
  TransactionPage,
  TrendsResponse,
  UnparsedUpload,
} from '../api'
import accounts from './fixtures/accounts.json' with { type: 'json' }
import accountCreated from './fixtures/account-created.json' with { type: 'json' }
import importAlreadyImported from './fixtures/import-already-imported.json' with { type: 'json' }
import importMappingMismatch from './fixtures/import-mapping-mismatch.json' with { type: 'json' }
import importMappingRequired from './fixtures/import-mapping-required.json' with { type: 'json' }
import importNeedsReview from './fixtures/import-needs-review.json' with { type: 'json' }
import importParseError from './fixtures/import-parse-error.json' with { type: 'json' }
import importPreviewCard from './fixtures/import-preview-card.json' with { type: 'json' }
import categorizationRunOff from './fixtures/categorization-run-off.json' with { type: 'json' }
import importPreviewCsv from './fixtures/import-preview-csv.json' with { type: 'json' }
import importSummary from './fixtures/import-summary.json' with { type: 'json' }
import importUnparsed from './fixtures/import-unparsed.json' with { type: 'json' }
import importUnparsedScan from './fixtures/import-unparsed-scan.json' with { type: 'json' }
import reviewQueueCandidates from './fixtures/review-queue-candidates.json' with { type: 'json' }
import reviewQueueCompeting from './fixtures/review-queue-competing.json' with { type: 'json' }
import reviewQueuePossibleMatch from './fixtures/review-queue-possible-match.json' with { type: 'json' }
import statementDeleted from './fixtures/statement-deleted.json' with { type: 'json' }
import statementDetail from './fixtures/statement-detail.json' with { type: 'json' }
import statementsAccount1 from './fixtures/statements-account-1.json' with { type: 'json' }
import statementsAccount2 from './fixtures/statements-account-2.json' with { type: 'json' }
import statementsAccount3 from './fixtures/statements-account-3.json' with { type: 'json' }
import statementsAccount4 from './fixtures/statements-account-4.json' with { type: 'json' }
import statementsAccount5 from './fixtures/statements-account-5.json' with { type: 'json' }
import statementsFixtureChecking from './fixtures/statements-fixture-checking.json' with { type: 'json' }
import transactionsStatement from './fixtures/transactions-statement.json' with { type: 'json' }
import alertsAll from './fixtures/alerts-all.json' with { type: 'json' }
import categoriesAll from './fixtures/analytics-categories.json' with { type: 'json' }
import categoriesCompleteMonth from './fixtures/analytics-categories-complete-month.json' with { type: 'json' }
import drillDownTopCategory from './fixtures/analytics-drilldown-top-category.json' with { type: 'json' }
import merchantsCompleteMonth from './fixtures/analytics-merchants-complete-month.json' with { type: 'json' }
import months from './fixtures/analytics-months.json' with { type: 'json' }
import recurringAll from './fixtures/analytics-recurring-all.json' with { type: 'json' }
import trendsCompleteMonth from './fixtures/analytics-trends-complete-month.json' with { type: 'json' }
import trendsTopCategory from './fixtures/analytics-trends-top-category.json' with { type: 'json' }
import categories from './fixtures/categories.json' with { type: 'json' }
import chatConversations from './fixtures/chat-conversations.json' with { type: 'json' }
import coverage from './fixtures/coverage.json' with { type: 'json' }
import dashboardCompleteMonth from './fixtures/dashboard-complete-month.json' with { type: 'json' }
import dashboardLatest from './fixtures/dashboard-latest.json' with { type: 'json' }
import detailsCategory from './fixtures/details-category.json' with { type: 'json' }
import detailsIncome from './fixtures/details-income.json' with { type: 'json' }
import detailsOtherMember from './fixtures/details-other-member.json' with { type: 'json' }
import drillDownCategory from './fixtures/drilldown-category.json' with { type: 'json' }
import drillDownIncome from './fixtures/drilldown-income.json' with { type: 'json' }
import drillDownOtherMember from './fixtures/drilldown-other-member.json' with { type: 'json' }
import reviewQueue from './fixtures/review-queue.json' with { type: 'json' }
import statusOff from './fixtures/status-off.json' with { type: 'json' }
import transactionDetail from './fixtures/transaction-detail.json' with { type: 'json' }
import transactionsPage from './fixtures/transactions-page.json' with { type: 'json' }
import transactionsUncategorized from './fixtures/transactions-uncategorized.json' with { type: 'json' }

/** `T` with every string literal widened to `string`, as a JSON import types it. */
type Widen<T> = T extends string
  ? string
  : T extends number
    ? number
    : T extends boolean
      ? boolean
      : T extends null | undefined
        ? T
        : T extends readonly (infer U)[]
          ? Widen<U>[]
          : T extends object
            ? { [K in keyof T]: Widen<T[K]> }
            : T

function typed<T>(json: Widen<T>): T {
  return json as unknown as T
}

/** An RFC 9457 problem as the API returns it, with the members import problems add. */
export interface ProblemBody {
  type: string
  title: string
  status: number
  detail: string
  instance: string
  statementId?: number
  line?: number
  preview?: ImportPreview
}

export const fixtures = {
  statusOff: typed<StatusResponse>(statusOff),
  coverage: typed<CoverageResponse>(coverage),
  accounts: typed<Account[]>(accounts),
  categories: typed<Category[]>(categories),
  months: typed<MonthlyTotalsResponse>(months),
  categoriesAll: typed<CategoryTotalsResponse>(categoriesAll),
  categoriesCompleteMonth: typed<CategoryTotalsResponse>(categoriesCompleteMonth),
  merchantsCompleteMonth: typed<MerchantTotalsResponse>(merchantsCompleteMonth),
  trendsCompleteMonth: typed<TrendsResponse>(trendsCompleteMonth),
  trendsTopCategory: typed<TrendsResponse>(trendsTopCategory),
  drillDownTopCategory: typed<DrillDownResponse>(drillDownTopCategory),
  recurringAll: typed<RecurringChargesResponse>(recurringAll),
  alertsAll: typed<AlertListResponse>(alertsAll),
  transactionsPage: typed<TransactionPage>(transactionsPage),
  transactionsUncategorized: typed<TransactionPage>(transactionsUncategorized),
  transactionDetail: typed<Transaction>(transactionDetail),
  reviewQueue: typed<ReviewQueueResponse>(reviewQueue),
  chatConversations: chatConversations,
  /** September 2026, complete: seven slices with Other (Health, Subscriptions), alerts, recurring. */
  dashboardCompleteMonth: typed<DashboardResponse>(dashboardCompleteMonth),
  /** October 2026, partial with no txns yet: no slices, three partial-coverage flags. */
  dashboardLatest: typed<DashboardResponse>(dashboardLatest),
  /** September's Groceries panel (the second slice) and the first page of its rows: 8, below the average. */
  detailsCategory: typed<DashboardDetails>(detailsCategory),
  drillDownCategory: typed<DrillDownResponse>(drillDownCategory),
  /** September's Health panel, one of Other's members: 1 row, above the average. */
  detailsOtherMember: typed<DashboardDetails>(detailsOtherMember),
  drillDownOtherMember: typed<DrillDownResponse>(drillDownOtherMember),
  /** September's income panel: 2 rows, no merchants, no share. */
  detailsIncome: typed<DashboardDetails>(detailsIncome),
  drillDownIncome: typed<DrillDownResponse>(drillDownIncome),

  // The Import page (step 7d): the sample accounts' statements, then an import pass on new "Fixture Bank" accounts.
  /** Each sample account's statements, by account id, as `GET /api/accounts/{id}/statements` returns them. */
  statementsByAccount: {
    1: typed<StatementListItem[]>(statementsAccount1),
    2: typed<StatementListItem[]>(statementsAccount2),
    3: typed<StatementListItem[]>(statementsAccount3),
    4: typed<StatementListItem[]>(statementsAccount4),
    5: typed<StatementListItem[]>(statementsAccount5),
  } as Record<number, StatementListItem[]>,
  accountCreated: typed<Account>(accountCreated),
  /** 422: the account has no CSV mapping (no preview in the problem). */
  importMappingRequired: typed<ProblemBody>(importMappingRequired),
  /** The preview the page then reads: headers, first rows and the suggested mapping. */
  importPreviewCsv: typed<ImportPreview>(importPreviewCsv),
  /** A card CSV whose sign the suggester guessed (INVERTED): it comes with one row read both ways (step 7e). */
  importPreviewCard: typed<ImportPreview>(importPreviewCard),
  /** `POST /api/categorization/run` with AI off, after the import pass: rules only (step 7e). */
  categorizationRunOff: typed<CategorizationRun>(categorizationRunOff),
  /** 201: a CSV with balances that check out, no warnings. */
  importSummary: typed<ImportSummary>(importSummary),
  /** 201: NEEDS_REVIEW with "reconciliation failed" warnings. */
  importNeedsReview: typed<ImportSummary>(importNeedsReview),
  /** 409: the same file again. */
  importAlreadyImported: typed<ProblemBody>(importAlreadyImported),
  /** 422: another layout than the account's mapping; the preview without rows. */
  importMappingMismatch: typed<ProblemBody>(importMappingMismatch),
  /** 422: a PDF layout recognized but not read yet. */
  importParseError: typed<ProblemBody>(importParseError),
  /** 202: an unrecognized PDF, and a scan. */
  importUnparsed: typed<UnparsedUpload>(importUnparsed),
  importUnparsedScan: typed<UnparsedUpload>(importUnparsedScan),
  /** The queue with a candidate group, then with a competing candidate, then with a possible match. */
  reviewQueueCandidates: typed<ReviewQueueResponse>(reviewQueueCandidates),
  reviewQueueCompeting: typed<ReviewQueueResponse>(reviewQueueCompeting),
  reviewQueuePossibleMatch: typed<ReviewQueueResponse>(reviewQueuePossibleMatch),
  statementDeleted: typed<StatementDeleteResult>(statementDeleted),
  statementsFixtureChecking: typed<StatementListItem[]>(statementsFixtureChecking),
  statementDetail: typed<StatementDetail>(statementDetail),
  /** `GET /api/transactions?statementId=` for the fixture checking statement. */
  transactionsStatement: typed<TransactionPage>(transactionsStatement),
}

/** `GET /api/chat/usage` with nothing spent and the default caps (hand-written: the capture runs with AI off). */
export const usageFixture = {
  day: '2026-10-23',
  spentToday: '0.000000',
  maxTurnCost: '0.15',
  maxDailyCost: '1.00',
  resetsAt: '2026-10-24T00:00:00-04:00',
  month: '2026-10',
  spentThisMonth: '0.000000',
  questionsThisMonth: 0,
}

/**
 * A `fetch` stub that answers GETs from `routes` by path (query ignored unless a key includes one); anything else
 * is a 404 problem, so a test notices a read it didn't expect.
 */
export function fetchFrom(routes: Record<string, unknown>) {
  return async (input: string | URL | Request): Promise<Response> => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const path = url.split('?')[0]
    const body = routes[url] ?? routes[path]
    if (body === undefined) {
      return Response.json({ type: 'not-found', detail: `no fixture for ${url}` }, { status: 404 })
    }
    return Response.json(body)
  }
}
