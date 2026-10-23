import { describe, expect, test } from 'vitest'
import { answerParts, stripEmphasis } from './chatText'

describe('stripEmphasis', () => {
  test.each([
    ['You spent more in **March 2026**.', 'You spent more in March 2026.'],
    ['That was *mostly* rent.', 'That was mostly rent.'],
    ['An _important_ note.', 'An important note.'],
    ['Twice: __bold__ and **bold**.', 'Twice: bold and bold.'],
    ['***Both***', 'Both'],
    ['**$1,850.00** on rent [1]', '$1,850.00 on rent [1]'],
    ['(**Groceries**)', '(Groceries)'],
    ['**Rent is *most* of it**', 'Rent is most of it'],
  ])('%s', (answer, shown) => {
    expect(stripEmphasis(answer)).toBe(shown)
  })

  test('markers inside words, lone markers and list markers stay', () => {
    expect(stripEmphasis('SQ *TNK 0193 and AMZN MKTP US*7ETP43')).toBe('SQ *TNK 0193 and AMZN MKTP US*7ETP43')
    expect(stripEmphasis('snake_case and my_var_name')).toBe('snake_case and my_var_name')
    expect(stripEmphasis('* Groceries\n* Dining\n- Rent')).toBe('* Groceries\n* Dining\n- Rent')
    expect(stripEmphasis('2 * 3 = 6')).toBe('2 * 3 = 6')
    expect(stripEmphasis('a ** b')).toBe('a ** b')
  })

  test('a list item with emphasis keeps its marker and loses the emphasis', () => {
    expect(stripEmphasis('* **Rent**: $1,850.00\n1. *Dining*')).toBe('* Rent: $1,850.00\n1. Dining')
  })

  test('emphasis never spans lines; line breaks stay', () => {
    expect(stripEmphasis('**open\nclose**')).toBe('**open\nclose**')
    expect(stripEmphasis('one\n\ntwo')).toBe('one\n\ntwo')
  })

  test('citation markers are untouched', () => {
    expect(stripEmphasis('see [1] and [2]')).toBe('see [1] and [2]')
  })
})

describe('answerParts', () => {
  test('cited markers become parts; the text between them stays', () => {
    expect(answerParts('Rent [1] and dining [2].', new Set([1, 2]))).toEqual([
      { text: 'Rent ' },
      { marker: 1 },
      { text: ' and dining ' },
      { marker: 2 },
      { text: '.' },
    ])
  })

  test('a marker with no citation stays text', () => {
    expect(answerParts('Rent [1] and [7].', new Set([1]))).toEqual([{ text: 'Rent ' }, { marker: 1 }, { text: ' and [7].' }])
  })

  test('link-like text is only text', () => {
    const text = 'See [rows](/drill?bucket=EXPENSE&evil=1) or /alerts?key=x or https://example.com [1]'
    expect(answerParts(text, new Set([1]))).toEqual([
      { text: 'See [rows](/drill?bucket=EXPENSE&evil=1) or /alerts?key=x or https://example.com ' },
      { marker: 1 },
    ])
  })
})
