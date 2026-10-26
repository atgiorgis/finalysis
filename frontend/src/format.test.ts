import { describe, expect, test } from 'vitest'
import { formatDate, formatMoney, formatMonth, formatPercent, formatSpent } from './format'

const MINUS = '−'

describe('formatMoney', () => {
  test.each([
    ['-1850.00', `${MINUS}$1,850.00`],
    ['1234567.89', '$1,234,567.89'],
    ['0.00', '$0.00'],
    ['-0.05', `${MINUS}$0.05`],
    ['999.99', '$999.99'],
    ['1000.00', '$1,000.00'],
    ['0.214300', '$0.214300'],
    ['12', '$12'],
  ])('%s → %s', (value, shown) => {
    expect(formatMoney(value)).toBe(shown)
  })

  test('magnitude drops the sign', () => {
    expect(formatMoney('-3412.20', { sign: 'magnitude' })).toBe('$3,412.20')
    expect(formatMoney('3412.20', { sign: 'magnitude' })).toBe('$3,412.20')
  })

  test('change adds a plus to positive values, never to zero', () => {
    expect(formatMoney('12.30', { sign: 'change' })).toBe('+$12.30')
    expect(formatMoney('-12.30', { sign: 'change' })).toBe(`${MINUS}$12.30`)
    expect(formatMoney('0.00', { sign: 'change' })).toBe('$0.00')
  })

  test('a negative zero shows no sign', () => {
    expect(formatMoney('-0.00')).toBe('$0.00')
  })

  test('null shows a dash, and an unexpected value is shown as it came', () => {
    expect(formatMoney(null)).toBe('—')
    expect(formatMoney(undefined)).toBe('—')
    expect(formatMoney('1e3')).toBe('1e3')
    expect(formatMoney('')).toBe('')
  })
})

describe('formatPercent', () => {
  test.each([
    ['93.8', '93.8%'],
    ['-3.0', `${MINUS}3.0%`],
    ['0.0', '0.0%'],
    ['1250.5', '1,250.5%'],
  ])('%s → %s', (value, shown) => {
    expect(formatPercent(value)).toBe(shown)
  })

  test('change and null', () => {
    expect(formatPercent('12.5', { sign: 'change' })).toBe('+12.5%')
    expect(formatPercent(null)).toBe('—')
  })
})

describe('formatMonth and formatDate', () => {
  test('months', () => {
    expect(formatMonth('2026-10')).toBe('October 2026')
    expect(formatMonth('2026-01', 'short')).toBe('Jan')
    expect(formatMonth('2026-08', 'name')).toBe('August')
    expect(formatMonth('2026-13')).toBe('2026-13')
    expect(formatMonth(null)).toBe('—')
  })

  test('dates', () => {
    expect(formatDate('2026-10-04')).toBe('Oct 4')
    expect(formatDate('2026-10-14', true)).toBe('Oct 14, 2026')
    expect(formatDate('2026-00-04')).toBe('2026-00-04')
    expect(formatDate(null)).toBe('—')
  })
})

describe('formatSpent', () => {
  test('money out as its size, money in with a plus, zero and null plain', () => {
    expect(formatSpent('-74.09')).toBe('$74.09')
    expect(formatSpent('85.00')).toBe('+$85.00')
    expect(formatSpent('0.00')).toBe('$0.00')
    expect(formatSpent(null)).toBe('\u2014')
  })
})
