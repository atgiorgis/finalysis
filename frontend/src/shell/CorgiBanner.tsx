import type { AiStatus } from '../api'
import { bannerText, pathTone } from '../corgi'

/**
 * Shown on every tab while Corgi has a path (UI-05, docs/plans/phase-9.md §3.5): what leaves the machine, and to whom,
 * in the wording SEC-01 records, with the path's colour on its dot. Off, or a locked vault, shows none.
 */
export default function CorgiBanner({ ai }: { ai: AiStatus }) {
  const text = ai.path ? bannerText(ai.path, ai) : null
  if (!ai.path || !text) {
    return null
  }
  return (
    <div role="status" className={`corgi-banner corgi-banner-${pathTone(ai.path)}`}>
      {text}
    </div>
  )
}
