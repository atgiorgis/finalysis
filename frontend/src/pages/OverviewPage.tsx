import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { reads, type DashboardResponse, type DashboardSlice } from '../api'
import CategoryDetails from '../components/CategoryDetails'
import CashFlowChart from '../components/charts/CashFlowChart'
import SpendingDonut from '../components/charts/SpendingDonut'
import DataQualityTile from '../components/DataQualityTile'
import KpiTiles from '../components/KpiTiles'
import { AlertsSummary, AtAGlance, RecurringSummary } from '../components/OverviewSummaries'
import { detailParam, parseDetail, type Detail } from '../detailParam'
import { formatMonth } from '../format'
import { useDataVersion, useMonth, useShellData } from '../shell/ShellData'

type Load = { state: 'loading' } | { state: 'error'; message: string } | { state: 'done'; dashboard: DashboardResponse }

/**
 * The Overview (UI-01, docs/plans/phase-7.md §2.2): one call, `GET /api/dashboard`, for the month the picker shows.
 * Every figure is the API's.
 */
export default function OverviewPage() {
  const { accounts } = useShellData()
  const { month, setMonth } = useMonth()
  const [params, setParams] = useSearchParams()
  const detail = parseDetail(params.get('detail'))
  const { version } = useDataVersion()
  const [load, setLoad] = useState<Load>({ state: 'loading' })
  const setDetail = (next: Detail | null) =>
    setParams((current) => {
      const updated = new URLSearchParams(current)
      if (next) {
        updated.set('detail', detailParam(next))
      } else {
        updated.delete('detail')
      }
      return updated
    })

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
  }, [month, version])

  return (
    <section className="page overview">
      <h1>Overview</h1>
      {load.state === 'loading' && <p className="muted">Loading…</p>}
      {load.state === 'error' && <p className="error">Couldn’t load the overview: {load.message}</p>}
      {load.state === 'done' && (
        <OverviewBody
          dashboard={load.dashboard}
          onSelectMonth={setMonth}
          accounts={accounts}
          detail={detail}
          onDetail={setDetail}
        />
      )}
    </section>
  )
}

/** The panel a slice opens. */
function sliceDetail(slice: DashboardSlice): Detail {
  switch (slice.kind) {
    case 'CATEGORY':
      return { kind: 'category', categoryId: slice.category?.id ?? -1 }
    case 'OTHER':
      return { kind: 'other' }
    case 'UNCATEGORIZED':
      return { kind: 'uncategorized' }
  }
}

function sameDetail(a: Detail | null, b: Detail): boolean {
  return !!a && detailParam(a) === detailParam(b)
}

function OverviewBody({
  dashboard,
  onSelectMonth,
  accounts,
  detail,
  onDetail,
}: {
  dashboard: DashboardResponse
  onSelectMonth: (month: string) => void
  accounts: ReturnType<typeof useShellData>['accounts']
  detail: Detail | null
  onDetail: (detail: Detail | null) => void
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
          <SpendingDonut
            spending={dashboard.spendingSlices.spending}
            slices={dashboard.spendingSlices.rows}
            selected={dashboard.spendingSlices.rows.find((s) => sameDetail(detail, sliceDetail(s))) ?? null}
            onSelect={(slice) => onDetail(sliceDetail(slice))}
          />
        )}
        <p className="muted">Choose a category, Other or Uncategorized to see its trend and transactions.</p>
      </section>
      {detail && (
        <div className="overview-details">
          <CategoryDetails dashboard={dashboard} detail={detail} onOpen={onDetail} onClose={() => onDetail(null)} />
        </div>
      )}
      <AtAGlance dashboard={dashboard} onOpenIncome={() => onDetail({ kind: 'income' })} />
      <AlertsSummary dashboard={dashboard} />
      <RecurringSummary dashboard={dashboard} />
    </div>
  )
}
