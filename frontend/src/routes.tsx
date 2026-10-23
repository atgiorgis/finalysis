import { Navigate, Route, Routes, useParams, useSearchParams } from 'react-router-dom'
import AlertsPage from './pages/AlertsPage'
import ChatPage from './pages/ChatPage'
import DrillPage from './pages/DrillPage'
import OverviewPage from './pages/OverviewPage'
import PlaceholderPage from './pages/PlaceholderPage'
import RecurringPage from './pages/RecurringPage'
import SettingsPage from './pages/SettingsPage'
import TransactionsPage from './pages/TransactionsPage'
import Layout from './shell/Layout'
import { ShellDataProvider } from './shell/ShellData'
import { TABS } from './tabs'

export { TABS }

/** `/transactions/:id`: the Transactions tab with that transaction's sheet open, keeping the month. */
function TransactionLink() {
  const { id } = useParams()
  const [params] = useSearchParams()
  const next = new URLSearchParams(params)
  if (id && /^\d{1,18}$/.test(id)) {
    next.set('txn', id)
  }
  return <Navigate to={`/transactions?${next}`} replace />
}

/** Tabs a later step builds; each is a placeholder until then. */
const BUILT = ['/overview', '/transactions', '/alerts', '/recurring', '/chat', '/settings']
const PLACEHOLDERS = TABS.filter((tab) => !BUILT.includes(tab.path))

/**
 * Every route of the app, inside the shell. `/` opens the Overview.
 */
export default function AppRoutes() {
  return (
    <ShellDataProvider>
      <Routes>
        <Route element={<Layout />}>
          <Route index element={<Navigate to="/overview" replace />} />
          <Route path="/transactions" element={<TransactionsPage />} />
          <Route path="/transactions/:id" element={<TransactionLink />} />
          <Route path="/drill" element={<DrillPage />} />
          <Route path="/alerts/*" element={<AlertsPage />} />
          <Route path="/recurring/*" element={<RecurringPage />} />
          {PLACEHOLDERS.map((tab) => (
            <Route
              key={tab.path}
              path={`${tab.path}/*`}
              element={<PlaceholderPage label={tab.label} step={tab.step} />}
            />
          ))}
          <Route path="/overview/*" element={<OverviewPage />} />
          <Route path="/chat" element={<ChatPage />} />
          <Route path="/chat/:id" element={<ChatPage />} />
          <Route path="/settings" element={<SettingsPage />} />
          <Route path="*" element={<Navigate to="/overview" replace />} />
        </Route>
      </Routes>
    </ShellDataProvider>
  )
}
