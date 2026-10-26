/** The product's tabs, in navigation order (docs/plans/phase-7.md §1.1, §4.4). */
export const TABS = [
  { path: '/overview', label: 'Overview' },
  { path: '/transactions', label: 'Transactions' },
  { path: '/import', label: 'Import' },
  { path: '/alerts', label: 'Alerts' },
  { path: '/recurring', label: 'Recurring' },
  { path: '/chat', label: 'Chat' },
  { path: '/goals', label: 'Goals' },
  { path: '/settings', label: 'Settings' },
] as const
