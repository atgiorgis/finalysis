# Phase 5 totals against the answer key (ANA-06)

Evidence for ANA-06: the analytics totals checked cell by cell against hand-labelled data, with the match rate.
From `AnalyticsSampleDataTest` (`docs/plans/phase-5.md` §9.3, step 4 notes) and, for the alerts, `AlertSampleDataTest`
(step 5). Both run in every `./mvnw verify` and use no AI.

- **Commit:** totals from the Phase 5 step 4 commit (on top of `6b9fec8`); the step 5 alerts from the step 5 commit
  (on top of `4e2d219`). Each on a clean tree. Synthetic sample data only.
- **Data:** `sample-data/statements/` (4 accounts, 25 statements) imported through the API, with transfer
  detection running as in production. Every txn then gets the answer key's category (`answer-key.csv`, 267 rows,
  April to September 2026), so the comparison tests the counting rules, not categorization.
- **Oracle:** `AnswerKeyOracle` computes the expected cells from `answer-key.csv` with plain Java streams. It shares
  no code with analytics (no SQL, no `TxnBucketSql`, no service); the counting rules are written out again in it.

## Result

| Run | Category cells | Month-total cells | Compared | Matched | Match rate |
|---|---:|---:|---:|---:|---:|
| Sample data | 162 | 320 | 482 | 482 | **100%** |
| Broken June file | 162 | 320 | 482 | 471 | 97.7%, as designed (below) |

- **Category cells:** month × bucket × category, for the household and each of the 4 accounts, every cell present
  on either side. Linked transfers are one cell per month, whatever their category.
- **Month-total cells:** `income`, `spending`, `net`, `savingsRatePercent`, `unlinkedTransfers`, `linkedTransfers`,
  `netChange` and `txnCount`, for each scope and each month from March to October 2026. That is the key's six
  months plus March and October, which the key has no rows in and which must therefore be empty.
- **Transfers:** detection links exactly the key's 36 transfer rows.

## Broken June file

`sample-data/broken/summit-card_2026-06.csv` lacks the 2026-06-01 `DOORDASH*THAI BASIL` −23.44 row, so its balance
check fails. Imported instead of the good file and compared with the full answer key, exactly 11 cells differ, each
by that one row:

- Dining in June (`EXPENSE`), for the household and for Summit Card: −23.44 and one row fewer than the key;
- the June totals built on it: `spending`, `net`, `netChange` and `txnCount` for both, and the household's
  `savingsRatePercent` (Summit Card has no income, so its rate is null either way).

Compared with the key minus that row, all 482 cells match. June, and only June, carries `RECONCILIATION_FAILED`
with the Summit statement's id.

## Drill-down

The 318 sample-data cells of the drill-down (`GET /api/analytics/transactions`; step 3) are checked against the
oracle as well as against the endpoints: every category row × month for the household and each account, each
month's income and spending, and each merchant's `spent`. All 318 match. The key has no cleaned merchant, so a
merchant's figure sums the key's spending rows grouped by the merchant the import gave each one
(`DrillDownSampleDataTest`).

## Alerts against the labels

From `AlertSampleDataTest` (step 5): the sample data imported as above, with the answer key's categories, and every
alert from March to October 2026 listed with the default settings (`docs/plans/phase-5.md` §6.3). Each basis is
asserted to the cent against the plan's hand-worked figures (§6.3, §6.6), never against the code's.

The answer key labels three anomalies; the plan expects five alerts: those three plus a spike in Shopping (which the
Best Buy purchase causes) and one in Dining (a real, steady rise in the synthetic data, listed on purpose).

| Label | Row | Expected alert | Result |
|---|---|---|---|
| `duplicate` | Apex Visa 2026-06-13 `SAFEWAY #1234 ARLINGTON VA` −84.12 (twice) | `POSSIBLE_DUPLICATE` 2026-06 | **Raised** (step 5) |
| `large_purchase` | Apex Visa 2026-08-22 `BEST BUY 00012345 ARLINGTON VA` −899.99 | `LARGE_TRANSACTION` 2026-08 | **Raised** (step 5) |
| `price_increase` | Apex Visa 2026-07-12 `SPOTIFY USA` −12.99 | `RECURRING_PRICE_INCREASE` 2026-07 | Step 6 |
| — | Shopping, 2026-08 | `CATEGORY_SPIKE` 2026-08 | **Raised** (step 5) |
| — | Dining, 2026-08 | `CATEGORY_SPIKE` 2026-08 | **Raised** (step 5) |

The basis of each step 5 alert:

| Alert | Observed | Baseline | Threshold | Period | Details |
|---|---:|---:|---:|---|---|
| Safeway duplicate | 2 txns | — | 2 txns | 2026-06-13 to 2026-06-13 | same account, merchant and amount; 84.12 each; window 1 day |
| Best Buy large transaction | 899.99 | 66.43 (median) | 500.00 | 2026-02-23 to 2026-08-21 | 15 earlier Shopping charges; 13.55 × the median; max(500.00, 3 × 66.43 = 199.29) |
| Shopping spike | 1183.67 | 244.42 | 366.63 | 2026-05-01 to 2026-07-31 | +939.25, +384.3%; max(244.42 × 1.5 = 366.63, 244.42 + 100.00) |
| Dining spike | 340.63 | 207.79 | 311.68 | 2026-05-01 to 2026-07-31 | +132.84, +63.9%; 207.79 × 1.5 = 311.685 → 311.68 (`HALF_EVEN`), above 307.79 |

**No other alert** of these three types is raised in those eight months: the test asserts the list is exactly these
four. In particular no month has another duplicate pair, rent (1850.00 a month) is never a large transaction (Housing
has fewer than five earlier charges until September, whose median is 1850.00), and Dining in September (356.91
against 263.08, +35.7%) is no spike. The Spotify row is completed with step 6.

On the Phase 5 fixture, the same checks raise exactly the step 5 rows of the hand-written
`sample-data/analytics/expected-alerts.csv` (`AlertFixtureTest`).
