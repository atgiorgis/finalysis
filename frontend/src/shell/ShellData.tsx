import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { useSearchParams } from 'react-router-dom'
import { reads, type Account, type Coverage, type CoverageResponse, type StatusResponse, type YearMonth } from '../api'
import { addMonths, isYearMonth } from '../months'

/** The most months the picker offers: the analytics endpoints' range limit. */
const MAX_MONTHS = 24

export interface PickerMonth {
  month: YearMonth
  coverage: Coverage
}

export interface ShellData {
  status: StatusResponse | null
  coverage: CoverageResponse | null
  accounts: Account[]
  /** Months with data, oldest first, with their coverage; empty while loading or without statements. */
  months: PickerMonth[]
  loading: boolean
  error: string | null
}

const EMPTY: ShellData = { status: null, coverage: null, accounts: [], months: [], loading: true, error: null }

const ShellContext = createContext<ShellData>(EMPTY)

/** The first month any account has data, from coverage: the earliest `firstDay`. */
function firstMonth(coverage: CoverageResponse): YearMonth | null {
  const months = coverage.accounts
    .map((account) => account.firstDay?.slice(0, 7))
    .filter((month): month is string => !!month)
    .sort()
  return months[0] ?? null
}

/** Loads what every tab shares once: status (the AI mode), coverage, accounts and the picker's months. */
export function ShellDataProvider({ children }: { children: ReactNode }) {
  const [data, setData] = useState<ShellData>(EMPTY)

  useEffect(() => {
    let live = true
    async function load() {
      const [status, coverage, accounts] = await Promise.all([reads.status(), reads.coverage(), reads.accounts()])
      let months: PickerMonth[] = []
      const latest = coverage.latestMonth
      const first = firstMonth(coverage)
      if (latest && first) {
        const earliestOffered = addMonths(latest, -(MAX_MONTHS - 1))
        const from = first > earliestOffered ? first : earliestOffered
        months = (await reads.months({ from, to: latest })).months
          .filter((m) => m.coverage !== 'NONE')
          .map((m) => ({ month: m.month, coverage: m.coverage }))
      }
      if (live) {
        setData({ status, coverage, accounts, months, loading: false, error: null })
      }
    }
    load().catch((e: Error) => {
      if (live) {
        setData({ ...EMPTY, loading: false, error: e.message })
      }
    })
    return () => {
      live = false
    }
  }, [])

  return <ShellContext.Provider value={data}>{children}</ShellContext.Provider>
}

export function useShellData(): ShellData {
  return useContext(ShellContext)
}

/**
 * The month every tab shows: `?month=` when it is a month with data, else the latest complete month (the latest month
 * when none is complete), as the trends and alerts default. A later partial month stays in the picker, marked.
 * Changing it keeps the other query parameters.
 */
export function useMonth(): { month: YearMonth | null; setMonth: (month: YearMonth) => void } {
  const { months, coverage } = useShellData()
  const [params, setParams] = useSearchParams()
  const asked = params.get('month')
  const month = useMemo(() => {
    if (isYearMonth(asked) && months.some((m) => m.month === asked)) {
      return asked
    }
    return coverage?.latestCompleteMonth ?? coverage?.latestMonth ?? null
  }, [asked, months, coverage])
  const setMonth = useCallback(
    (next: YearMonth) => {
      setParams((current) => {
        const updated = new URLSearchParams(current)
        updated.set('month', next)
        return updated
      })
    },
    [setParams],
  )
  return { month, setMonth }
}
