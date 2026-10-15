export type AiMode = 'OFF' | 'CLOUD' | 'LOCAL'

/** Where models run; `provider` and `models` are present only when AI is on. */
export interface AiStatus {
  mode: AiMode
  provider?: string
  models?: Record<string, string>
}

export interface StatusResponse {
  app: string
  status: string
  timestamp: string
  ai: AiStatus
}

export async function fetchStatus(): Promise<StatusResponse> {
  const res = await fetch('/api/status')
  if (!res.ok) {
    throw new Error(`Request failed: ${res.status}`)
  }
  return res.json()
}
