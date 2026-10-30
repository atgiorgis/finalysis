import { useCallback, useEffect, useRef, useState } from 'react'
import { selfHost, type SelfHostState, type SelfHostStatus } from '../../api'
import { validServerName } from '../../corgi'
import { useDataVersion } from '../../shell/ShellData'

/** How often the status is read while the server is starting or stopping (docs/plans/phase-9.md §5.5). */
export const POLL_MS = 5000

const STATE_TEXT: Record<SelfHostState, string> = {
  STOPPED: 'Stopped',
  STARTING: 'Starting',
  RUNNING: 'Running',
  STOPPING: 'Stopping',
}

function changing(status: SelfHostStatus | null): boolean {
  return status?.state === 'STARTING' || status?.state === 'STOPPING'
}

function messageOf(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

/**
 * The Self-hosted server's controls in its card (docs/plans/phase-9.md §5.5): Test (Ollama's version on the name, through
 * the backend), Start and Stop (disabled with the backend's reason when they can't be used), and the status, read again
 * every 5 seconds while the server is starting or stopping. The running line, the rate and the stopped-disk note are the
 * backend's strings, shown as they are. When a start or stop settles, the app's status is read again, so Corgi's badge
 * and banner follow.
 */
export default function SelfHostControls({ name }: { name: string }) {
  const { statusChanged } = useDataVersion()
  const [status, setStatus] = useState<SelfHostStatus | null>(null)
  const [readError, setReadError] = useState<string | null>(null)
  const [busy, setBusy] = useState<'test' | 'start' | 'stop' | null>(null)
  const [test, setTest] = useState<{ reachable: boolean; message: string } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const previous = useRef<SelfHostStatus | null>(null)

  const show = useCallback(
    (next: SelfHostStatus) => {
      if (changing(previous.current) && !changing(next)) {
        void statusChanged()
      }
      previous.current = next
      setStatus(next)
      setReadError(null)
    },
    [statusChanged],
  )

  const read = useCallback(async () => {
    try {
      show(await selfHost.status())
    } catch (e) {
      setReadError(messageOf(e))
    }
  }, [show])

  useEffect(() => {
    void read()
  }, [read])

  const polling = changing(status)
  useEffect(() => {
    if (!polling) {
      return
    }
    const timer = setInterval(() => void read(), POLL_MS)
    return () => clearInterval(timer)
  }, [polling, read])

  async function act(action: 'start' | 'stop') {
    setBusy(action)
    setError(null)
    try {
      show(await (action === 'start' ? selfHost.start() : selfHost.stop()))
    } catch (e) {
      setError(messageOf(e))
      void read()
    } finally {
      setBusy(null)
    }
  }

  async function runTest() {
    setBusy('test')
    setTest(null)
    try {
      setTest(await selfHost.test(name.trim()))
    } catch (e) {
      setTest({ reachable: false, message: messageOf(e) })
    } finally {
      setBusy(null)
    }
  }

  const nameOk = validServerName(name.trim())
  const canStart = !!status?.controls && status.state === 'STOPPED' && busy === null
  const canStop = !!status?.controls && (status.state === 'RUNNING' || status.state === 'STARTING') && busy === null
  const disabledWhy = status && !status.controls ? (status.reason ?? undefined) : undefined

  return (
    <div className="selfhost-controls">
      <div className="selfhost-buttons" role="group" aria-label="Server controls">
        <button
          type="button"
          className="button-secondary"
          disabled={!nameOk || busy !== null}
          title={nameOk ? undefined : 'Enter the server’s Tailscale name first.'}
          onClick={() => void runTest()}
        >
          {busy === 'test' ? 'Testing…' : 'Test'}
        </button>
        <button
          type="button"
          className="button-secondary"
          disabled={!canStart}
          title={disabledWhy}
          onClick={() => void act('start')}
        >
          {busy === 'start' ? 'Starting…' : 'Start'}
        </button>
        <button
          type="button"
          className="button-secondary"
          disabled={!canStop}
          title={disabledWhy}
          onClick={() => void act('stop')}
        >
          {busy === 'stop' ? 'Stopping…' : 'Stop'}
        </button>
      </div>
      {test && (
        <p className={test.reachable ? 'selfhost-test' : 'selfhost-test error'} role="status">
          {test.message}
        </p>
      )}
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      {readError && <p className="error">Couldn’t read the server: {readError}</p>}
      {!status && !readError && <p className="muted">Reading the server…</p>}
      {status && !status.controls && <p className="path-card-reason selfhost-reason">{status.reason}</p>}
      {status?.state && (
        <dl className="facts selfhost-facts" aria-label="Server status">
          <div className="facts-row">
            <dt>Server</dt>
            <dd role="status" aria-live="polite">
              {STATE_TEXT[status.state]}
              {status.progress && <span className="selfhost-progress">: {status.progress}…</span>}
            </dd>
          </div>
          {status.line && (
            <div className="facts-row">
              <dt>This session</dt>
              <dd>
                {status.line}
                {status.hourlyRate && (
                  <span className="muted">
                    {' '}
                    · {status.instanceType} at ${status.hourlyRate} an hour
                  </span>
                )}
              </dd>
            </div>
          )}
        </dl>
      )}
      {status?.note && <p className="selfhost-note">{status.note}</p>}
      {status?.storageNote && <p className="muted selfhost-storage">{status.storageNote}</p>}
    </div>
  )
}
