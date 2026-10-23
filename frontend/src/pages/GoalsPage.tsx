/**
 * Goals (GOAL-06, Phase 7b; docs/plans/phase-7.md §8): the tab and its empty state only. Goal records, the monthly
 * evaluation, the report card and the advisor note are Layer 2 (docs/plans/roadmap.md): nothing here reads or writes
 * goal data.
 */
export default function GoalsPage() {
  return (
    <section className="page">
      <h1>Goals</h1>
      <section className="card goals-empty" aria-labelledby="goals-empty-title">
        <h2 id="goals-empty-title">No goals yet</h2>
        <p>
          Goals come in a later layer of Finalysis, once your books are complete. You will set them yourself: the chat
          may suggest one, and only your save creates it.
        </p>
        <p>
          Each month, every goal will be checked against your own totals, the same figures as the Overview, and any gap
          will be explained by the transactions behind it.
        </p>
        <p className="muted">Planning from your own data, not financial advice.</p>
      </section>
    </section>
  )
}
