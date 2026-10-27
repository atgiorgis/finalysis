import { forwardRef } from 'react'
import { useLocation } from 'react-router-dom'
import { TABS } from '../tabs'
import { useTheme } from '../theme'
import { hasVault, useVault } from '../vault/VaultContext'
import { LockIcon, LogoMark, MenuIcon, MoonIcon, SunIcon } from './icons'
import MonthPicker from './MonthPicker'

/** The tab a path belongs to, for the "/ Overview" crumb; none for a page outside the tabs (`/drill`). */
function tabLabel(pathname: string): string | null {
  return TABS.find((tab) => pathname === tab.path || pathname.startsWith(`${tab.path}/`))?.label ?? null
}

/** Dark or light, remembered per browser (theme.ts). */
function ThemeSwitch() {
  const [theme, setTheme] = useTheme()
  const dark = theme === 'dark'
  return (
    <button
      type="button"
      role="switch"
      aria-checked={dark}
      aria-label="Dark theme"
      className="theme-switch"
      onClick={() => setTheme(dark ? 'light' : 'dark')}
    >
      <span className="theme-switch-knob">{dark ? <MoonIcon /> : <SunIcon />}</span>
    </button>
  )
}

/** Locks the vault now (docs/plans/phase-8.md §6): an icon with its label; on a phone the label is for screen readers. */
function LockButton() {
  const { status, lock } = useVault()
  if (!hasVault(status)) {
    return null
  }
  return (
    <button type="button" className="lock-button" onClick={() => void lock()}>
      <LockIcon />
      <span className="lock-label">Lock</span>
    </button>
  )
}

/**
 * The bar at the top of every page (step 7c): the menu button, the logo, the tab shown, the month picker, the theme
 * switch and, with the vault, the Lock button. On a phone the month picker takes a second row.
 */
const TopBar = forwardRef<HTMLButtonElement, { menuOpen: boolean; onOpenMenu: () => void }>(function TopBar(
  { menuOpen, onOpenMenu },
  menuButton,
) {
  const { pathname } = useLocation()
  const label = tabLabel(pathname)
  return (
    <header className="top-bar">
      <button
        ref={menuButton}
        type="button"
        className="icon-button"
        aria-label="Open menu"
        aria-expanded={menuOpen}
        aria-haspopup="dialog"
        onClick={onOpenMenu}
      >
        <MenuIcon />
      </button>
      <span className="brand">
        <LogoMark />
        <span className="brand-name">Finalysis</span>
      </span>
      {label && (
        <span className="top-bar-crumb" aria-current="page">
          / {label}
        </span>
      )}
      <div className="top-bar-month">
        <MonthPicker />
      </div>
      <ThemeSwitch />
      <LockButton />
    </header>
  )
})

export default TopBar
