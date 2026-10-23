import { useEffect, useState } from 'react'
import { fetchStatus, type AiStatus, type StatusResponse } from './api'
import ChatPage from './ChatPage'

const PROVIDER_NAMES: Record<string, string> = { bedrock: 'Amazon Bedrock' }

/**
 * Shown for as long as cloud AI is on: what leaves the machine, and to whom (SEC-01). Categorization sends merchant
 * names and amounts; chat sends the question and the figures its tools return.
 */
function CloudAiBanner({ ai }: { ai: AiStatus }) {
  if (ai.mode !== 'CLOUD') {
    return null
  }
  const provider = (ai.provider && PROVIDER_NAMES[ai.provider]) ?? ai.provider ?? 'a cloud provider'
  return (
    <div role="status" className="cloud-ai-banner">
      Cloud AI is on ({provider}): merchant names and amounts are sent for categorization, and chat questions with
      the figures they need are sent for answers.
    </div>
  )
}

export default function App() {
  const [status, setStatus] = useState<StatusResponse | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    fetchStatus()
      .then(setStatus)
      .catch((e: Error) => setError(e.message))
  }, [])

  return (
    <>
      {status && <CloudAiBanner ai={status.ai} />}
      <main>
        <h1>Finalysis</h1>
        {error && <p className="error">Backend unreachable: {error}</p>}
        {!error && !status && <p>Connecting to backend…</p>}
        {status && (
          <p>
            Backend status: <strong>{status.status}</strong> (
            {new Date(status.timestamp).toLocaleString()})
          </p>
        )}
        {status && <ChatPage />}
      </main>
    </>
  )
}
