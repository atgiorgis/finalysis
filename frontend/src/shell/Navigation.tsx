import { useState } from 'react'
import { NavLink, useSearchParams } from 'react-router-dom'
import { TABS } from '../tabs'

/** A tab's link, keeping the chosen month. */
function useTabHref() {
  const [params] = useSearchParams()
  const month = params.get('month')
  return (path: string) => (month ? `${path}?month=${encodeURIComponent(month)}` : path)
}

/** The tabs across the header, on desktop. */
export function NavTabs() {
  const href = useTabHref()
  return (
    <nav aria-label="Main" className="nav-tabs">
      {TABS.map((tab) => (
        <NavLink key={tab.path} to={href(tab.path)} className="nav-tab">
          {tab.label}
        </NavLink>
      ))}
    </nav>
  )
}

const PHONE_TABS = ['/overview', '/transactions', '/chat']

/**
 * The bottom bar, on a phone: three tabs and More for the rest (docs/plans/phase-7.md §7.1). Step 7 finishes the phone
 * layout; this is its structure.
 */
export function BottomNav() {
  const href = useTabHref()
  const [moreOpen, setMoreOpen] = useState(false)
  const main = TABS.filter((tab) => PHONE_TABS.includes(tab.path))
  const more = TABS.filter((tab) => !PHONE_TABS.includes(tab.path))

  return (
    <nav aria-label="Main (phone)" className="bottom-nav">
      {moreOpen && (
        <ul className="bottom-nav-more" id="bottom-nav-more">
          {more.map((tab) => (
            <li key={tab.path}>
              <NavLink to={href(tab.path)} onClick={() => setMoreOpen(false)}>
                {tab.label}
              </NavLink>
            </li>
          ))}
        </ul>
      )}
      {main.map((tab) => (
        <NavLink key={tab.path} to={href(tab.path)} className="bottom-nav-tab" onClick={() => setMoreOpen(false)}>
          {tab.label}
        </NavLink>
      ))}
      <button
        type="button"
        className="bottom-nav-tab"
        aria-expanded={moreOpen}
        aria-controls="bottom-nav-more"
        onClick={() => setMoreOpen((open) => !open)}
      >
        More
      </button>
    </nav>
  )
}
