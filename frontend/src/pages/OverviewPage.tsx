import { useEffect, useState } from 'react'
import { reads, type DashboardResponse } from '../api'
import CashFlowChart from '../components/charts/CashFlowChart'
import SpendingDonut from '../components/charts/SpendingDonut'
import DataQualityTile from '../components/DataQualityTile'
import KpiTiles from '../components/KpiTiles'
import { AlertsSummary, AtAGlance, RecurringSummary } from '../components/OverviewSummaries'
import { formatMonth } from '../format'
import { useMonth, useShellData } from '../shell/ShellData'

type Load = { state: 'loading' } | { state: 'error'; message: string } | { state: 'done'; dashboard: DashboardResponse }

/**
 * The Overview (UI-01, docs/plans/phase-7.md §2.2): one call, `GET /api/dashboard`, for the month the picker shows.
 * Every figure is the API's.
 */
export default function OverviewPage() {
  const { accounts } = useShellData()
  const { month, setMonth } = useMonth()
  const [load, setLoad] = useState<Load>({ state: 'loading' })

  useEffect(() => {
    let live = true
    setLoad({ state: 'loading' })
    reads
      .dashboard(month)
      .then((dashboard) => live && setLoad({ state: 'done', dashboard }))
      .catch((e: Error) => live && setLoad({ state: 'error', message: e.message }))
    return () => {
      live = false
    }
  }, [month])

  return (
    <section className="page overview">
      <h1>Overview</h1>
      {load.state === 'loading' && <p className="muted">Loading…</p>}
      {load.state === 'error' && <p className="error">Couldn’t load the overview: {load.message}</p>}
      {load.state === 'done' && <OverviewBody dashboard={load.dashboard} onSelectMonth={setMonth} accounts={accounts} />}
    </section>
  )
}

function OverviewBody({
  dashboard,
  onSelectMonth,
  accounts,
}: {
  dashboard: DashboardResponse
  onSelectMonth: (month: string) => void
  accounts: ReturnType<typeof useShellData>['accounts']
}) {
  if (!dashboard.month || !dashboard.totals) {
    return (
      <p className="card">
        No statements yet. Import statements for your accounts, and the overview fills in from them.
      </p>
    )
  }
  const month = dashboard.month
  return (
    <div className="overview-grid">
      <div className="overview-kpis">
        <KpiTiles dashboard={dashboard} />
        <DataQualityTile dashboard={dashboard} accounts={accounts} />
      </div>
      <section className="card overview-cash-flow" aria-labelledby="cash-flow-title">
        <h2 id="cash-flow-title">Cash flow by month</h2>
        <CashFlowChart months={dashboard.cashFlow} selected={month} onSelect={onSelectMonth} />
      </section>
      <section className="card overview-donut" aria-labelledby="donut-title">
        <h2 id="donut-title">Where the money went · {formatMonth(month)}</h2>
        {dashboard.spendingSlices.rows.length === 0 ? (
          <p>No spending this month.</p>
        ) : (
          <SpendingDonut spending={dashboard.spendingSlices.spending} slices={dashboard.spendingSlices.rows} />
        )}
      </section>
      <AtAGlance dashboard={dashboard} />
      <AlertsSummary dashboard={dashboard} />
      <RecurringSummary dashboard={dashboard} />
    </div>
  )
}
