import type { Citation, CitationDrillDown } from './api'

/**
 * Where a citation opens (docs/plans/phase-7.md §3.5). Built from the citation object's own fields only, never from
 * the answer's text, which the model wrote and which can quote untrusted merchant text (the owner's rule, step 3b).
 * `/drill` forwards only the drill-down's own parameters to the API.
 */

/** The rows behind a total: its exact parameters, a label, and the total they must add up to. */
export function drillHref(drillDown: CitationDrillDown, label: string): string {
  const params = new URLSearchParams(drillDown.params)
  params.set('label', label)
  params.set('expect', drillDown.total.net)
  return `/drill?${params}`
}

/** The alert in the Alerts tab, with the month it belongs to, so the tab's three months hold it. */
export function alertHref(citation: Extract<Citation, { kind: 'ALERT' }>): string {
  return `/alerts?${new URLSearchParams({ month: citation.month, key: citation.alertKey })}`
}

/** The series in the Recurring tab. */
export function seriesHref(citation: Extract<Citation, { kind: 'SERIES' }>): string {
  return `/recurring?${new URLSearchParams({ series: `${citation.accountId}:${citation.merchant}` })}`
}
