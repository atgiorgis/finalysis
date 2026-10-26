import { expect, test } from 'vitest'
import type { DashboardResponse } from '../api'
import { fixtures } from '../test/fixtures'
import { leadSentence } from './leadSentence'

const SEPTEMBER = fixtures.dashboardCompleteMonth as DashboardResponse

/** September with its totals and last-month change replaced. */
function september(
  totals: Partial<NonNullable<DashboardResponse['totals']>>,
  vs: Partial<NonNullable<DashboardResponse['vsLastMonth']>> | null = {},
): DashboardResponse {
  return {
    ...SEPTEMBER,
    totals: { ...SEPTEMBER.totals!, ...totals },
    vsLastMonth: vs === null ? null : { ...SEPTEMBER.vsLastMonth!, ...vs },
  }
}

function text(dashboard: DashboardResponse): string | null {
  return leadSentence(dashboard)?.map((part) => part.text).join('') ?? null
}

/**
 * Every figure in the sentence, as its digits: `$1,351.17` → `1351.17`. The test below holds each to an API string, so
 * the sentence can only repeat what the API returned.
 */
function figures(sentence: string): string[] {
  return (sentence.match(/\d[\d,]*(?:\.\d+)?/g) ?? []).map((f) => f.replace(/,/g, ''))
}

test('the sentence from the September fixture', () => {
  expect(text(SEPTEMBER)).toBe(
    'You kept $1,351.17 of what came in. That’s a 27.6% savings rate, up 18.9 points from August.',
  )
})

test('the figures are marked: the net, the rate and the points', () => {
  expect(leadSentence(SEPTEMBER)?.filter((p) => p.figure)).toEqual([
    { text: '$1,351.17', figure: 'net' },
    { text: '27.6%', figure: 'plain' },
    { text: '18.9 points', figure: 'plain' },
  ])
})

test('without last month, the sentence ends after the savings rate', () => {
  expect(text(september({}, null))).toBe('You kept $1,351.17 of what came in. That’s a 27.6% savings rate.')
})

test('without a change in points, the sentence ends after the savings rate too', () => {
  expect(text(september({}, { savingsRatePoints: null }))).toBe(
    'You kept $1,351.17 of what came in. That’s a 27.6% savings rate.',
  )
})

test('a lower rate reads "down"; an unchanged one says so', () => {
  expect(text(september({}, { savingsRatePoints: '-4.2' }))).toBe(
    'You kept $1,351.17 of what came in. That’s a 27.6% savings rate, down 4.2 points from August.',
  )
  expect(text(september({}, { savingsRatePoints: '0.0' }))).toBe(
    'You kept $1,351.17 of what came in. That’s a 27.6% savings rate, unchanged from August.',
  )
})

test('a negative net: "You spent … more than came in", the minus dropped from the API’s string', () => {
  expect(text(september({ net: '-212.40', savingsRatePercent: '-6.0' }, { savingsRatePoints: '-34.1' }))).toBe(
    'You spent $212.40 more than came in. That’s a −6.0% savings rate, down 34.1 points from August.',
  )
})

test('no income (no savings rate): no sentence, the KPI card says so', () => {
  expect(leadSentence(september({ savingsRatePercent: null }))).toBeNull()
})

test('a partial month: no sentence, the page shows the partial-month note instead', () => {
  expect(leadSentence(fixtures.dashboardLatest as DashboardResponse)).toBeNull()
  expect(leadSentence(september({ coverage: 'PARTIAL' }))).toBeNull()
})

test('no month: no sentence', () => {
  expect(leadSentence({ ...SEPTEMBER, month: null, totals: null })).toBeNull()
})

test('every figure in the sentence is one the API returned, never one computed here', () => {
  const cases: DashboardResponse[] = [
    SEPTEMBER,
    september({}, null),
    september({}, { savingsRatePoints: '-4.2' }),
    september({ net: '-212.40', savingsRatePercent: '-6.0' }, { savingsRatePoints: '-34.1' }),
    september({ net: '12345.60', savingsRatePercent: '61.3' }, { savingsRatePoints: '102.5' }),
  ]
  for (const dashboard of cases) {
    const returned = [dashboard.totals!.net, dashboard.totals!.savingsRatePercent, dashboard.vsLastMonth?.savingsRatePoints]
      .filter((v): v is string => !!v)
      .map((v) => v.replace(/^-/, ''))
    const shown = figures(text(dashboard)!)
    expect(shown.length).toBeGreaterThan(0)
    for (const figure of shown) {
      expect(returned).toContain(figure)
    }
  }
})
