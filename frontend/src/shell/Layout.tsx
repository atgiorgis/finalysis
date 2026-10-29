import { useCallback, useEffect, useRef, useState } from 'react'
import { Outlet } from 'react-router-dom'
import CorgiBanner from './CorgiBanner'
import { useShellData } from './ShellData'
import Sidebar from './Sidebar'
import TopBar from './TopBar'
import TxnSheet from './TxnSheet'

/**
 * The frame of every tab (step 7c): the top bar, Corgi's strip under it (its path, Phase 9), the menu, and the transaction sheet any
 * page can open (`?txn=`). One pattern at every width.
 */
export default function Layout() {
  const { status, loading, error } = useShellData()
  const [menuOpen, setMenuOpen] = useState(false)
  const menuButton = useRef<HTMLButtonElement>(null)

  const closeMenu = useCallback(() => setMenuOpen(false), [])

  // When the menu closes, by any route, focus goes back to the button that opened it.
  const wasOpen = useRef(false)
  useEffect(() => {
    if (wasOpen.current && !menuOpen) {
      menuButton.current?.focus()
    }
    wasOpen.current = menuOpen
  }, [menuOpen])

  return (
    <div className="app">
      <TopBar ref={menuButton} menuOpen={menuOpen} onOpenMenu={() => setMenuOpen(true)} />
      {status && <CorgiBanner ai={status.ai} />}
      {menuOpen && <Sidebar onClose={closeMenu} />}
      <main className="app-main">
        {error && <p className="error">Backend unreachable: {error}</p>}
        {!error && loading && <p>Connecting to backend…</p>}
        {!error && !loading && <Outlet />}
      </main>
      {!error && !loading && <TxnSheet />}
    </div>
  )
}
