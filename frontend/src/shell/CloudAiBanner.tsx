import type { AiStatus } from '../api'

const PROVIDER_NAMES: Record<string, string> = { bedrock: 'Amazon Bedrock' }

/** The provider's display name, or its id as the backend reports it. */
export function providerName(ai: AiStatus): string {
  return (ai.provider && PROVIDER_NAMES[ai.provider]) ?? ai.provider ?? 'a cloud provider'
}

/**
 * Shown for as long as cloud AI is on (UI-05): what leaves the machine, and to whom, in the wording SEC-01 records.
 * Categorization sends merchant names and amounts; chat sends the question and the figures its tools return.
 */
export default function CloudAiBanner({ ai }: { ai: AiStatus }) {
  if (ai.path !== 'CLOUD') {
    return null
  }
  return (
    <div role="status" className="cloud-ai-banner">
      Cloud AI is on ({providerName(ai)}): merchant names and amounts are sent for categorization, and chat questions
      with the figures they need are sent for answers.
    </div>
  )
}
