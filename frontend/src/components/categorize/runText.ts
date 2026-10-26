import type { CategorizationRun } from '../../api'
import { AI_NAME } from '../../corgi'
import { formatCount } from '../../format'

/** What "held back" means, for its tooltip: the redaction block (SEC-01). */
export const HELD_BACK_TEXT =
  'Held back: nothing of the merchant was left to send once account numbers, names and other identifiers were ' +
  'removed, or the privacy check refused it. It stays here, uncategorized, for you to set.'

function plural(n: number, one: string, many: string): string {
  return `${formatCount(n)} ${n === 1 ? one : many}`
}

/**
 * A run's summary in plain words, each count as the backend added it up: "97 categorized, 34 to review, 1 held back
 * for privacy, 4 model calls". `heldBack` is its own part so the page can attach the tooltip.
 */
export function runParts(run: CategorizationRun): { before: string; heldBack: string | null; after: string } {
  const s = run.summary
  const before = `${formatCount(s.categorized)} categorized, ${formatCount(s.toReview)} to review`
  const heldBack = s.heldBack > 0 ? `${formatCount(s.heldBack)} held back for privacy` : null
  const after =
    run.ai.status === 'OFF' ? `rules only (${AI_NAME} is off)` : plural(s.modelCalls, 'model call', 'model calls')
  return { before, heldBack, after }
}

/** The run's whole summary as one line, for tests and screen readers. */
export function runSummaryText(run: CategorizationRun): string {
  const { before, heldBack, after } = runParts(run)
  return [before, heldBack, after].filter(Boolean).join(', ')
}

/** "126 merchants indexed for search", only when the search index was built in this run. */
export function indexedText(run: CategorizationRun): string | null {
  const n = run.summary.merchantsIndexed
  return n > 0 ? `${plural(n, 'merchant', 'merchants')} indexed for search` : null
}

/** When Corgi stopped before the end: said plainly, so "to review" isn't read as final. */
export function stoppedText(run: CategorizationRun): string | null {
  switch (run.ai.status) {
    case 'CAP_REACHED':
      return `${AI_NAME} reached its limit for one run; the rest wait for the next one.`
    case 'STOPPED_ON_ERROR':
      return `${AI_NAME} stopped after a failed call; the rest wait for the next run.`
    default:
      return null
  }
}
