import { useEffect, useState } from 'react'
import { fetchStatus, type StatusResponse } from './api'

export default function App() {
  const [status, setStatus] = useState<StatusResponse | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    fetchStatus()
      .then(setStatus)
      .catch((e: Error) => setError(e.message))
  }, [])

  return (
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
    </main>
  )
}
