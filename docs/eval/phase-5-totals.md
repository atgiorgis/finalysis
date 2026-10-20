# Phase 5 totals against the answer key (ANA-06)

Evidence for ANA-06: the analytics totals checked cell by cell against hand-labelled data, with the match rate.
From `AnalyticsSampleDataTest` (`docs/plans/phase-5.md` §9.3, step 4 notes), for the alerts `AlertSampleDataTest`
(steps 5 and 6), and for the recurring charges `RecurringSampleDataTest` (step 6). All run in every `./mvnw verify` and
use no AI. With step 6 the report is complete: ANA-06 is Done.

- **Commit:** totals from the Phase 5 step 4 commit (on top of `6b9fec8`); the step 5 alerts from the step 5 commit
  (on top of `4e2d219`); the full alert list and the recurring charges from the step 6 commit (on top of `8bd95cf`).
  Each on a clean tree. Synthetic sample data only.
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

From `AlertSampleDataTest`: the sample data imported as above, with the answer key's categories, and every
alert from March to October 2026 listed with the default settings (`docs/plans/phase-5.md` §6.3). Each basis is
asserted to the cent against the plan's hand-worked figures (§6.3, §6.6), never against the code's.

The answer key labels three anomalies; the plan expects five alerts: those three plus a spike in Shopping (which the
Best Buy purchase causes) and one in Dining (a real, steady rise in the synthetic data, listed on purpose).

| Label | Row | Expected alert | Result |
|---|---|---|---|
| `duplicate` | Apex Visa 2026-06-13 `SAFEWAY #1234 ARLINGTON VA` −84.12 (twice) | `POSSIBLE_DUPLICATE` 2026-06 | **Raised** (step 5) |
| `large_purchase` | Apex Visa 2026-08-22 `BEST BUY 00012345 ARLINGTON VA` −899.99 | `LARGE_TRANSACTION` 2026-08 | **Raised** (step 5) |
| `price_increase` | Apex Visa 2026-07-12 `SPOTIFY USA` −12.99 | `RECURRING_PRICE_INCREASE` 2026-07 | **Raised** (step 6) |
| — | Shopping, 2026-08 | `CATEGORY_SPIKE` 2026-08 | **Raised** (step 5) |
| — | Dining, 2026-08 | `CATEGORY_SPIKE` 2026-08 | **Raised** (step 5) |

The basis of each alert:

| Alert | Observed | Baseline | Threshold | Period | Details |
|---|---:|---:|---:|---|---|
| Safeway duplicate | 2 txns | — | 2 txns | 2026-06-13 to 2026-06-13 | same account, merchant and amount; 84.12 each; window 1 day |
| Best Buy large transaction | 899.99 | 66.43 (median) | 500.00 | 2026-02-23 to 2026-08-21 | 15 earlier Shopping charges; 13.55 × the median; max(500.00, 3 × 66.43 = 199.29) |
| Shopping spike | 1183.67 | 244.42 | 366.63 | 2026-05-01 to 2026-07-31 | +939.25, +384.3%; max(244.42 × 1.5 = 366.63, 244.42 + 100.00) |
| Dining spike | 340.63 | 207.79 | 311.68 | 2026-05-01 to 2026-07-31 | +132.84, +63.9%; 207.79 × 1.5 = 311.685 → 311.68 (`HALF_EVEN`), above 307.79 |
| Spotify price increase | 12.99 | 11.99 (stable price) | 12.49 | 2026-05-12 to 2026-07-12 | 11.99 on 05-12 and 06-12, then 12.99: +1.00, +8.3%; threshold 11.99 + 0.50 |

**No other alert** is raised in those eight months: the test asserts the list is exactly these five. In particular:

- no month has another duplicate pair, rent (1850.00 a month) is never a large transaction (Housing has fewer than five
  earlier charges until September, whose median is 1850.00), and Dining in September (356.91 against 263.08, +35.7%)
  is no spike;
