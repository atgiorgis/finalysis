import { createContext, useContext } from 'react'
import type { VaultStatus } from '../api'

export interface VaultControls {
  /** The vault's status as last read; `storage: 'PLAIN'` is the dev database with the vault off. */
  status: VaultStatus | null
  /** Locks now and shows the unlock screen; the page is remembered for after unlock. */
  lock: () => Promise<void>
  /** Reads the status again (after a settings change). */
  refresh: () => Promise<void>
}

export const VaultContext = createContext<VaultControls>({
  status: null,
  lock: async () => {},
  refresh: async () => {},
})

/** The vault's status and its Lock action, for the top bar, the menu and Settings. */
export function useVault(): VaultControls {
  return useContext(VaultContext)
}

/** True when there is a vault to lock (not the plain dev database). */
export function hasVault(status: VaultStatus | null): boolean {
  return status?.storage === 'VAULT'
}
