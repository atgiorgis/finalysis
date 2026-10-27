import { useEffect, useRef, type KeyboardEvent } from 'react'
import { NavLink, useSearchParams } from 'react-router-dom'
import { formatMonth } from '../format'
import { TABS } from '../tabs'
import { hasVault, useVault } from '../vault/VaultContext'
import { CloseIcon, LockIcon, LogoMark, TAB_ICONS } from './icons'
import { accountsLine, useMonth, useShellData } from './ShellData'

/** A tab's link, keeping the chosen month. */
function useTabHref() {
  const [params] = useSearchParams()
  const month = params.get('month')
  return (path: string) => (month ? `${path}?month=${encodeURIComponent(month)}` : path)
}

const FOCUSABLE = 'a[href], button:not([disabled])'

/**
 * The navigation at every width (step 7c): a menu that slides in from the left, with the seven tabs. It closes when a
 * tab is chosen, on ✕, on the backdrop and on Escape; while open, Tab stays inside it. The Layout returns focus to the
 * menu button when it closes.
 */
export default function Sidebar({ onClose }: { onClose: () => void }) {
  const href = useTabHref()
  const { status, accounts } = useShellData()
  const { month } = useMonth()
  const vault = useVault()
  const panel = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const current = panel.current?.querySelector<HTMLElement>('nav a[aria-current="page"]')
    const first = panel.current?.querySelector<HTMLElement>('nav a')
    ;(current ?? first)?.focus()
  }, [])

  useEffect(() => {
    const onKey = (e: globalThis.KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault()
        onClose()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

  /** Keeps Tab and Shift+Tab inside the menu. */
  const trapTab = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.key !== 'Tab' || !panel.current) {
      return
    }
    const items = [...panel.current.querySelectorAll<HTMLElement>(FOCUSABLE)]
    if (items.length === 0) {
      return
    }
    const first = items[0]
    const last = items[items.length - 1]
    const active = document.activeElement
    if (e.shiftKey && (active === first || !panel.current.contains(active))) {
      e.preventDefault()
      last.focus()
    } else if (!e.shiftKey && (active === last || !panel.current.contains(active))) {
      e.preventDefault()
      first.focus()
    }
  }

  return (
    <div className="sidebar-layer">
      {/* A pointer convenience; the keyboard closes with Escape or ✕. */}
      <div className="sidebar-backdrop" data-testid="sidebar-backdrop" onClick={onClose} aria-hidden="true" />
      <div
        ref={panel}
        className="sidebar"
        role="dialog"
        aria-modal="true"
        aria-label="Menu"
        tabIndex={-1}
        onKeyDown={trapTab}
      >
        <div className="sidebar-head">
          <span className="brand">
            <LogoMark />
            <span className="brand-name">Finalysis</span>
          </span>
          <button type="button" className="icon-button" aria-label="Close menu" onClick={onClose}>
            <CloseIcon />
          </button>
        </div>
        <nav aria-label="Main" className="sidebar-nav">
          {TABS.map((tab) => {
            const TabIcon = TAB_ICONS[tab.path]
            return (
              <NavLink key={tab.path} to={href(tab.path)} className="sidebar-link" onClick={onClose}>
                <TabIcon />
                {tab.label}
              </NavLink>
            )
          })}
        </nav>
        {hasVault(vault.status) && (
          <button
            type="button"
            className="button-secondary sidebar-lock"
            onClick={() => {
              onClose()
              void vault.lock()
            }}
          >
            <LockIcon />
            Lock now
          </button>
        )}
        {status && (
          <p className="sidebar-foot">
            <strong>{accountsLine(accounts.length)}</strong>
            {month && <span>Showing {formatMonth(month)}</span>}
          </p>
        )}
      </div>
    </div>
  )
}
