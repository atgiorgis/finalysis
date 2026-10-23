import '@testing-library/jest-dom/vitest'
import { cleanup, screen, within } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import { renderApp } from '../test/renderApp'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

test('the Goals tab is a shell: no goals yet, and what Layer 2 will bring', async () => {
  renderApp('/goals')

  const empty = await screen.findByRole('region', { name: 'No goals yet' })
  expect(empty).toHaveTextContent('You will set them yourself: the chat may suggest one, and only your save creates it.')
  expect(empty).toHaveTextContent('checked against your own totals')
  expect(empty).toHaveTextContent('explained by the transactions behind it')
  expect(empty).toHaveTextContent('not financial advice')
})

test('it reads no goal data and offers no control', async () => {
  const { requests } = renderApp('/goals')

  const page = (await screen.findByRole('heading', { level: 1, name: 'Goals' })).closest('section') as HTMLElement
  expect(requests.filter((r) => r.includes('goal'))).toEqual([])
  expect(within(page).queryAllByRole('button')).toEqual([])
  expect(within(page).queryAllByRole('textbox')).toEqual([])
  expect(within(page).queryAllByRole('link')).toEqual([])
})
