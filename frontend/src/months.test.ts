import { expect, test } from 'vitest'
import { addMonths, isYearMonth, monthDays, monthsBetween } from './months'

test('addMonths crosses years both ways', () => {
  expect(addMonths('2026-10', 1)).toBe('2026-11')
  expect(addMonths('2026-12', 1)).toBe('2027-01')
  expect(addMonths('2026-01', -1)).toBe('2025-12')
  expect(addMonths('2026-10', -23)).toBe('2024-11')
  expect(addMonths('2026-10', 0)).toBe('2026-10')
})

test('monthsBetween lists both ends and is empty when reversed', () => {
  expect(monthsBetween('2025-11', '2026-02')).toEqual(['2025-11', '2025-12', '2026-01', '2026-02'])
  expect(monthsBetween('2026-03', '2026-03')).toEqual(['2026-03'])
  expect(monthsBetween('2026-04', '2026-03')).toEqual([])
})

test('monthDays knows month lengths and leap years', () => {
  expect(monthDays('2026-10')).toEqual({ first: '2026-10-01', last: '2026-10-31' })
  expect(monthDays('2026-02')).toEqual({ first: '2026-02-01', last: '2026-02-28' })
  expect(monthDays('2028-02')).toEqual({ first: '2028-02-01', last: '2028-02-29' })
  expect(monthDays('2026-04').last).toBe('2026-04-30')
})

test('isYearMonth accepts real months only', () => {
  expect(isYearMonth('2026-10')).toBe(true)
  expect(isYearMonth('2026-13')).toBe(false)
  expect(isYearMonth('2026-00')).toBe(false)
  expect(isYearMonth('Oct')).toBe(false)
  expect(isYearMonth(null)).toBe(false)
})