- Spotify's 12.99 in August and September is no further increase (the two before are not both 12.99, then 12.99 is
  not 0.50 above 12.99), and the utilities never have two equal charges in a row, so they never qualify;
- **no new recurring charge.** Netflix, Spotify, rent and the utilities start within 28 days of their account's first
  covered day. Google One's first charge (2026-04-20) is 36 days after Apex Visa's first covered day (2026-03-15),
  because the synthetic Apex data begins on 04-01. With the plan's `min-prior-days` of 30 it was raised as new; the
  default is 45 since step 6 (more than `max-gap-days`, 35: a series that began before the data can first show up a
  whole gap after it), and it isn't.

## Recurring charges

From `RecurringSampleDataTest`: `GET /api/analytics/recurring?status=ALL` on the same data lists exactly the plan's
eight series (§9.3), every field written by hand from `answer-key.csv`. Newest last charge first:

| Merchant (as cleaned) | Account | Category | Typical | Last | Fixed price | Charges | First | Last | Next expected | Status |
|---|---|---|---:|---:|---|---:|---|---|---|---|
| CITY OF ARLINGTON WATER UTIL PMT | Northfield Checking | Utilities | 46.81 | 47.98 | no | 6 | 2026-04-22 | 2026-09-22 | 2026-10-22 | ACTIVE |
| GOOGLE GOOGLE ONE | Apex Visa | Subscriptions | 2.99 | 2.99 | yes | 6 | 2026-04-20 | 2026-09-20 | 2026-10-20 | ACTIVE |
| METRO POWER CO ONLINE PMT | Northfield Checking | Utilities | 96.60 | 114.64 | no | 6 | 2026-04-18 | 2026-09-18 | 2026-10-18 | ACTIVE |
| CLEARLINE WIRELESS AUTOPAY | Northfield Checking | Utilities | 68.95 | 68.90 | no | 6 | 2026-04-15 | 2026-09-15 | 2026-10-15 | ACTIVE |
| SPOTIFY USA | Apex Visa | Subscriptions | 11.99 | 12.99 | yes | 6 | 2026-04-12 | 2026-09-12 | 2026-10-12 | ACTIVE |
| NETFLIX.COM | Apex Visa | Subscriptions | 15.49 | 15.49 | yes | 6 | 2026-04-06 | 2026-09-06 | 2026-10-06 | ACTIVE |
| PARKSIDE PROPERTY MGMT RENT PMT | Northfield Checking | Housing | 1850.00 | 1850.00 | yes | 6 | 2026-04-01 | 2026-09-01 | 2026-10-01 | ACTIVE |
| SKYWAVE INTERNET AUTOPAY | Northfield Checking | Utilities | 79.99 | 79.99 | yes | 5 | 2026-04-12 | 2026-08-12 | 2026-09-12 | **ENDED** |

- **Typical** is the lower middle charge (`percentile_disc(0.5)`), so Spotify's is 11.99 (three charges at each
  price) while its last is 12.99; the utilities' medians are 46.81, 96.60 and 68.95.
- **Skywave Internet is `ENDED`:** no September charge, and 2026-08-12 is 49 days before Northfield Checking's last
  covered day (2026-09-30), more than `active-days` (45). This is the backlog's missing-bill case.
- **Nothing else is a series.** Summit Card's most frequent merchants (a ramen shop, a grocer) have no latest run of
  three charges 25 to 35 days apart; Apex Visa's Shell, CVS and Target purchases have runs of two at most.

## The fixture

On the Phase 5 fixture the checks raise exactly the six rows of the hand-written
`sample-data/analytics/expected-alerts.csv` (`AlertFixtureTest`: the two April spikes, the large charge, the March
duplicate, the Streamco price increase 9.99 → 11.49 in March and the Cloudbox new recurring charge in April), and the
recurring endpoint lists exactly the three rows of `expected-recurring.csv` (`RecurringChargesApiTest`).

