import { Navigate, Route, Routes } from 'react-router-dom'
import App from './App'

/** The product's tabs, in navigation order (docs/plans/phase-7.md §1.1, §4.4). */
export const TABS = [
  { path: '/overview', label: 'Overview', step: '2' },
  { path: '/transactions', label: 'Transactions', step: '4' },
  { path: '/alerts', label: 'Alerts', step: '5' },
  { path: '/recurring', label: 'Recurring', step: '5' },
  { path: '/chat', label: 'Chat', step: '6' },
  { path: '/goals', label: 'Goals', step: '7b' },
  { path: '/settings', label: 'Settings', step: '1' },
] as const

/** Stands in for a tab until its step builds it. */
function PlaceholderPage({ label, step }: { label: string; step: string }) {
  return (
    <main>
      <h1>{label}</h1>
      <p>Built in Phase 7 step {step}.</p>
    </main>
  )
}

/**
 * Every route of the app. Until step 1 builds the shell, `/` is still the Phase 5 status page with the Phase 6 chat
 * test page, and each tab is a placeholder.
 */
export default function AppRoutes() {
  return (
    <Routes>
      <Route path="/" element={<App />} />
      {TABS.map((tab) => (
        <Route key={tab.path} path={`${tab.path}/*`} element={<PlaceholderPage label={tab.label} step={tab.step} />} />
      ))}
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
