/** Stands in for a tab until its step builds it. */
export default function PlaceholderPage({ label, step }: { label: string; step: string }) {
  return (
    <section className="page">
      <h1>{label}</h1>
      <p className="muted">Built in Phase 7 step {step}.</p>
    </section>
  )
}
