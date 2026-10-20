# Phase 5 totals against the answer key (ANA-06)

Evidence for ANA-06: the analytics totals checked cell by cell against hand-labelled data, with the match rate.
From `AnalyticsSampleDataTest` (`docs/plans/phase-5.md` §9.3, step 4 notes). It runs in every `./mvnw verify`
and uses no AI.

- **Commit:** the Phase 5 step 4 commit (on top of `6b9fec8`), on a clean tree. Synthetic sample data only.
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

The answer key labels three anomalies. The alerts are built in steps 5 and 6, so they aren't checked yet; this
section is completed then, with the plan's five expected alerts (§9.3).

| Label | Row | Expected alert |
|---|---|---|
| `duplicate` | Apex Visa 2026-06-13 `SAFEWAY #1234 ARLINGTON VA` −84.12 (twice) | `POSSIBLE_DUPLICATE` (step 5) |
| `large_purchase` | Apex Visa 2026-08-22 `BEST BUY 00012345 ARLINGTON VA` −899.99 | `LARGE_TRANSACTION` (step 5) |
| `price_increase` | Apex Visa 2026-07-12 `SPOTIFY USA` −12.99 | `RECURRING_PRICE_INCREASE` (step 6) |
