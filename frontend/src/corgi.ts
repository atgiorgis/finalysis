import type { AiStatus } from './api'
import { formatCost } from './format'

/**
 * Corgi: the display name of every AI feature in Finalysis (Phase 7 step 7e). Only a name on screen: the model behind
 * it is the configured one (Sonnet 4.6 or Haiku 4.5 on Bedrock, or a local model), and code identifiers keep their
 * own names. Change it here and every screen follows.
 */
export const AI_NAME = 'Corgi'

export const CHAT_TAGLINE = 'Reads your books. Never changes them.'

/** A provider's name as the badge shows it; one the UI doesn't know is shown as configured. */
const PROVIDER_LABELS: Record<string, string> = {
  bedrock: 'Bedrock',
  ollama: 'Ollama',
}

/** Where Corgi runs, from `GET /api/status`: local in the income colour, cloud in s3 ink; none when AI is off. */
export function pathBadge(ai: AiStatus): { label: string; tone: 'local' | 'cloud' } | null {
  if (ai.mode === 'OFF') {
    return null
  }
  const provider = ai.provider ? (PROVIDER_LABELS[ai.provider] ?? ai.provider) : null
  const where = ai.mode === 'LOCAL' ? 'Local' : 'Cloud'
  return { label: provider ? `${where} · ${provider}` : where, tone: ai.mode === 'LOCAL' ? 'local' : 'cloud' }
}

/** What a local model costs, on every cost line while the mode is local. */
export const LOCAL_COST = '$0.00 · local'

/**
 * A cost as shown: `$0.00 · local` while the mode is local, otherwise the backend's estimate through `format`
 * (`formatCost` to the cent by default).
 */
export function costText(
  value: string | null | undefined,
  ai: AiStatus | null | undefined,
  format: (value: string | null | undefined) => string = formatCost,
): string {
  return ai?.mode === 'LOCAL' ? LOCAL_COST : format(value)
}
