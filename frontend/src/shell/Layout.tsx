import { Outlet } from 'react-router-dom'
import { formatMonth } from '../format'
import CloudAiBanner from './CloudAiBanner'
import MonthPicker from './MonthPicker'
import { BottomNav, NavTabs } from './Navigation'
import { useMonth, useShellData } from './ShellData'
import TxnSheet from './TxnSheet'

function accountsLine(count: number): string {
  return count === 1 ? '1 account' : `${count} accounts`
}

/**
 * The frame of every tab: the cloud-AI banner, the header with the tabs and the month picker, the bottom bar, and the
 * transaction sheet any page can open (`?txn=`).
 */
export default function Layout() {
  const { status, accounts, loading, error } = useShellData()
  const { month } = useMonth()

  return (
    <div className="app">
      {status && <CloudAiBanner ai={status.ai} />}
      <header className="app-header">
        <div className="app-header-row">
          <span className="app-name">Finalysis</span>
          <MonthPicker />
        </div>
        {status && (
          <p className="app-context">
            {accountsLine(accounts.length)}
            {month ? ` · ${formatMonth(month)}` : ''}
          </p>
        )}
        <NavTabs />
      </header>
      <main className="app-main">
        {error && <p className="error">Backend unreachable: {error}</p>}
        {!error && loading && <p>Connecting to backend…</p>}
        {!error && !loading && <Outlet />}
      </main>
      <BottomNav />
      {!error && !loading && <TxnSheet />}
    </div>
  )
}
