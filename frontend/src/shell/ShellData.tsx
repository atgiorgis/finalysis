import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { useSearchParams } from 'react-router-dom'
import {
  reads,
  type Account,
  type Category,
  type Coverage,
  type CoverageResponse,
  type StatusResponse,
  type YearMonth,
} from '../api'
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
  /** Every category, for the filters and the category picker; empty if the read failed. */
  categories: Category[]
  /** Months with data, oldest first, with their coverage; empty while loading or without statements. */
  months: PickerMonth[]
  loading: boolean
  error: string | null
}

const EMPTY: ShellData = {
  status: null,
  coverage: null,
  accounts: [],
  categories: [],
  months: [],
  loading: true,
  error: null,
}

const ShellContext = createContext<ShellData>(EMPTY)

/** What changed: `shell` also reloads what the shell shares (accounts, coverage, the picker's months). */
export interface DataChange {
  shell?: boolean
}

/**
 * Bumped after every write (a correction, a dismissal, an import), so the pages that read the changed data read it
 * again (docs/plans/phase-7.md §2.4). Pages put `version` in their reads' dependencies. A write that adds an account or
 * a statement, or removes one, asks for `{ shell: true }`.
 */
const VersionContext = createContext<{ version: number; dataChanged: (change?: DataChange) => void }>({
  version: 0,
  dataChanged: () => {},
})

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
  const [version, setVersion] = useState(0)
  const [shellVersion, setShellVersion] = useState(0)

  useEffect(() => {
    let live = true
    async function load() {
      const [status, coverage, accounts, categories] = await Promise.all([
        reads.status(),
        reads.coverage(),
        reads.accounts(),
        reads.categories().catch(() => [] as Category[]),
      ])
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
        setData({ status, coverage, accounts, categories, months, loading: false, error: null })
      }
    }
    load().catch((e: Error) => {
      // A reload that fails keeps what the shell had; only the first load shows the error.
      if (live && shellVersion === 0) {
        setData({ ...EMPTY, loading: false, error: e.message })
      }
    })
    return () => {
      live = false
    }
  }, [shellVersion])

  const versionValue = useMemo(
    () => ({
      version,
      dataChanged: (change: DataChange = {}) => {
        setVersion((v) => v + 1)
        if (change.shell) {
          setShellVersion((v) => v + 1)
        }
      },
    }),
    [version],
  )

  return (
    <ShellContext.Provider value={data}>
      <VersionContext.Provider value={versionValue}>{children}</VersionContext.Provider>
    </ShellContext.Provider>
  )
}

/** The data version and the call that bumps it after a write. */
export function useDataVersion(): { version: number; dataChanged: (change?: DataChange) => void } {
  return useContext(VersionContext)
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

/** "1 account", "4 accounts". */
export function accountsLine(count: number): string {
  return count === 1 ? '1 account' : `${count} accounts`
}
