import { Fragment, useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { ApiProblem, onLoginRequired, onVaultLocked, reads, vault, type VaultStatus } from '../api'
import LoginScreen from './LoginScreen'
import RecoveryKeyScreen from './RecoveryKeyScreen'
import SetupScreen from './SetupScreen'
import UnlockScreen from './UnlockScreen'
import { VaultContext, type VaultControls } from './VaultContext'
import { BrokenScreen, BusyScreen, LockFailedScreen, NotReadyNotice, UnreachableScreen } from './VaultMessages'

/** While unlocked: how often the status is read, so an idle or wake lock clears the screen without a click. */
export const STATUS_POLL_MS = 30_000
/** The "user is here" signal: at most once a minute (docs/plans/phase-8.md §5.3). */
export const ACTIVITY_INTERVAL_MS = 60_000
/** While locking or unlocking elsewhere: how soon to look again. */
const BUSY_POLL_MS = 1_000

const ACTIVITY_EVENTS = ['pointerdown', 'pointermove', 'keydown', 'wheel', 'scroll', 'touchstart'] as const

/**
 * The vault in front of the whole app (docs/plans/phase-8.md §6). The app renders only while the vault is unlocked
 * (or off: the plain dev database). Any 423, a status read, or the Lock button switches to the unlock screen and
 * **unmounts the app**, which drops everything it held in memory: page data, chat turns, the shell's accounts. After
 * unlock the app mounts afresh and the user lands back on the route they were on.
 */
export default function VaultGate({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<VaultStatus | null>(null)
  const [unreachable, setUnreachable] = useState<string | null>(null)
  const [recoveryKey, setRecoveryKey] = useState<string | null>(null)
  /** The phone without a session (Phase 8 §9.3): the login screen, nothing of the app. */
  const [needsLogin, setNeedsLogin] = useState(false)
  const [session, setSession] = useState(0)
  const location = useLocation()
  const navigate = useNavigate()
  const here = useRef('')
  here.current = location.pathname + location.search
  const returnTo = useRef<string | null>(null)

  const refresh = useCallback(async (): Promise<VaultStatus | null> => {
    try {
      const read = (await reads.status()).vault
      setStatus(read)
      setUnreachable(null)
      return read
    } catch (e) {
      if (e instanceof ApiProblem && e.slug === 'login-required') {
        setNeedsLogin(true)
        return null
      }
      // Not answering: drop what the app shows. On the phone this is what a lock on the computer looks like (the
      // listener closes), so nothing may stay on screen.
      setStatus(null)
      setUnreachable((e as Error).message)
      return null
    }
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

  /** Locked from anywhere: remember the page once, close the app at once, then read which state it is. */
  const closed = useCallback(() => {
    if (returnTo.current === null) {
      returnTo.current = here.current
    }
    setStatus((current) => (current && current.state === 'UNLOCKED' ? { ...current, state: 'LOCKED' } : current))
    void refresh()
  }, [refresh])

  useEffect(() => onVaultLocked(closed), [closed])
  useEffect(
    () =>
      onLoginRequired(() => {
        if (returnTo.current === null) {
          returnTo.current = here.current
        }
        setNeedsLogin(true)
      }),
    [],
  )

  const open =
    !needsLogin &&
    status !== null &&
    recoveryKey === null &&
    (status.storage === 'PLAIN' || status.state === 'UNLOCKED')
  const watching = open && status?.storage === 'VAULT'

  useEffect(() => {
    if (!watching) {
      return
    }
    const timer = setInterval(() => {
      void refresh().then((read) => {
        if (read && read.state !== 'UNLOCKED') {
          closed()
        }
      })
    }, STATUS_POLL_MS)
    let last = Date.now()
    const onActivity = () => {
      const now = Date.now()
      if (now - last >= ACTIVITY_INTERVAL_MS) {
        last = now
        void vault.activity()
      }
    }
    ACTIVITY_EVENTS.forEach((type) => window.addEventListener(type, onActivity, { passive: true }))
    return () => {
      clearInterval(timer)
      ACTIVITY_EVENTS.forEach((type) => window.removeEventListener(type, onActivity))
    }
  }, [watching, refresh, closed])

  const busy = status?.state === 'LOCKING' || status?.state === 'UNLOCKING'
  useEffect(() => {
    if (!busy) {
      return
    }
    const timer = setTimeout(() => void refresh(), BUSY_POLL_MS)
    return () => clearTimeout(timer)
  }, [busy, status, refresh])

  /** After unlock or recovery: a fresh app, back on the page the user was on. */
  const unlocked = useCallback(async () => {
    setSession((s) => s + 1)
    await refresh()
    const to = returnTo.current
    returnTo.current = null
    if (to && to !== here.current) {
      navigate(to, { replace: true })
    }
  }, [refresh, navigate])

  const controls = useMemo<VaultControls>(
    () => ({
      status,
      lock: async () => {
        returnTo.current = here.current
        try {
          await vault.lock()
        } finally {
          setStatus((current) => (current ? { ...current, state: 'LOCKED' } : current))
          await refresh()
        }
      },
      refresh: async () => {
        await refresh()
      },
    }),
    [status, refresh],
  )

  /** Setup succeeded: show the key once; the app opens only after the user confirms it. */
  function onCreated(key: string) {
    setRecoveryKey(key)
    void refresh()
  }

  if (needsLogin) {
    return (
      <LoginScreen
        onLoggedIn={() => {
          setNeedsLogin(false)
          void unlocked()
        }}
      />
    )
  }

  if (status === null) {
    return unreachable ? (
      <UnreachableScreen error={unreachable} onRetry={() => void refresh()} />
    ) : (
      <p className="vault-connecting">Connecting to Finalysis…</p>
    )
  }

  if (recoveryKey !== null) {
    return (
      <RecoveryKeyScreen
        recoveryKey={recoveryKey}
        onContinue={() => {
          setRecoveryKey(null)
          setSession((s) => s + 1)
        }}
      />
    )
  }

  if (open) {
    return (
      <VaultContext.Provider value={controls}>
        <Fragment key={session}>{children}</Fragment>
      </VaultContext.Provider>
    )
  }

  const notReady = status.state === 'NOT_READY' ? <NotReadyNotice status={status} /> : undefined
  switch (status.state) {
    case 'NOT_SET_UP':
      return <SetupScreen onCreated={onCreated} />
    case 'NOT_READY':
      return status.setUp ? (
        <UnlockScreen notice={notReady} onUnlocked={() => void unlocked()} />
      ) : (
        <SetupScreen notice={notReady} onCreated={onCreated} />
      )
    case 'BROKEN':
      return <BrokenScreen status={status} onRetry={() => void refresh()} />
    case 'LOCK_FAILED':
      return <LockFailedScreen status={status} onDone={() => void refresh()} />
    case 'LOCKING':
    case 'UNLOCKING':
      return <BusyScreen status={status} />
    default:
      return <UnlockScreen onUnlocked={() => void unlocked()} />
  }
}
