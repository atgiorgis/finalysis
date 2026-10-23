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
import accounts from './fixtures/accounts.json'
import alertsAll from './fixtures/alerts-all.json'
import categoriesAll from './fixtures/analytics-categories.json'
import categoriesCompleteMonth from './fixtures/analytics-categories-complete-month.json'
import drillDownTopCategory from './fixtures/analytics-drilldown-top-category.json'
import merchantsCompleteMonth from './fixtures/analytics-merchants-complete-month.json'
import months from './fixtures/analytics-months.json'
import recurringAll from './fixtures/analytics-recurring-all.json'
import trendsCompleteMonth from './fixtures/analytics-trends-complete-month.json'
import trendsTopCategory from './fixtures/analytics-trends-top-category.json'
import categories from './fixtures/categories.json'
import chatConversations from './fixtures/chat-conversations.json'
import coverage from './fixtures/coverage.json'
import reviewQueue from './fixtures/review-queue.json'
import statusOff from './fixtures/status-off.json'
import transactionDetail from './fixtures/transaction-detail.json'
import transactionsPage from './fixtures/transactions-page.json'
import transactionsUncategorized from './fixtures/transactions-uncategorized.json'

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
