import type { AiPath, AiStatus, ChatUsage } from './api'
import { formatCost } from './format'

/**
 * Corgi: the display name of every AI feature in Finalysis (Phase 7 step 7e). Only a name on screen: the model behind
 * it is the configured one, and code identifiers keep their own names. Change it here and every screen follows.
 *
 * Where Corgi runs is its **path** (docs/plans/phase-9.md §3, §16): Cloud, Self-hosted or Local, or Off.
 * A model's size has its own word, never used for a path (`pathNaming.test.ts`).
 */
export const AI_NAME = 'Corgi'

export const CHAT_TAGLINE = 'Reads your books. Never changes them.'

export type PathTone = 'cloud' | 'self-hosted' | 'local' | 'off'

/** The three paths a card offers, in their order on Settings → Corgi. */
export const PATHS: Exclude<AiPath, 'OFF'>[] = ['CLOUD', 'SELF_HOSTED', 'LOCAL']

export const PATH_NAMES: Record<AiPath, string> = {
  CLOUD: 'Cloud',
  SELF_HOSTED: 'Self-hosted',
  LOCAL: 'Local',
  OFF: 'Off',
}

const TONES: Record<AiPath, PathTone> = { CLOUD: 'cloud', SELF_HOSTED: 'self-hosted', LOCAL: 'local', OFF: 'off' }

/** A cloud provider's name in prose; one the UI doesn't know is named as the backend reports it. */
const PROVIDER_NAMES: Record<string, string> = { bedrock: 'Amazon Bedrock' }

export function providerName(ai: AiStatus): string {
  return (ai.provider && PROVIDER_NAMES[ai.provider]) ?? ai.provider ?? 'a cloud provider'
}

/** A provider's name on the Cloud badge. */
const PROVIDER_LABELS: Record<string, string> = { bedrock: 'Bedrock' }

/** Whether the status says Corgi can't run on its selected path (not OFF, not locked). */
export function isOff(ai: AiStatus | null | undefined): boolean {
  return !ai?.path || ai.path === 'OFF' || ai.reachable === false
}

/**
 * The badge of a path (§3.5): Cloud amber "Cloud · Bedrock", Self-hosted blue "Self-hosted · <name>", Local green
 * "Local". `ai` gives the provider and the server's name; without them the badge names the path alone.
 */
export function badgeFor(path: AiPath, ai?: AiStatus | null): { label: string; tone: PathTone } {
  switch (path) {
    case 'CLOUD': {
      const provider = ai?.provider ? (PROVIDER_LABELS[ai.provider] ?? ai.provider) : 'Bedrock'
      return { label: `Cloud · ${provider}`, tone: 'cloud' }
    }
    case 'SELF_HOSTED':
      return { label: ai?.server ? `Self-hosted · ${ai.server}` : 'Self-hosted', tone: 'self-hosted' }
    default:
      return { label: PATH_NAMES[path], tone: TONES[path] }
  }
}

/**
 * Where Corgi runs, from `GET /api/status`, for the chat header: the path's badge, or "Corgi is off" in muted ink when
 * the path can't run now; none when the path is Off or the vault is locked.
 */
export function pathBadge(ai: AiStatus): { label: string; tone: PathTone } | null {
  if (!ai.path || ai.path === 'OFF') {
    return null
  }
  if (ai.reachable === false) {
    return { label: `${AI_NAME} is off`, tone: 'off' }
  }
  return badgeFor(ai.path, ai)
}

/** SEC-01's wordings (§3.5), pinned word for word by the tests. Off shows none. */
export function bannerText(path: AiPath, ai?: AiStatus | null): string | null {
  switch (path) {
    case 'CLOUD':
      return (
        `Cloud AI is on (${ai ? providerName(ai) : 'Amazon Bedrock'}): merchant names and amounts are sent for ` +
        'categorization, and chat questions with the figures they need are sent for answers.'
      )
    case 'SELF_HOSTED':
      return (
        `${AI_NAME} runs on your own server (${ai?.server ?? 'your server'}): merchant names and amounts are sent to ` +
        'it for categorization, and chat questions with the figures they need are sent for answers.'
      )
    case 'LOCAL':
      return `${AI_NAME} runs on this computer: nothing is sent anywhere.`
    default:
      return null
  }
}

export function pathTone(path: AiPath): PathTone {
  return TONES[path]
}

/**
 * "Corgi is off: <reason>. Install Ollama or choose a path in Settings." when the selected path can't run; null when
 * it can, and for Off (which is a choice, not a fault) or a locked vault.
 */
export function offText(ai: AiStatus | null | undefined): string | null {
  if (!ai?.path || ai.path === 'OFF' || ai.reachable !== false) {
    return null
  }
  const reason = ai.reason ? `: ${ai.reason}` : ''
  return `${AI_NAME} is off${reason}. Install Ollama or choose a path in Settings.`
}

/** What a local model costs, on every cost line while the path is Local. */
export const LOCAL_COST = '$0.00 · local'

/** Self-hosted's summary lines (the day's and the month's spend count Cloud only). */
export const SELF_HOSTED_COST = 'No token cost on Self-hosted.'

/**
 * A summary cost as shown, for the selected path: `$0.00 · local` on Local, "No token cost on Self-hosted." on
 * Self-hosted, otherwise the backend's estimate through `format` (`formatCost` to the cent by default).
 */
export function costText(
  value: string | null | undefined,
  ai: AiStatus | null | undefined,
  format: (value: string | null | undefined) => string = formatCost,
): string {
  if (ai?.path === 'LOCAL') {
    return LOCAL_COST
  }
  if (ai?.path === 'SELF_HOSTED') {
    return SELF_HOSTED_COST
  }
  return format(value)
}

/**
 * One turn's cost, on the path the turn ran on (its usage's `path`; the status's for turns stored before paths): the
 * estimate on Cloud, `$0.00 · local` on Local, and on Self-hosted the backend's note as it returned it.
 */
export function turnCostText(
  usage: ChatUsage | null | undefined,
  ai: AiStatus | null | undefined,
  format: (value: string | null | undefined) => string,
): string {
  const path = usage?.path ?? ai?.path
  if (path === 'LOCAL') {
    return LOCAL_COST
  }
  if (path === 'SELF_HOSTED') {
    return usage?.costNote ?? SELF_HOSTED_COST
  }
  return format(usage?.estimatedCost)
}

/** A Tailscale name as the backend takes it: dot-separated labels of letters, digits and inner hyphens; never a URL. */
const SERVER_NAME = /^(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$/

export function validServerName(name: string): boolean {
  return SERVER_NAME.test(name)
}

/** An AWS region id (`us-east-1`): lowercase letters, digits and hyphens. */
const REGION = /^[a-z]{2}(?:-[a-z0-9]+)+$/

export function validRegion(region: string): boolean {
  return region.length <= 32 && REGION.test(region)
}
