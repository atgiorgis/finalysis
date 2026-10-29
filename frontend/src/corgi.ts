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

/**
 * Where Corgi runs, from `GET /api/status`: local in the income colour, cloud in s3 ink; none when AI is off or the
 * vault is locked. Self-hosted shares cloud's tone until its own colour comes with Settings → Corgi (step 9b-2).
 */
export function pathBadge(ai: AiStatus): { label: string; tone: 'local' | 'cloud' } | null {
  if (!ai.path || ai.path === 'OFF') {
    return null
  }
  const provider = ai.provider ? (PROVIDER_LABELS[ai.provider] ?? ai.provider) : null
  const where = ai.path === 'LOCAL' ? 'Local' : ai.path === 'SELF_HOSTED' ? 'Self-hosted' : 'Cloud'
  return { label: provider ? `${where} · ${provider}` : where, tone: ai.path === 'LOCAL' ? 'local' : 'cloud' }
}

/** What a local model costs, on every cost line while the path is Local. */
export const LOCAL_COST = '$0.00 · local'

/**
 * A cost as shown: `$0.00 · local` while the path is Local, otherwise the backend's estimate through `format`
 * (`formatCost` to the cent by default).
 */
export function costText(
  value: string | null | undefined,
  ai: AiStatus | null | undefined,
  format: (value: string | null | undefined) => string = formatCost,
): string {
  return ai?.path === 'LOCAL' ? LOCAL_COST : format(value)
}
