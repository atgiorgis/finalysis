import { useCallback, useState } from 'react'

/**
 * Dark or light (docs/plans/phase-7.md, step 7c). The choice is a per-browser convenience kept in localStorage; when
 * storage can't be read (private mode, blocked site data), the theme is dark and the app works the same.
 */
export type Theme = 'dark' | 'light'

export const THEME_KEY = 'finalysis.theme'

export function readTheme(): Theme {
  try {
    return localStorage.getItem(THEME_KEY) === 'light' ? 'light' : 'dark'
  } catch {
    return 'dark'
  }
}

function saveTheme(theme: Theme): void {
  try {
    localStorage.setItem(THEME_KEY, theme)
  } catch {
    // Not kept: the switch still works for this visit.
  }
}

/** Puts the theme on <html>, where theme.css picks its token set. */
export function applyTheme(theme: Theme): void {
  document.documentElement.dataset.theme = theme
}

function currentTheme(): Theme {
  return document.documentElement.dataset.theme === 'light' ? 'light' : 'dark'
}

/** The theme shown and the call that changes it: applied at once and remembered. */
export function useTheme(): [Theme, (theme: Theme) => void] {
  const [theme, setState] = useState<Theme>(currentTheme)
  const setTheme = useCallback((next: Theme) => {
    applyTheme(next)
    saveTheme(next)
    setState(next)
  }, [])
  return [theme, setTheme]
}
