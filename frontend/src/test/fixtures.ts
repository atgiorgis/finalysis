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
  ReviewQueueResponse,
  StatusResponse,
  Transaction,
  TransactionPage,
  TrendsResponse,
} from '../api'
import accounts from './fixtures/accounts.json' with { type: 'json' }
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
}

/** `GET /api/chat/usage` with nothing spent and the default caps (hand-written: the capture runs with AI off). */
export const usageFixture = {
  day: '2026-10-23',
  spentToday: '0.000000',
  maxTurnCost: '0.15',
  maxDailyCost: '1.00',
  resetsAt: '2026-10-24T00:00:00-04:00',
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
