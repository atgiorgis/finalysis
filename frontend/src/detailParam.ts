import type { DashboardResponse, DetailsTarget } from './api'

/**
 * The Overview's open panel, kept in the URL as `?detail=` (docs/plans/phase-7.md §4.4): `cat:<id>`, `uncategorized`,
 * `income` or `other`. Parsing an id is the one number here; it is never a figure.
 */
export type Detail = { kind: 'category'; categoryId: number } | { kind: 'uncategorized' } | { kind: 'income' } | {
  kind: 'other'
}

export function parseDetail(value: string | null): Detail | null {
  if (value === 'uncategorized' || value === 'income' || value === 'other') {
    return { kind: value }
  }
  const match = value ? /^cat:(\d{1,18})$/.exec(value) : null
  return match ? { kind: 'category', categoryId: Number(match[1]) } : null
}

export function detailParam(detail: Detail): string {
  return detail.kind === 'category' ? `cat:${detail.categoryId}` : detail.kind
}

/** The details read for a panel; Other has none (its members each have their own). */
export function detailsTarget(detail: Detail): DetailsTarget | null {
  switch (detail.kind) {
    case 'category':
      return { categoryId: detail.categoryId }
    case 'uncategorized':
      return { uncategorized: true }
    case 'income':
      return { line: 'INCOME' }
    case 'other':
      return null
  }
}

/**
 * The figure the Overview showed for a panel, as the dashboard returned it: a slice's or one of Other's members'
 * amount, Uncategorized money out, or the month's income. Null when the month shows no such figure.
 */
export function shownFigure(dashboard: DashboardResponse, detail: Detail): string | null {
  const slices = dashboard.spendingSlices.rows
  switch (detail.kind) {
    case 'income':
      return dashboard.totals?.income ?? null
    case 'uncategorized':
      return slices.find((s) => s.kind === 'UNCATEGORIZED')?.amount ?? null
    case 'other':
      return slices.find((s) => s.kind === 'OTHER')?.amount ?? null
    case 'category': {
      const slice = slices.find((s) => s.kind === 'CATEGORY' && s.category?.id === detail.categoryId)
      if (slice) {
        return slice.amount
      }
      const member = slices
        .flatMap((s) => s.members)
        .find((m) => m.category.id === detail.categoryId)
      return member?.amount ?? null
    }
  }
}

/** The name the Overview gave a panel's figure. */
export function shownName(dashboard: DashboardResponse, detail: Detail): string {
  switch (detail.kind) {
    case 'income':
      return 'Income'
    case 'uncategorized':
      return 'Uncategorized'
    case 'other':
      return 'Other'
    case 'category': {
      const all = dashboard.spendingSlices.rows.flatMap((s) => [s.category, ...s.members.map((m) => m.category)])
      return all.find((c) => c?.id === detail.categoryId)?.name ?? 'Category'
    }
  }
}
