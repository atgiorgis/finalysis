import type { ReactNode } from 'react'
import { LogoMark } from '../shell/icons'

/**
 * The frame of every vault screen (setup, recovery key, unlock, messages): the brand and one card, centered, nothing
 * of the app behind it. Works at 375 px.
 */
export default function VaultScreen({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="app vault-layer">
      <main className="vault-screen">
        <span className="brand vault-brand">
          <LogoMark />
          <span className="brand-name">Finalysis</span>
        </span>
        <section className="card vault-card" aria-labelledby="vault-title">
          <h1 id="vault-title">{title}</h1>
          {children}
        </section>
      </main>
    </div>
  )
}
