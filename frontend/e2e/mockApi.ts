import type { Page, Route } from '@playwright/test'
import { conversation, conversations } from '../src/test/chatFixtures'
import { fixtures, usageFixture } from '../src/test/fixtures'

/**
 * Answers every `/api` request of the layout check from the unit tests' fixtures (sample data, AI off), so the check
 * needs no backend. A request with no fixture is a 404 problem, which the pages show as an error; the check then
 * still measures that state.
 */
const exact: Record<string, unknown> = {
  [`/api/dashboard/details?month=2026-09&categoryId=${fixtures.detailsCategory.category?.id}`]: fixtures.detailsCategory,
  [`/api/dashboard/details?month=2026-09&categoryId=${fixtures.detailsOtherMember.category?.id}`]:
    fixtures.detailsOtherMember,
  '/api/dashboard/details?month=2026-09&line=INCOME': fixtures.detailsIncome,
  '/api/dashboard?month=2026-09': fixtures.dashboardCompleteMonth,
  '/api/dashboard?month=2026-10': fixtures.dashboardLatest,
}

const byPath: Record<string, unknown> = {
  '/api/status': fixtures.statusOff,
  '/api/analytics/coverage': fixtures.coverage,
  '/api/accounts': fixtures.accounts,
  '/api/categories': fixtures.categories,
  '/api/analytics/months': fixtures.months,
  '/api/chat/usage': usageFixture,
  '/api/dashboard': fixtures.dashboardCompleteMonth,
  '/api/analytics/transactions': fixtures.drillDownCategory,
  '/api/transactions': fixtures.transactionsPage,
  [`/api/transactions/${fixtures.transactionDetail.id}`]: fixtures.transactionDetail,
  '/api/review-queue': fixtures.reviewQueue,
  '/api/alerts': fixtures.alertsAll,
  '/api/analytics/recurring': fixtures.recurringAll,
  '/api/chat/conversations': conversations,
  '/api/chat/conversations/7': conversation,
  // The Import tab (step 7d).
  ...Object.fromEntries(
    Object.entries(fixtures.statementsByAccount).map(([id, list]) => [`/api/accounts/${id}/statements`, list]),
  ),
  [`/api/statements/${fixtures.statementDetail.id}`]: fixtures.statementDetail,
}

const STATUS = Symbol('status')

/** An answer with a status other than 200, for a problem (409, 422) or a 201/202. */
export interface WithStatus {
  status: number
  json: unknown
}

export function withStatus(status: number, json: unknown): WithStatus {
  return { status, json, [STATUS]: true } as WithStatus
}

function isWithStatus(body: unknown): body is WithStatus {
  return typeof body === 'object' && body !== null && STATUS in body
}

export async function mockApi(page: Page, overrides: Record<string, unknown> = {}) {
  await page.route('**/api/**', async (route: Route) => {
    const url = new URL(route.request().url())
    const key = url.pathname + url.search
    const body = overrides[key] ?? overrides[url.pathname] ?? exact[key] ?? byPath[url.pathname]
    if (body === undefined) {
      await route.fulfill({ status: 404, json: { type: 'not-found', detail: `no fixture for ${key}` } })
    } else if (isWithStatus(body)) {
      await route.fulfill({ status: body.status, json: body.json })
    } else {
      await route.fulfill({ json: body })
    }
  })
}
