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

// ---------------------------------------------------------------------------- chat (CHT-01, Phase 6)

/** A transaction a citation points at; `GONE` when its statement was deleted. */
export interface CitedTxn {
  status: 'FOUND' | 'GONE'
  txnDate?: string | null
  amount?: string | null
  merchant?: string | null
  accountName?: string | null
}

/** One citation of an answer, by the marker its text shows (`[1]`). */
export type Citation =
  | { kind: 'TRANSACTION'; marker: number; txn: CitedTxn }
  | { kind: 'TOTAL'; marker: number; label: string }
  | { kind: 'ALERT'; marker: number; type: string; month: string }
  | { kind: 'SERIES'; marker: number; merchant: string }

export interface ChatUsage {
  model: string | null
  rounds: number
  estimatedCost: string | null
}

/** One answered turn, as `POST /api/chat/conversations/{id}/turns` returns it (fields the page shows). */
export interface ChatTurn {
  conversationId: number
  turn: number
  answer: string
  answerSource: 'MODEL' | 'FALLBACK'
  citations: Citation[]
  usage: ChatUsage
  limitHit: string | null
}

/** An RFC 9457 problem from the API, with its `detail` as the message. */
export class ApiProblem extends Error {
  constructor(
    readonly status: number,
    readonly type: string | undefined,
    detail: string,
  ) {
    super(detail)
  }
}

async function problemOf(res: Response): Promise<ApiProblem> {
  try {
    const body = await res.json()
    return new ApiProblem(res.status, body.type, body.detail ?? `Request failed: ${res.status}`)
  } catch {
    return new ApiProblem(res.status, undefined, `Request failed: ${res.status}`)
  }
}

export async function createConversation(): Promise<{ id: number }> {
  const res = await fetch('/api/chat/conversations', { method: 'POST' })
  if (!res.ok) {
    throw await problemOf(res)
  }
  return res.json()
}

export async function askTurn(conversationId: number, message: string): Promise<ChatTurn> {
  const res = await fetch(`/api/chat/conversations/${conversationId}/turns`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message }),
  })
  if (!res.ok) {
    throw await problemOf(res)
  }
  return res.json()
}
