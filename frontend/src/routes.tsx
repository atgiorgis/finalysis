import { Navigate, Route, Routes } from 'react-router-dom'
import ChatPage from './ChatPage'
import OverviewPage from './pages/OverviewPage'
import PlaceholderPage from './pages/PlaceholderPage'
import SettingsPage from './pages/SettingsPage'
import Layout from './shell/Layout'
import { ShellDataProvider } from './shell/ShellData'
import { TABS } from './tabs'

export { TABS }

/** Tabs a later step builds; each is a placeholder until then. */
const PLACEHOLDERS = TABS.filter((tab) => !['/overview', '/chat', '/settings'].includes(tab.path))

/**
 * Every route of the app, inside the shell. `/` opens the Overview. The Phase 6 chat page stays on the Chat tab until
 * step 6 replaces it.
 */
export default function AppRoutes() {
  return (
    <ShellDataProvider>
      <Routes>
        <Route element={<Layout />}>
          <Route index element={<Navigate to="/overview" replace />} />
          {PLACEHOLDERS.map((tab) => (
            <Route
              key={tab.path}
              path={`${tab.path}/*`}
              element={<PlaceholderPage label={tab.label} step={tab.step} />}
            />
          ))}
          <Route path="/overview/*" element={<OverviewPage />} />
          <Route path="/chat/*" element={<ChatPage />} />
          <Route path="/settings" element={<SettingsPage />} />
          <Route path="*" element={<Navigate to="/overview" replace />} />
        </Route>
      </Routes>
    </ShellDataProvider>
  )
}
