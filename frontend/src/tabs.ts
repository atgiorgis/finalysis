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
