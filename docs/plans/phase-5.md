# Phase 5 plan: analytics

Status: **approved** 2026-10-19, with the answers under "Decisions at approval" (§13), which override anything
older in this file. Branch `v1`, starting after tag `phase-4-done`. Requirements as of `docs/requirements.md`
v1.46.

Scope: ANA-01 (monthly totals by category, account and merchant, by transaction date, transfers excluded),
ANA-02 (cash flow and savings rate; fixed vs discretionary is Deferred to Phase 7), ANA-03 (month over month and
year over year, plus 3- and 6-month trailing averages), ANA-04 (rule-based, explainable alerts, §6), ANA-06
(totals checked against hand-computed results, with a match rate), ANA-07 (recurring charges, §6.5), ANA-08
(data quality per month, §1.7). Read-only analytics endpoints, designed so Phase 6's chatbot can call the same
services as tools.

Out of scope:
- **Any AI.** No model call, no prompt, no Spring AI dependency in the new packages (ArchUnit, §9.4). ANA-05
  (the plain-English monthly summary) needs a model, so it moves to Phase 6 (§13). Phase 5 builds the records
  it will be written from.
- Fixed vs discretionary spending (ANA-02's last part): Phase 7, where the UI can classify categories.
- Account balances over time, category roll-ups by parent, budgets, CSV export (§13, Backlog).
- Chat and its tools (Phase 6). Phase 5 designs for them (§8) but registers no tool.
- UI: dashboard, charts and alert cards (Phase 7, UI-01).
- Budgets, forecasts, net worth (ACC-06), tax (Phase 16), multiple currencies.
- Changes to ingestion, transfer detection or categorization. Phase 5 only reads what they wrote.

Working rules (unchanged): one step per commit (small follow-ups are fine), run
`cd backend && ./mvnw clean verify` and `cd frontend && npm test` before every commit, report test counts,
push `v1`, stop for review before the next step.

**Approach.** One classification of every transaction into a bucket (§1) is the base for every total, trend
and alert. It is written once, as a single SQL fragment, and tested on its own before anything sums it. Totals
are computed on demand from `txn`; only alert dismissals are stored (§2). The tests' expected figures are
written by hand or computed by an independent oracle, never by the code under test (§9).

---

## 1. Counting rules

### 1.1 Buckets

Every txn falls into exactly one bucket. The first matching row wins:

| # | Condition | Bucket | In income or spending? |
|---|---|---|---|
| 1 | `is_transfer = true` (on any transfer link, matched or one-sided) | `LINKED_TRANSFER` | No (GR-04) |
| 2 | category kind `TRANSFER` and `transfer_excluded = false` | `UNLINKED_TRANSFER` | No, shown on its own (§1.4) |
| 3 | category kind `TRANSFER` and `transfer_excluded = true` | by sign: `EXPENSE` if `amount < 0`, else `INCOME` | Yes, under its category name (§1.4) |
| 4 | `category.spending_offset = true` (seeded: Refunds & Rebates) | `REFUND` | Spending, whatever the sign, on its own line (§1.2) |
| 5 | category kind `EXPENSE` | `EXPENSE` | Spending, whatever the sign (refunds net, §1.2) |
| 6 | category kind `INCOME` | `INCOME` | Income, whatever the sign (a reversed payroll nets) |
| 7 | no category | `UNCATEGORIZED`, split by sign | Yes: `amount < 0` in spending, otherwise in income (§1.3) |

- `amount = 0` (fee waivers, rebate notes) counts as money in. It changes no total, but it is counted.
- A txn marked not a transfer (`transfer_excluded = true`) is never on a link (`ck_txn_transfer_excluded`),
  so it never reaches row 1, and rows 3 to 6 count it like any other txn.
- `category.parent_id` is ignored: no seeded category has a parent, and there is no roll-up in Phase 5.
- The fragment lives in one place (`TxnBucketSql`, package `analytics`) and every query uses it. A test
  checks that each bucket query's rows add up to the sum of all txns (§9.2), so nothing can be silently
  dropped.

**Month totals** (all money signed, GR-03):

- `income` = sum of `amount` over the `INCOME` bucket plus uncategorized money in
- `spending` = sum of `amount` over the `EXPENSE` and `REFUND` buckets plus uncategorized money out (≤ 0 in a
  normal month)
- `net` = `income + spending` (cash flow without transfers, ANA-02)
- `savingsRatePercent` = `net / income × 100`, one decimal; null when `income ≤ 0`
- `refunds` = the `REFUND` bucket on its own line, as `{moneyOut, moneyIn, net, count}`; already in `spending`
- reported beside them, excluded from all three: `unlinkedTransfers` and `linkedTransfers`, same shape

Invariant (tested for every month, account and fixture): `income + spending + unlinkedTransfers.net +
linkedTransfers.net` = the sum of every txn's amount in the month. (`refunds` is part of `spending`, so it is
not added again.)

### 1.2 Refunds

A refund is money in. It counts in the bucket of its own category, in the month of its own `txn_date`:

- In an **expense category**, it reduces that category's spending. A refund larger than the month's
  purchases leaves a positive net, which is shown as is.
- The month of the original purchase is **never restated**. Statements don't link a refund to its purchase,
  and guessing that link would be bank-specific.
- A refund that a rule put in **Refunds & Rebates** (the V15 seed rule for money in with `REFUND`, `RETURN`,
  `REBATE` and the like) is **not income** (§13, decision 3). It reduces total `spending` and appears on its
  own `refunds` line, because the purchase it refunds can't be known. The user can move it to the expense
  category, and then it nets there instead.
- **How the rule is expressed:** V18 adds `category.spending_offset BOOLEAN NOT NULL DEFAULT false`, set for
  Refunds & Rebates only. It means "money in this category offsets spending instead of counting as income".
  - Analytics reads the flag, never a category name, so the rule stays data, not code.
  - The category's kind stays INCOME, so categorization (rule directions, AI examples, CAT-08) is untouched.
  - There is no API to change the flag in Phase 5.
- In breakdowns the Refunds & Rebates row is a spending row, so `spent` is negative for it (money back). In
  trends it is a `RECEIVED` row (§7), so the amount reads as positive money back.

*Worked example (fixture, March).* Card, 2026-03-06 `ONLINE RETURN CREDIT` +15.00, money in, seed rule →
Refunds & Rebates. → `refunds` `moneyIn` 15.00, `net` 15.00; `spending` is 15.00 less negative; `income` is
unchanged, so the savings rate's denominator doesn't grow.

*Worked example (fixture, §9.1).* Card, 2026-01-30 `TARGET` −120.00, Shopping. Card, 2026-02-08 `TARGET`
+120.00 (no refund keyword, so the merchant rule puts it in Shopping too).

| Month | Shopping `moneyOut` | `moneyIn` | `net` | Effect on `spending` |
|---|---:|---:|---:|---:|
| 2026-01 | −120.00 | 0.00 | −120.00 | −120.00 |
| 2026-02 | 0.00 | 120.00 | 120.00 | +120.00 |

Over both months, Shopping nets to 0.00. February's spending is 120.00 lower than its purchases alone.

### 1.3 Uncategorized and review-pending

Uncategorized txns count in the totals by sign. They also have their own bucket in every breakdown:
`category: null`, labelled "Uncategorized". It carries `reviewPending`, the number of its txns waiting in the
review queue for a category:

- `ai_review_reason` is set (`LOW_CONFIDENCE`, `INVALID_ANSWER`);
- or the txn is marked not a transfer and uncategorized (`NOT_TRANSFER_UNCATEGORIZED`).

Other uncategorized txns (AI off, or not run yet) count in the bucket but not in `reviewPending`. A txn in the
CAT-08 queue (`SUGGESTED_ONE_SIDED_TRANSFER`) has a Transfer category, so it is in `unlinkedTransfers`,
not here.

*Worked example (fixture, checking, February).* 2026-02-14 `MYSTERY PAYMENT` −60.00, no rule matches, AI off.
2026-02-16 `BLUE BEAN CAFE` −8.00, left by the AI with `LOW_CONFIDENCE`.
→ Uncategorized `moneyOut` −68.00, `moneyIn` 0.00, `count` 2, `reviewPending` 1. Both are in `spending`.

Why they count: a total that silently left them out would understate spending by an unknown amount. Kept in,
with their own visible line, the user (and the chatbot) can say "of which 68.00 is not yet categorized".

### 1.4 Transfer-kind categories on unlinked rows

A txn with a Transfer category and no link means a rule, the AI or the user said "this is a transfer", but
the other side was never found. Examples: a payment to a card that isn't in the app, a transfer to an outside
savings account.

**Rule:** excluded from income and spending, reported as `unlinkedTransfers` (count and amounts) in the month,
account and category breakdowns, and listed in the review queue (CAT-08) as today. It is not spending, because
the money is still the user's. If the card is imported later, detection links it and the money moves to
`linkedTransfers`, with the same totals.

How the user's CAT-08 actions move it:

| Action | Then | Bucket |
|---|---|---|
| Import the other account; detection links it | `is_transfer = true` | `LINKED_TRANSFER` |
| Mark it one-sided (`POST /api/transfers {outTxnId}`) | one-sided link, `is_transfer = true` | `LINKED_TRANSFER` |
| Mark it not a transfer | Phase 4 step 9d clears a RULE or AI Transfer category | `UNCATEGORIZED` (counts by sign) until it gets a category |
| Correct its category to an expense | USER category | `EXPENSE` |

A USER Transfer category on a txn marked not a transfer (Phase 4 §3.4: only USER can be there) is a
contradiction the user created. It counts by sign under "Transfer" (row 3 of §1.1). The prompt's rule is that
not-transfer txns count normally, and this is the only way to do that without guessing a category.

*Worked example (fixture, checking, February).* 2026-02-10 `OUTSIDE CARD AUTOPAY` −250.00, Transfer (seed
rule), no link → `unlinkedTransfers.moneyOut` −250.00, `count` 1. `spending` is unchanged. Next to it,
2026-02-05 `FIXTURE CARD PAYMENT` −400.00 is linked to the card's +400.00 → `linkedTransfers` −400.00 / +400.00
across the two accounts, and `net` 0.00 for the household.

### 1.5 Which date assigns a month

**`txn_date`**, the purchase date (ANA-01: "by transaction date"). It comes from a date embedded in the
description when there is one, otherwise from the posting date (V1 comment). The month is the calendar month
of `txn_date`: queries use `txn_date >= first day AND txn_date < first day of next month`, so the index can be
used. There are no time zones: `txn_date` is a `DATE`.

*Worked example.* Card statement 2026-02-15 to 2026-03-14, row `FIXTURE DINER` dated 2026-02-27, −45.00 → February,
although it arrives with the March statement. A checking row printed 2026-02-02 with `CHECKCARD 0131` in its
description has `txn_date` 2026-01-31 → January.

Consequence: a month can still change after it ends, when a card statement that crosses the month end
arrives. That is why months carry a coverage status (§1.6). The posting date was rejected, because it would
put a purchase in the month the bank processed it, and the totals would differ by bank.

### 1.6 Coverage, and months whose reconciliation failed

**Coverage.** Each account is *expected* in every day from the first day of its earliest statement to the
**last covered day of any account** (accounts don't close in Phase 5; see §11). A day is covered for an
account when one of its statements' `[period_start, period_end]` contains it. Postgres 17 `range_agg` over
`daterange` gives each account's covered days.

| Month status | When |
|---|---|
| `COMPLETE` | every account expected in the month is covered on every day of it |
| `PARTIAL` | some expected day of some account is not covered |
| `NONE` | no account is covered on any day of the month (outside the data, or a gap in every account) |

*Worked example (sample data).* Apex Visa's first statement starts 2026-03-15; the other accounts start
2026-04-01. The last covered day is 2026-10-14 (Apex's October statement). March: only Apex is expected, and
only from the 15th → `PARTIAL`. April to September → `COMPLETE`. October: every account is expected from the
1st to the 14th, but only Apex covers those days → `PARTIAL`. So the latest complete month is 2026-09, which is
the default for trends.

**Reconciliation flags.** A month carries flags; its totals are never changed by them:

| Flag | When | Detail |
|---|---|---|
| `RECONCILIATION_FAILED` | a statement whose period overlaps the month has `balance_check = FAILED` | `accountId`, `statementId`, `reviewedAt` (the user may have looked at it already) |
| `BALANCE_UNVERIFIED` | such a statement has `balance_check = UNVERIFIED` | same |
| `PARTIAL_COVERAGE` | the month is `PARTIAL` | `accountId` of each account with uncovered days |

*Worked example (sample data).* `sample-data/broken/summit-card_2026-06.csv` lacks the 2026-06-01
`DOORDASH*THAI BASIL` −23.44 row, so its balance check fails. Imported instead of the good file, June includes
every row the statement does have, so June's Dining is 23.44 less than the answer key, and June carries
`RECONCILIATION_FAILED {accountId: summit, statementId: …}`. The month and account endpoints show the flag; an
alert whose figures use June lists it in its `dataFlags` (§6.1).

### 1.7 Data quality per month (ANA-08)

Every `MonthTotals` (and every per-account month) carries a `dataQuality` block, so "how much of this figure
can I trust" comes back with the figure, not from a second call.

| Field | Definition |
|---|---|
| `countedTxns` | txns in the `INCOME`, `EXPENSE`, `REFUND` and `UNCATEGORIZED` buckets (transfers excluded) |
| `uncategorizedCount`, `reviewPendingCount` | as in §1.3 |
| `uncategorizedPercentOfCount` | `uncategorizedCount / countedTxns × 100` |
| `reviewPendingPercentOfCount` | `reviewPendingCount / countedTxns × 100` |
| `uncategorizedPercentOfSpending` | uncategorized money out / money out of the spending rows (`EXPENSE`, `REFUND`, uncategorized out) × 100, as magnitudes. Refund money in isn't in the denominator, so a refund can't make the share look smaller |
| `unlinkedTransferCount` | the CAT-08 rows of §1.4, also waiting for the user |
| `flags` | the §1.6 flags (`RECONCILIATION_FAILED` with statement ids, `BALANCE_UNVERIFIED`, `PARTIAL_COVERAGE`) |

Percents have one decimal and are null when the denominator is 0.

*Worked example (fixture, February, both accounts).* There are 11 counted txns (§9.1's table without the three
transfer rows). 2 are uncategorized, 1 of them review-pending. Money out of the spending rows is
1500.00 + 100.00 + 80.00 + 45.00 + 9.99 + 4.99 + 68.00 = 1807.98.
→ `uncategorizedPercentOfCount` 18.2, `reviewPendingPercentOfCount` 9.1, `uncategorizedPercentOfSpending`
3.8 (68.00 / 1807.98), `unlinkedTransferCount` 1, `flags` []. *(Step 1: figures updated for the two subscription
charges the fixture needs in February; step 1 notes.)*

---

## 2. Computed on demand or stored

**Totals, trends and coverage are computed on demand**, in SQL, from `txn`, `category`, `statement` and
`transfer_link`. Nothing is stored.

- **Correctness.** A stored total goes stale on every import, statement delete, correction, rule change,
  AI run, link, unlink and not-transfer mark. That is a dozen invalidation paths, each one a bug waiting to
  happen. On demand, a total is always right.
- **Cost.** A household has a few thousand txns a year. One month-range query over 24 months with the
  `txn_date` index is a few milliseconds. The step 2 performance test (§9.6) checks 50,000 txns, about
  ten years of a busy household.
- **Consistency.** Each request reads in one `REPEATABLE READ`, read-only transaction (the review queue's
  pattern), so a month's buckets and its flags come from the same snapshot.

**Alerts are also computed on demand**, as a deterministic function of the data. Only **dismissals** are
stored, keyed by the alert's stable key (§6.2):

- The same facts always give the same key, so an alert can't be stored twice and needs no de-duplication
  pass.
- An alert disappears by itself when its facts change: a corrected category, a deleted statement, a
  duplicate the user deleted.
- Listing alerts is a pure read, so the same service is a safe Phase 6 tool. With stored alerts, a GET
  would have to refresh them, or a listener would need to run after every one of the changes above.
- Cost: what's lost is "first seen" and "new since your last visit". That goes to the backlog for Phase 7's
  UI (§11).

---

## 3. Data model

### 3.1 Migrations

| Version | Step | Content |
|---|---|---|
| V18 `analytics` | 1 | `category.spending_offset BOOLEAN NOT NULL DEFAULT false`, set true for Refunds & Rebates (§1.2). `CREATE INDEX ix_txn_date ON txn (txn_date)`: month-range queries without an account filter today scan `ix_txn_transfer_date`, whose leading column is the flag. The step 2 EXPLAIN test (§9.6) checks that the new index is used. No other index unless that test shows a need |
| V19 `alert_dismissal` | 5 | The table below |

```sql
CREATE TABLE alert_dismissal (
    alert_key    TEXT        NOT NULL,
    alert_type   TEXT        NOT NULL,
    note         TEXT,
    dismissed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_alert_dismissal PRIMARY KEY (alert_key),
    CONSTRAINT ck_alert_dismissal_key CHECK (alert_key ~ '^[a-z-]{1,40}\.[0-9]{4}-[0-9]{2}\.[0-9a-f]{16}$'),
    CONSTRAINT ck_alert_dismissal_type CHECK (alert_type IN ('CATEGORY_SPIKE', 'LARGE_TRANSACTION',
        'POSSIBLE_DUPLICATE', 'RECURRING_PRICE_INCREASE', 'NEW_RECURRING')),
    CONSTRAINT ck_alert_dismissal_note CHECK (char_length(note) BETWEEN 1 AND 200)
);
```

- No foreign keys: the key is derived from txn fingerprints and ids (§6.2), so a dismissal survives deleting
  and re-importing the same statement, and comes back into force when the same facts reappear.
- A dismissal whose alert no longer exists stays harmless (a few rows at most), and is not cleaned up in
  Phase 5.
- `EnumCheckConstraintTest` is extended with `AlertType`.

### 3.2 Packages

- `com.finalysis.analytics`: buckets, totals, coverage, trends, merchants. Query classes over
  `JdbcTemplate` (the `review` package's pattern: plain SQL, no entity graph).
- `com.finalysis.alert`: the checks, `AlertQueryService` (read-only) and `AlertDismissalService` (the one
  write). It depends on `analytics`; `analytics` does not depend on `alert`.
- Neither depends on `categorize`, `ingestion` or `transfer` classes; they read those tables in SQL, as
  `SuggestedTransferQuery` does. `PackageDependencyTest` gains both packages.

### 3.3 Configuration

`@ConfigurationProperties` records, validated at startup (a bad value fails startup, as `TransferProperties`
does):

| Property | Default | Meaning |
|---|---|---|
| `finalysis.analytics.max-months` | 24 | Longest `from`–`to` range per request |
| `finalysis.analytics.default-months` | 6 | Range when `from` is omitted |
| `finalysis.analytics.merchants.max-limit` | 50 | Largest merchant list |
| `finalysis.alerts.max-results` | 100 | Largest alert list (`truncated: true` beyond it) |
| `finalysis.alerts.*` | §6 | One block per check |

---

## 4. Math and units

- **SQL sums, Java divides.** SQL does `sum`, `count` and `percentile_disc` over `NUMERIC`, which are exact.
  Every division (averages, percents, multiples) is in Java `BigDecimal`. Postgres `round()` rounds half away
  from zero, and doing all rounding in one place keeps it consistent.
- **Rounding:** `HALF_EVEN`. Money results have scale 2, percents scale 1, multiples scale 2.
- **No float anywhere.** A source test (the `MerchantCleanerSourceTest` idiom) fails if a SQL string in
  `analytics` or `alert` contains `avg(`, `round(`, `percentile_cont` (it returns `double precision`),
  `float` or `double precision`.
- **JSON units:**
  - Money: a string with two decimals (`@ApiMoney`, as today).
  - Percents: a string with one decimal, in a field ending in `Percent` (new `@ApiPercent`, same idiom).
  - Multiples: a string with two decimals, in a field starting with `times` (new `@ApiRatio`).
  - Months: `yyyy-MM`. Dates: ISO.
- **Signs.** Totals keep GR-03 signs (`spending` ≤ 0, `moneyOut` ≤ 0, `moneyIn` ≥ 0). Trends and alerts
  talk about how much was spent, and a signed "change of −31.8%" that means "spent more" invites misreading.
  So they use **named magnitudes** (§13):
  - `spent` = −(net of the category's spending rows): positive = net money out after refunds; negative only
    when refunds exceed purchases.
  - `received` = net of the income rows.

  Each record's Javadoc states its unit, and so will the Phase 6 tool descriptions.
- **One currency.** Amounts are in the statements' currency, assumed to be the same for every account.
  There is no currency column (§11).

---

## 5. API

All endpoints are `GET` except the dismissal pair. Everything is read in one read-only `REPEATABLE READ`
transaction.

### 5.1 Common parameters and errors

| Parameter | Type | Default | Rules |
|---|---|---|---|
| `from`, `to` | `yyyy-MM` | `to` = the month of the last covered day; `from` = `to` − 5 | `from ≤ to`; at most `max-months` months |
| `month` | `yyyy-MM` | the latest `COMPLETE` month | — |
| `accountId` | long | all accounts | must exist |
| `categoryId` | long | all | must exist; not with `uncategorized=true` |
| `uncategorized` | boolean | — | only the Uncategorized bucket |
| `limit` | int | per endpoint | 1 to the endpoint's maximum |

Errors, all in the existing problem format:

- **400** `invalid-request` with `errors[]` (`InvalidField`): a bad month format, `from > to`, a range that is
  too long, a `limit` out of range, an unknown enum value, or `categoryId` with `uncategorized`.
- **404** `not-found`: an unknown `accountId`, `categoryId` or alert key. Unlike the transaction list, which
  filters to an empty page, an analytics total is a number. "0.00 spent on account 99" would be read as an
  answer, by the user or by the chatbot, so an unknown id is an error (§13).
- **No data** is not an error. With no statements, `months` is empty, `latestMonth` and `latestCompleteMonth`
  are null, and the response is 200.

### 5.2 Endpoints

| Method and path | Returns | Errors |
|---|---|---|
| `GET /api/analytics/months?from=&to=&accountId=` | `MonthlyTotalsResponse`: one `MonthTotals` per month in the range, empty months included, each with its `refunds` line and `dataQuality` (§1.7) | 400, 404 |
| `GET /api/analytics/categories?from=&to=&accountId=&categoryId=&uncategorized=` | `CategoryTotalsResponse`: per category per month, `MoneyFlow` cells; Uncategorized, Unlinked transfers and Linked transfers rows; categories without txns in the range left out | 400, 404 |
| `GET /api/analytics/accounts?from=&to=` | `AccountTotalsResponse`: per account per month, `income`, `spending`, `net`, `refunds`, the transfer buckets, `netChange` (sum of every amount, transfers included: the account's balance movement), coverage and `dataQuality` | 400 |
| `GET /api/analytics/recurring?accountId=&status=ACTIVE\|ENDED\|ALL&limit=` | `RecurringChargesResponse`: the monthly series of §6.4 (§6.5), default `ACTIVE`, newest `lastDate` first, limit default 50, max 100, `truncated` | 400, 404 |
| `GET /api/analytics/merchants?from=&to=&accountId=&categoryId=&limit=` | `MerchantTotalsResponse`: top merchants by `spent` over the range (default 10, max 50), each with `spent`, `count`, `firstDate`, `lastDate`, the category of most of its rows. Null merchants are grouped as `merchant: null`. `othersSpent` and `othersCount` cover the rest, so the list adds up to the total | 400, 404 |
| `GET /api/analytics/trends?month=&accountId=&categoryId=` | `TrendsResponse`: per category, plus a total spending row and a total income row. Each row: `amount` in the month; previous month; change amount and percent; trailing 3- and 6-month averages; the same month a year earlier (§7) | 400, 404 |
| `GET /api/analytics/coverage` | `CoverageResponse`: per account, its covered ranges, gaps (uncovered spans between its first and the last covered day) and flagged statements; `latestMonth`, `latestCompleteMonth` | — |
| `GET /api/alerts?from=&to=&type=&accountId=&includeDismissed=false&limit=` | `AlertListResponse {items, truncated, counts by type}`: alerts whose month is in the range (default: the last 3 months to `latestMonth`), newest month first, then by type and key | 400, 404 |
| `GET /api/alerts/{key}` | One `Alert` (computed for the key's month) | 400 malformed key, 404 |
| `PUT /api/alerts/{key}/dismissal` `{note?}` | Dismisses it; idempotent (a second PUT keeps the first `dismissedAt` and replaces the note). 200 with the `Alert` (`dismissed: true`) | 400, 404 if no current alert has that key, so arbitrary keys can't be stored |
| `DELETE /api/alerts/{key}/dismissal` | Undismisses it; idempotent | 204; 400 malformed key |

### 5.3 Records (the shapes Phase 6's tools will return)

```java
// analytics
record MonthRange(YearMonth from, YearMonth to)            // validated: from ≤ to, ≤ max-months
record CategoryRef(long id, String name, CategoryKind kind) // null in a row = Uncategorized
record MoneyFlow(@ApiMoney BigDecimal moneyOut, @ApiMoney BigDecimal moneyIn,
                 @ApiMoney BigDecimal net, int count)
record MonthTotals(YearMonth month, Coverage coverage,
                   @ApiMoney BigDecimal income, @ApiMoney BigDecimal spending, @ApiMoney BigDecimal net,
                   @ApiPercent BigDecimal savingsRatePercent,           // null when income ≤ 0
                   MoneyFlow refunds,                                   // already in spending
                   MoneyFlow uncategorized, MoneyFlow unlinkedTransfers, MoneyFlow linkedTransfers,
                   int txnCount, DataQuality dataQuality)
record DataQuality(int countedTxns, int uncategorizedCount, int reviewPendingCount, int unlinkedTransferCount,
                   @ApiPercent BigDecimal uncategorizedPercentOfCount,
                   @ApiPercent BigDecimal reviewPendingPercentOfCount,
                   @ApiPercent BigDecimal uncategorizedPercentOfSpending,  // each null when its denominator is 0
                   List<MonthFlag> flags)
record MonthFlag(MonthFlagType type, long accountId, Long statementId, Instant reviewedAt)

record RecurringCharge(long accountId, String merchant, CategoryRef category, Cadence cadence, // MONTHLY
                       @ApiMoney BigDecimal typicalAmount, @ApiMoney BigDecimal lastAmount, // positive = money out
                       boolean fixedPrice, int occurrences, LocalDate firstDate, LocalDate lastDate,
                       LocalDate nextExpectedDate, RecurringStatus status)               // ACTIVE or ENDED
record RecurringChargesResponse(Long accountId, RecurringStatus status, List<RecurringCharge> items,
                                boolean truncated)
record MonthlyTotalsResponse(MonthRange range, Long accountId, YearMonth latestMonth,
                             YearMonth latestCompleteMonth, List<MonthTotals> months)

record Average(@ApiMoney BigDecimal amount, int monthsUsed, int monthsWanted)   // null amount when monthsUsed = 0
record TrendRow(CategoryRef category, TrendMeasure measure,                     // SPENT or RECEIVED
                @ApiMoney BigDecimal amount, @ApiMoney BigDecimal previousMonth,
                @ApiMoney BigDecimal changeAmount, @ApiPercent BigDecimal changePercent,
                Average trailing3, Average trailing6, @ApiMoney BigDecimal sameMonthLastYear,
                Coverage monthCoverage)
record TrendsResponse(YearMonth month, Long accountId, List<TrendRow> rows)

// alert
record Alert(String key, AlertType type, YearMonth month, AlertBasis basis, AlertDetails details,
             List<TxnRef> txns,                          // at most 10
             List<MonthFlag> dataFlags, boolean dismissed, Instant dismissedAt, String note)
record AlertBasis(Quantity observed, Quantity baseline, Quantity threshold,   // §6.6; baseline null if none
                  LocalDate periodStart, LocalDate periodEnd,                 // what the baseline covered
                  List<AlertSetting> settings)                                // the thresholds that applied
record Quantity(QuantityUnit unit, String value)  // MONEY (2 decimals), PERCENT (1), COUNT, DAYS; as JSON text
record AlertSetting(String name, Quantity value)   // e.g. "min-increase-percent", PERCENT "50.0"
record TxnRef(long id, long accountId, LocalDate txnDate, @ApiMoney BigDecimal amount, String merchant)
sealed interface AlertDetails permits CategorySpike, LargeTransaction, PossibleDuplicate,
        RecurringPriceIncrease, NewRecurring { }         // JSON discriminator "type", as ReviewItem
```

- `TxnRef` has no raw description, counterparty or account number, only the cleaned merchant. Phase 6
  decides what may go to a cloud model; this keeps the default small.
- Every list is bounded (range ≤ 24 months, merchants ≤ 50, recurring charges ≤ 100 and alerts ≤ 100 with
  `truncated`, ≤ 10 txns per alert). A response can never grow with the size of the history.

---

## 6. Alerts

### 6.1 Which checks are in Phase 5

| Check | Phase 5? | Why |
|---|---|---|
| Category spike | **Yes** | Core of ANA-04. Only needs the month totals and trailing averages |
| Unusually large transaction | **Yes** | Answer-key anomaly `large_purchase` (Best Buy, 2026-08-22) |
| Possible duplicate charge | **Yes** | Answer-key anomaly `duplicate` (Safeway, 2026-06-13). ING-03 keeps genuine duplicates on purpose; this is the prompt to check them |
| Recurring charge price went up | **Yes** | Answer-key anomaly `price_increase` (Spotify, 2026-07-12). Needs the recurrence detector (§6.4) |
| New recurring charge | **Yes** (§13) | Reuses the same detector; about a day's work more. No answer-key example, so the fixture provides one |
| Missing or late bill | Backlog | Needs "expected but absent" logic tied to coverage. The sample data has one real case (Skywave Internet has no September charge), which is a good first test later |
| Variable bill changed (utilities up x%) | Backlog | Needs a per-merchant baseline with tolerance; easy to make noisy |
| First purchase at a merchant, recurring income changes, weekly or annual cadence | Backlog | Not in ANA-04's list, or needs more history than the sample data has |

**What every check shares:**
- It only reads, and never looks at months after the alert's month (so an alert for 2026-07 doesn't change
  when August is imported, except through a re-categorization).
- It ignores `LINKED_TRANSFER` and `UNLINKED_TRANSFER` rows.
- Alerts are household-wide: `accountId` filters the list, it doesn't change the checks.
- Each alert lists in `dataFlags` the `RECONCILIATION_FAILED`/`PARTIAL_COVERAGE` flags of the months its
  figures used. A flagged month still counts; the flag says the figure may be off.

### 6.2 Keys, duplicates and dismissal

**Key** = `{type-slug}.{yyyy-MM}.{first 16 hex of SHA-256(components)}`, e.g.
`category-spike.2026-08.4b1f0c9a7e2d3851`. The month in the key lets `GET /api/alerts/{key}` compute only
that month.

| Type | Month | Hashed components |
|---|---|---|
| `CATEGORY_SPIKE` | the spike month | category id |
| `LARGE_TRANSACTION` | month of the txn | txn fingerprint |
| `POSSIBLE_DUPLICATE` | month of the earliest txn | the group's fingerprints, sorted |
| `RECURRING_PRICE_INCREASE` | month of the first charge at the new price | that txn's fingerprint |
| `NEW_RECURRING` | month of the charge that completed `min-occurrences` | fingerprint of the series' first charge |

**How duplicates of the same alert are avoided:**
- Alerts are recomputed, never inserted, so the same facts can't produce two rows.
- Within one computation, a group is one alert. Three identical charges are one `POSSIBLE_DUPLICATE` with
  three txns, not three pairs.
- A spike is one alert per category and month, however its amount moves later.
- Fingerprints, not txn ids, make a key survive deleting and re-importing the same statement (ING-03
  fingerprints are deterministic). A dismissed alert stays dismissed after a re-import.

**How you dismiss one:** `PUT /api/alerts/{key}/dismissal` with an optional note, from the API (Postman now,
the Phase 7 UI later). Never a chatbot tool. `DELETE` restores it. A dismissed alert is hidden unless
`includeDismissed=true`. If its facts change so the key changes (a fourth duplicate joins the group), the new
alert is shown: that is new information. A spike's key doesn't change when its amount grows, so a dismissed
spike stays dismissed for that category and month (§11).

### 6.3 Definitions and thresholds

All thresholds are under `finalysis.alerts.<check>`, validated at startup. The defaults were checked against
the answer key during planning (§9.3 lists exactly what they flag).

**Category spike** (`category-spike`)
- For each EXPENSE category and month M: `spent(M) ≥ trailing3(M) × (1 + min-increase-percent/100)` **and**
  `spent(M) − trailing3(M) ≥ min-increase-amount`, with `trailing3.monthsUsed ≥ min-baseline-months`.
- Skipped when the baseline is ≤ 0, and for the Uncategorized bucket (it rises and falls with AI runs).
- M may be `PARTIAL`: a partial month can only grow, so a spike already visible is real. The baseline uses
  complete months only (§7).
- Defaults: `min-increase-percent` 50, `min-increase-amount` 100.00, `min-baseline-months` 3.
- Details: `category`, `spent`, `baseline` (`Average`), `increaseAmount`, `increasePercent`.
- *Worked example (sample data).* Shopping 2026-08: spent 1183.67. Baseline May to July = (282.51 + 226.66 +
  224.10) / 3 = 244.42. Increase 939.25, 384.3% → alert. Dining 2026-08: 340.63 vs (174.75 + 190.10 + 258.52)
  / 3 = 207.79, +132.84 and +63.9% → alert. That is a real, steady rise in the synthetic data, not a labelled
  anomaly, and the expected-alerts file lists it on purpose. Dining 2026-09: 356.91 vs 263.08, +35.7% → no
  alert.

**Large transaction** (`large-transaction`)
- A money-out txn in the `EXPENSE` or `UNCATEGORIZED` bucket with `|amount| ≥ min-amount` **and**
  `|amount| ≥ times-median × median`. The median is `percentile_disc(0.5)` (exact, `NUMERIC`) of `|amount|`
  over the same category's money-out txns (Uncategorized for an uncategorized one) dated in the
  `history-days` before it, not the same day.
- Needs at least `min-history` such txns. Without them, there is no alert, so a first month of rent is not
  "unusual".
- Defaults: `min-amount` 500.00, `times-median` 3, `history-days` 180, `min-history` 5.
- Details: `txn`, `category`, `median`, `historyCount`, `timesMedian`.
- *Worked example (sample data).* Best Buy 2026-08-22, −899.99, Shopping. The 15 Shopping charges since
  2026-02-23 have a median of 66.43; 899.99 ≥ 500 and 13.55 × the median → alert. Rent, 1850.00 every month:
  Housing has fewer than 5 earlier charges until September, whose median is 1850.00, so there is never an
  alert.

**Possible duplicate** (`possible-duplicate`)
- Two or more money-out, non-transfer txns in the same account with the same amount and the same merchant
  (or, when the merchant is null, the same raw description), whose `txn_date`s are at most `window-days`
  apart. Also: two txns in one account with the same non-null `external_id` (the OFX FITID safety net ING-03
  promised).
- Pairs are joined into groups (connected components). Defaults: `window-days` 1.
- Details: `reason` (`SAME_MERCHANT_AMOUNT_DATE`, `SAME_EXTERNAL_ID`), `amount`, `merchant`.
- *Worked example (sample data).* Apex Visa 2026-06-13, `SAFEWAY #1234 ARLINGTON VA` −84.12 twice → one
  alert, 2 txns. No other pair in the sample data qualifies.

**Recurring price increase** (`recurring-price-increase`) and **new recurring** (`new-recurring`) use the
series of §6.4.
- *Price increase:* in a series, a charge with `|amount|` greater than the previous charge, where the
  `stable-count` charges before it are exactly equal (a fixed price), and the increase is
  `≥ min-increase-amount`. Only the first charge at the new price raises it, not every later month.
  Defaults: `stable-count` 2, `min-increase-amount` 0.50. Variable bills (no two equal charges in a row)
  never qualify, which is why "variable bill changed" is a separate backlog check.
  - Details: `merchant`, `accountId`, `previousAmount`, `newAmount`, `increaseAmount`, `increasePercent`.
  - *Worked example (sample data).* Spotify on Apex Visa: 11.99 on 04-12, 05-12, 06-12, then 12.99 on 07-12 →
    alert for 2026-07, +1.00, +8.3%. August and September at 12.99 → nothing more. Clearline Wireless
    (67.84, 71.06, 68.95, …) never has two equal charges in a row → no alert.
- *New recurring:* a series with at least `min-occurrences` charges whose amounts are all within
  `amount-tolerance-percent` of the series median, whose first charge comes at least `min-prior-days` after
  the account's first covered day (it started while we were watching, rather than at the start of the data),
  and whose last charge is within `active-days` of the account's last covered day.
  Defaults: `min-occurrences` 3, `amount-tolerance-percent` 20, `min-prior-days` 30, `active-days` 45.
  - Details: `merchant`, `accountId`, `typicalAmount` (median), `occurrences`, `firstDate`, `lastDate`.
  - *Worked example.* Sample data: every series (Netflix, Spotify, Google One, rent, utilities) starts in
    the first statement month → no alert. Fixture: a card subscription first charged 2026-02-09, 56 days
    after the card's first covered day, then 03-09 and 04-09 → alert for 2026-04.

### 6.4 Monthly series (the recurrence detector)

- Per account and cleaned merchant (non-null), the money-out, non-transfer txns ordered by `txn_date`.
- A series is a run of consecutive charges where each gap is `min-gap-days` to `max-gap-days` (defaults 25
  and 35). A gap outside that range ends the run.
- Charges at the same merchant several times a month (groceries, coffee) never make a run, which is
  intended.
- Monthly cadence only.
- Plain Java over rows from one SQL query (it is sequence logic, not arithmetic), unit-tested on its own.
- A merchant's latest run is its series. An earlier run that a long gap ended is not reported separately.

### 6.5 Recurring charges endpoint (ANA-07)

`GET /api/analytics/recurring` lists the series themselves, not only the alerts raised about them. This answers
"what am I subscribed to?", for the Phase 7 UI and the Phase 6 chatbot. Read-only, same detector, same
settings.

| Field | Definition |
|---|---|
| `merchant`, `accountId` | the series' key |
| `category` | the category of most of its charges (ties: the latest charge's) |
| `cadence` | `MONTHLY` (the only one in Phase 5) |
| `typicalAmount` | median `|amount|` (`percentile_disc`) of the series' charges, positive = money out |
| `lastAmount` | `|amount|` of the latest charge |
| `fixedPrice` | the last `stable-count` + 1 charges are equal |
| `occurrences`, `firstDate`, `lastDate` | of the run |
| `nextExpectedDate` | `lastDate` plus one calendar month (`LocalDate.plusMonths`, so 01-31 → 02-28). A projection, never compared with anything in Phase 5: "expected but missing" is the backlog's missing-bill check |
| `status` | `ACTIVE` when `lastDate` is within `active-days` of the account's last covered day, otherwise `ENDED` |

A series needs `min-occurrences` charges. Amount tolerance is not required here, so variable bills (utilities)
are listed, with `fixedPrice: false`.

*Worked example (sample data, `status=ALL`).*
- Spotify (Apex Visa): 6 occurrences, 2026-04-12 to 2026-09-12. `typicalAmount` 11.99, `lastAmount` 12.99,
  `fixedPrice` true, next expected 2026-10-12, `ACTIVE`.
  - The typical amount is 11.99, not 12.99: three charges at each price, and `percentile_disc(0.5)` takes the
    lower middle value of an even count.
  - That is exact and reproducible, but it can lag a recent price change, which is why `lastAmount` is
    returned beside it.
- Skywave Internet: 5 occurrences, last 2026-08-12, next expected 2026-09-12. The account's last covered day
  is 2026-09-30, 49 days later, which is more than 45 → `ENDED`. This is the missing-bill case the backlog
  check will alert on.

### 6.6 Explainable alerts: the basis

Every alert returns the numbers that triggered it in one common shape, `AlertBasis`, next to its typed
`details`. The UI and the Phase 6 chatbot can then say "why" without recomputing anything (GR-01, CHT-04).

- `observed`, `baseline`, `threshold`: each a `Quantity` with its unit (`MONEY`, `PERCENT`, `COUNT`, `DAYS`).
- `periodStart`, `periodEnd`: what the baseline covered.
- `settings`: the configured values that applied, by property name.

**The threshold is computed once, rounded, and the check is `observed ≥ threshold` on the shown values**, so an
explanation can never contradict the decision. For a spike, the baseline is rounded to cents first. The
threshold is then `max(baseline × (1 + percent/100), baseline + amount)`, rounded `HALF_EVEN` to cents.

| Type | `observed` | `baseline` | `threshold` | period |
|---|---|---|---|---|
| Category spike | `spent(M)` | `trailing3(M)` | `max(baseline × 1.5, baseline + 100.00)` | first day of M−3 to last day of M−1 |
| Large transaction | `|amount|` | the median | `max(min-amount, times-median × median)` | `history-days` before the txn |
| Possible duplicate | number of txns in the group (COUNT) | null | 2 (COUNT) | the group's first to last `txn_date`; `window-days` in settings |
| Recurring price increase | the new amount | the stable previous amount | `baseline + min-increase-amount` | the stable charges' first date to the new charge's date |
| New recurring | occurrences (COUNT) | null | `min-occurrences` (COUNT) | the series' first to last date; `min-prior-days` and tolerance in settings |

*Worked examples (sample data).*
- Shopping spike 2026-08:
  - observed 1183.67, baseline 244.42 (2026-05-01 to 2026-07-31);
  - threshold max(366.63, 344.42) = 366.63.
- Dining spike 2026-08:
  - observed 340.63, baseline 207.79;
  - 207.79 × 1.5 = 311.685, rounded `HALF_EVEN` to 311.68; 207.79 + 100.00 = 307.79;
  - threshold 311.68.
- Best Buy:
  - observed 899.99, baseline 66.43 (2026-02-23 to 2026-08-21);
  - threshold max(500.00, 199.29) = 500.00.

---

## 7. Trends (ANA-03)

For month M and each row (an expense category, Uncategorized money out, total spending after refunds, an
income category, total income, and Refunds & Rebates as a `RECEIVED` row of money back):

| Field | Definition |
|---|---|
| `amount` | `spent` or `received` in M (§4) |
| `previousMonth` | same, for M − 1 |
| `changeAmount` | `amount − previousMonth` |
| `changePercent` | `changeAmount / |previousMonth| × 100`; null when `previousMonth` is 0 |
| `trailing3`, `trailing6` | Mean of `amount` over the **`COMPLETE` months among M−1 … M−N** (M excluded, nothing searched beyond N). A complete month with no rows in the category counts as 0.00. `monthsUsed` says how many months counted; `amount` is null when none did |
| `sameMonthLastYear` | `amount` in M − 12, or null when that month is `NONE` (YoY, ANA-03) |
| `monthCoverage` | M's coverage, so a partial month's lower figure isn't misread |

- M is excluded from its own averages, because spikes compare M against them. The window doesn't reach
  further back to make up for skipped months, so the baseline is always recent.
- *Worked example (sample data, Dining).*
  - 2026-08: `trailing3` = (174.75 + 190.10 + 258.52) / 3 = 207.79 (`monthsUsed` 3).
  - 2026-09: `trailing6` covers March to August. March is `PARTIAL` and is skipped, so
    (148.46 + 174.75 + 190.10 + 258.52 + 340.63) / 5 = 222.492 → 222.49, `monthsUsed` 5, `monthsWanted` 6.
  - `sameMonthLastYear` is null: 2025-09 is `NONE`.

---

## 8. Phase 6 readiness (read-only tools)

- **Services, not controllers, are the contract.** `MonthlyTotalsService`, `CategoryTotalsService`,
  `AccountTotalsService`, `MerchantTotalsService`, `TrendsService`, `CoverageService`,
  `RecurringChargesService` and `AlertQueryService` take typed parameters (`MonthRange`, `YearMonth`, `Optional<Long>` ids, enums, bounded
  `int` limits) and return the §5.3 records. The controllers only map HTTP onto them. Phase 6's tools will
  be thin adapters over the same methods, with the same validation and bounds.
- **Read-only, enforced three ways:**
  1. Every public method of those services is `@Transactional(readOnly = true)`. A reflection test checks
     this.
  2. pgjdbc then opens the transaction `READ ONLY`, so Postgres itself rejects a write. A test proves it by
     attempting one inside such a method's transaction.
  3. Writes live only in `AlertDismissalService`. An ArchUnit rule says no query service depends on it.
     Phase 6's rule will say chat depends only on the query services.
- **No endpoint changes data** except the dismissal pair, which is a user action over the API (SEC-03),
  never a tool.
- **Units in the types:** money is `BigDecimal` with `@ApiMoney`, field names say the measure (`spent`,
  `received`, `…Percent`, `times…`), and every record's Javadoc states its sign and unit. Phase 6 will reuse
  those Javadoc lines as tool descriptions.
- **Grounding (CHT-04, Phase 6):** every number the chatbot can quote comes from one of these records, so
  the number-grounding guard has a closed set of values to check against.

---

## 9. Test strategy

No test calls a model. `NoCloudAiGuard` already blocks cloud AI in `verify`, and an ArchUnit rule
(`NoAiInAnalyticsTest`, the `NoAiInIngestionTest` idiom) keeps `analytics` and `alert` from depending on
Spring AI, the AWS SDK, `com.finalysis.ai` or the AI classes of `categorize`.

### 9.1 The fixture (`sample-data/analytics/`, synthetic)

Small and hand-computed: every expected figure is worked out by hand, written into the plan's step 1 notes
and into `expected-month-totals.csv` and `expected-alerts.csv`, and never generated by the code under test.

- **Accounts:** Fixture Checking and Fixture Card, two fictional accounts.
- **Statements:**
  - checking: January to April 2026, calendar months;
  - card: 2025-12-15 to 2026-04-14, cycles from the 15th to the 14th.
- **Categories** come from a key file, `categories.csv` (fingerprint line → category, source, AI review
  reason), applied by the test after import. Analytics tests then don't depend on categorization quality.
- **Transfer links** come from real detection (the card payment), plus one Transfer-kind row with no other
  side.

Scenarios, each a named test:

| Scenario | Rows (amounts are the fixture's) | Expected |
|---|---|---|
| Refund in an expense category, next month | §1.2 | Jan Shopping −120.00; Feb Shopping +120.00 |
| Refund in Refunds & Rebates | card 03-06 +15.00 `ONLINE RETURN CREDIT` | March `refunds` +15.00, `spending` 15.00 less negative, `income` unchanged |
| Data quality | §1.7 | Feb 18.2 / 9.1 / 3.8 percent; March carries the failed statement's flag |
| Uncategorized and review-pending | §1.3 | Feb uncategorized −68.00, count 2, reviewPending 1 |
| Unlinked Transfer-kind | §1.4 | Feb unlinkedTransfers −250.00; then each §1.4 action moves it as the table says |
| Linked transfer | §1.4 | linkedTransfers ±400.00, excluded |
| Not-transfer with an expense category | checking 02-20 −100.00, marked, USER Person-to-Person Out | counts in Feb spending |
| Not-transfer with a USER Transfer category | checking 03-18 −35.00 | counts in March spending under "Transfer" |
| Month by `txn_date` | §1.5 | Feb Dining includes the 02-27 row from the March card statement |
| Coverage | Dec card from the 15th; April card to the 14th | Dec `PARTIAL`, Jan–Mar `COMPLETE`, Apr `PARTIAL` |
| Failed reconciliation | checking March prints a final balance 10.00 above its rows | March included, flagged `RECONCILIATION_FAILED` |
| Each alert type, and each threshold just below and just at its boundary | §6.3 | exactly `expected-alerts.csv` |

*Hand-computed February (both accounts):*

| Row | Bucket | Amount |
|---|---|---:|
| 02-01 Payroll, Income | INCOME | 3000.00 |
| 02-25 Interest | INCOME | 1.25 |
| 02-03 Rent, Housing | EXPENSE | −1500.00 |
| 02-20 not-transfer, Person-to-Person Out | EXPENSE | −100.00 |
| 02-08 Target refund, Shopping | EXPENSE | 120.00 |
| 02-11 Grocer, Groceries | EXPENSE | −80.00 |
| 02-27 Diner, Dining | EXPENSE | −45.00 |
| 02-20 Streamco, 02-09 Cloudbox, Subscriptions | EXPENSE | −14.98 |
| 02-14, 02-16 uncategorized | UNCATEGORIZED (out) | −68.00 |
| 02-10 Outside card autopay | UNLINKED_TRANSFER | −250.00 |
| 02-05 card payment, both sides | LINKED_TRANSFER | −400.00 / +400.00 |

→ income 3001.25; spending −1687.98; net 1313.27; savings rate 43.8%; unlinkedTransfers −250.00;
linkedTransfers net 0.00. Check: 3001.25 − 1687.98 − 250.00 + 0.00 = 1063.27, the sum of all fourteen amounts.
*(Step 1: the two subscription rows were added; the draft had −1673.00 and 44.3%.)*

### 9.2 Invariant tests (Testcontainers)

- **Nothing dropped:** for every month and account of the fixture and the sample data, the buckets' sum
  equals the plain sum of `amount`, and the bucket counts add up to the txn count.
- **Balances:** for every reconciled statement, the account endpoint's `netChange` over the statement's days
  equals `closing − opening`. This ties analytics to ING-06.
- **Transfers:** with every account imported, household `linkedTransfers.net` is 0.00 for every month whose
  links are all matched.
- **Randomized against the database:** a seeded generator writes 2,000 txns over random buckets, signs,
  dates and links. Totals from the API must equal those of a plain Java oracle that applies §1.1 to the same
  rows. Fixed seed, so a failure can be replayed.
- **jqwik property tests** for the pure-Java parts (§13 recommendation):
  - the §1.1 oracle: buckets partition the rows, and their sum is the plain sum;
  - averages and percents: `HALF_EVEN`, scale, and null on a zero denominator;
  - `MonthlySeries`: the gap rules, and order independence of the input;
  - alert keys: stable under row order, and different for different facts;
  - the explanation contract: `observed ≥ threshold` exactly when the check fires.

  jqwik runs on the JUnit Platform, and this project is on Platform 6.0.3 (Spring Boot 4), while jqwik 1.9 is
  built for Platform 1.x. **Step 1 adds it only after one property runs under `./mvnw verify`.** If it
  doesn't, these properties become seeded-random JUnit tests, which keep the same assertions, and the step 1
  notes say so. Testcontainers invariants stay plain JUnit either way: hundreds of tries against a database
  would make `verify` slow.

### 9.3 Sample data, ANA-06

- `AnalyticsSampleDataTest` imports `sample-data/statements/` as the existing tests do, applies the answer
  key's categories, and lets detection link the transfers.
- An independent oracle reads `answer-key.csv` with plain Java streams (no SQL, no shared code) and computes
  the expected month × category × account totals.
- The test compares every cell and reports a **match rate**, which must be 100%: 6 months, about 11
  categories, 4 accounts.
- **Expected alerts on the sample data, exactly these five:**
  - Shopping spike 2026-08;
  - Dining spike 2026-08;
  - Best Buy large transaction;
  - Safeway duplicate;
  - Spotify price increase 2026-07.

  These are the three labelled anomalies plus the Dining rise, all checked by hand. Each alert's `basis`
  is asserted to the cent (§6.6).
- **Expected recurring charges on the sample data** (`status=ALL`), exactly these eight series:
  - Netflix, Spotify and Google One on Apex Visa;
  - rent, Skywave Internet, Clearline Wireless, Metro Power and City water on Northfield Checking.

  Skywave is `ENDED`. Netflix, Spotify (12.99 for its last three charges), Google One, rent and Skywave are
  `fixedPrice`; the three utility bills are not.
- The broken June file variant: totals match the answer key except June Dining (−23.44), and June is flagged.
- **Report:** `docs/eval/phase-5-totals.md`, short: cells compared, match rate, the alert list against the
  labels, the commit. That is ANA-06's evidence.

### 9.4 Rules, units, read-only

- `NoAiInAnalyticsTest`, `PackageDependencyTest` extended (§3.2), the SQL source test (§4), the
  read-only reflection test and the Postgres read-only test (§8).
- Rounding: `HALF_EVEN` boundary cases for averages and percents (e.g. 222.495 → 222.50, 222.485 → 222.48).

### 9.5 API tests

- Each endpoint: defaults, every 400 (each `InvalidField`), 404s, the empty database, money and percent
  strings, bounds (`limit` 51 → 400, a 25-month range → 400), `truncated`.
- Dismissal:
  - PUT is idempotent;
  - an unknown key → 404;
  - dismissed alerts are hidden and shown with `includeDismissed`;
  - a dismissal survives deleting and re-importing the statement;
  - a fourth duplicate makes a new key;
  - DELETE is idempotent.
- `SensitiveDataLeakTest` extended: no error response or log line carries a merchant or a description.

### 9.6 Performance

- A Testcontainers test loads 50,000 generated txns. It asserts the month-range query uses `ix_txn_date`
  (EXPLAIN, the step 8 idiom in Phase 4), and that each endpoint answers within 500 ms on a warm database.
- The time limit is generous so it doesn't flake. It is there to catch a missing index, not to benchmark.

### 9.7 Postman

New runnable folder **"9. Analytics"** against the sample data: months (with data quality), categories,
accounts, merchants, trends, coverage, recurring charges, the five alerts with their basis, dismiss and
undismiss. Folder 6 "Errors" gains the new 400s and 404s. The
README's newman command gets `--folder "9. Analytics"`.

---

## 10. Steps

| # | Step | Commit content | Main tests |
|---|---|---|---|
| 0 | Plan | This file with the §13 decisions; requirements v1.46 (ANA-05 to Phase 6, ANA-02's fixed vs discretionary to Phase 7, new ANA-07 and ANA-08); CLAUDE.md current phase | — |
| 1 | Fixture and buckets | V18 (`spending_offset`, `ix_txn_date`); `sample-data/analytics/` (statements, `categories.csv`, the hand-computed expected CSVs); `TxnBucketSql`; packages and the ArchUnit, source and read-only test scaffolding; jqwik if it runs on Platform 6 (§9.2) | `TxnBucketTest` (each §1 worked example, by bucket, the Refunds & Rebates offset included), `AnalyticsFixtureTest` (the fixture files are well-formed; the expected CSVs are *not* checked by code), `NoAiInAnalyticsTest`, the oracle properties |
| 2 | Monthly totals, coverage, data quality | Months, categories, accounts and coverage services and endpoints; `refunds` line; flags and `dataQuality`; `@ApiPercent` | `MonthlyTotalsTest` (fixture CSV), `DataQualityTest` (§1.7), invariants §9.2, `CoverageTest`, `AnalyticsApiTest`, EXPLAIN and performance §9.6 |
| 3 | Merchants and trends | Merchant totals; trends (MoM, YoY, trailing 3/6); `@ApiRatio` | `MerchantTotalsTest`, `TrendsTest` (§7 worked example, skipped partial months, null cases), rounding |
| 4 | ANA-06 on the sample data | Oracle, `AnalyticsSampleDataTest`, broken-file variant, `docs/eval/phase-5-totals.md` | the test itself (100% match) |
| 5 | Alerts: framework, spike, large, duplicate | V19; keys; `AlertBasis`; `AlertQueryService`, `AlertDismissalService`; the three checks; alert endpoints | `CategorySpikeTest`, `LargeTransactionTest`, `PossibleDuplicateTest` (boundaries, basis to the cent), `AlertKeyTest` (stable across re-import), `AlertApiTest` (dismissal) |
| 6 | Recurring series, price increase, new recurring, recurring endpoint | `MonthlySeries`; the two checks; `RecurringChargesService` and `GET /api/analytics/recurring` | `MonthlySeriesTest` (unit), `RecurringPriceIncreaseTest`, `NewRecurringTest`, `RecurringChargesApiTest`, sample-data expected alerts (exactly five) and series (exactly eight) |
| 7 | Docs, Postman, close-out | README (analytics, counting rules, alerts, endpoints, limitations); Postman folder 9; requirement statuses and change log; phase summary here; tag `phase-5-done` is yours, after your newman run | newman against a reset database |

Why this order: the counting rules (step 1) are the riskiest decisions and everything sums them, so they are
pinned by tests before any endpoint exists. ANA-06 (step 4) proves the totals before any alert relies on
them. The recurrence detector is the most heuristic part and comes last.

### Step 0 notes

Done: "Phase 5 plan" (`e5e2009`), with the §13 decisions, requirements v1.46 and CLAUDE.md. Its `./mvnw clean verify`
was reported as 1,921 backend tests. That was a counting error, not lost tests: the figure was summed from
Surefire's per-class `.txt` reports, which leave out `@Nested` classes (277 tests in 26 nested classes). Maven's
own summary for the same build is 2,198, the same as `phase-4-done`. From step 1 on, counts are Maven's summary
line.

### Step 1 notes

Done: "Phase 5 step 1: fixture and buckets". Backend 2,233 tests (Maven summary; 2,198 + 35), 0 failures, 0 skipped;
frontend 4.

**Built**
- **V18 `analytics`:** `category.spending_offset` (true for Refunds & Rebates only) and `ix_txn_date`.
- **`analytics` package:** `TxnBucket` and `TxnBucketSql`, the one SQL `CASE` of §1.1, in the plan's order.
  `alert` exists as a package only.
- **Fixture** (`sample-data/analytics/`):
  - eight statements: Fixture Checking January to April 2026 (calendar months, balance column), and Fixture Card
    2025-12-15 to 2026-04-14 (15th-to-14th cycles, balances sent with the import, so they reconcile);
  - `manifest.csv`, and `categories.csv`, the category key: one row per txn, 53 in all, with its category state and
    expected bucket;
  - the hand-computed `expected-month-totals.csv` (15 rows: household, checking, card × 5 months, data quality
    included), `expected-alerts.csv` (6) and `expected-recurring.csv` (3).
- **Generator:** the fixture is written by `tools/SampleDataGenerator.java` as literal data (`writeAnalyticsFixture`),
  the repo's rule for every non-PDF sample file. The expected files are not computed there. Regenerating gives the
  committed files byte for byte (`SampleDataTest`).

**How the expected figures were made.** By hand, row by row from the key, then the arithmetic cross-checked with a
throwaway script (scratch only, never committed): 15 rows, 0 mismatches. No test derives them from the rows.
`AnalyticsFixtureTest` checks only that the files agree with each other:
- the key covers every statement row once;
- the category states are ones the schema allows;
- opening + rows = closing for every statement, and the March checking file's last balance is exactly 10.00 high;
- the expected files are well-formed and refer to real rows.

**Tests**
- **`TxnBucketTest`** imports the fixture for real (detection runs). Every txn lands in the key's bucket, and the
  oracle agrees. Detection links exactly the four card payments, and balance checks match the manifest (March
  checking `FAILED`, the rest `PASSED`).
- **`TxnBucketSqlTest`:** the SQL against `BucketOracle` on all 45 combinations the schema allows (5 category
  states × linked or excluded or neither × money out, zero, money in).
  - A deliberate mutation (Refunds & Rebates as `INCOME`) failed both bucket tests, naming the rows; reverted.
- **`BucketOraclePropertiesTest`** (jqwik, 5 properties):
  - nothing is dropped;
  - refunds are in spending and never in income;
  - a link always wins;
  - the sign changes the bucket only for a not-transfer txn with a Transfer category;
  - uncategorized money splits by sign.
- **`AnalyticsSourceTest`** (GR-01, GR-02): no `avg(`, `round(`, `percentile_cont`, `float` or
  `double precision` in a literal; no `double` or `float` in code. It has a self-check.
- **`NoAiInAnalyticsTest`** (ArchUnit); **`PackageDependencyTest`** (analytics and alert use no other feature's
  classes; analytics doesn't know alert).
- **`ReadOnlyQueriesTest`:** the read-only rule for `*Service` methods (it checks nothing until step 2's services
  exist, by design: `allowEmptyShould`, and step 2 adds the "it sees them" assertion), its self-check, and Postgres
  rejecting a write in a read-only transaction.
- **Extended:** `SchemaMigrationTest` (`ix_txn_date`; only Refunds & Rebates offsets spending; new categories
  default to false; the kind stays INCOME) and `SampleDataParserSelectionTest` (78 text sample files; the 13 new ones
  each resolve to the CSV parser).

**jqwik.** 1.9.3 runs on JUnit Platform 6.0.3: properties run under `./mvnw verify`, and a failing one fails the
build and prints its seed (checked with a throwaway probe, removed). Its failure database goes to
`target/jqwik-database` (`junit-platform.properties`), never the repo. Property classes must end in `Test`, or
Surefire's default includes skip them silently. The first name, `BucketOracleProperties`, would have been skipped,
and was renamed.

**Changes from the plan**
- **February's worked figures.** The recurring scenarios need a Streamco and a Cloudbox charge in February, which
  the draft's table didn't have:
  - spending −1687.98 (was −1673.00), net 1313.27, savings rate 43.8% (was 44.3%);
  - data quality 18.2 / 9.1 / 3.8 (was 22.2 / 11.1 / 3.8).

  §1.7 and §9.1 are updated and marked.
- **The failed reconciliation comes from the file.** The checking CSV has a balance column, which wins over balances
  sent with the import, so March fails because its last printed balance is 10.00 above its rows, not through a
  request parameter.
- **New files and tests.** `expected-recurring.csv` (ANA-07, added at approval) and `TxnBucketSqlTest` are new;
  the exhaustive combinations test the SQL more directly than a randomized one could.
- **One linked row is uncategorized and one has an expense category**, to show that the link wins over both.
- **Not in the fixture:** the §1.5 `CHECKCARD 0131` example. Embedded-date extraction belongs to the paused PDF
  parsers; the CSV layouts print one date. The card statement that crosses a month covers §1.5.

**Found on the way.** A first targeted run failed with V18 as the only migration on the classpath. `target/classes`
was stale, most likely from the editor's incremental Java build, which writes to the same `target/`. `clean`
fixed it; it is not a code issue.

### Requirement mapping

| Requirement | Step(s) | Tests |
|---|---|---|
| ANA-01 | 1, 2, 3 | `TxnBucketTest`, `MonthlyTotalsTest`, `MerchantTotalsTest` |
| ANA-02 (cash flow, savings rate) | 2 | `MonthlyTotalsTest` |
| ANA-03 | 3 | `TrendsTest` |
| ANA-04 | 5, 6 | the alert tests, sample-data expected alerts |
| ANA-06 | 4 | `AnalyticsSampleDataTest`, `docs/eval/phase-5-totals.md` |
| ANA-07 | 6 | `MonthlySeriesTest`, `RecurringChargesApiTest` |
| ANA-08 | 2 | `DataQualityTest` |
| GR-01, GR-02 | all | SQL source test, rounding tests |
| GR-04 | 1, 2 | `TxnBucketTest`, invariants |
| SEC-03 (prepared for Phase 6) | 1, 5 | read-only tests, ArchUnit |

---

## 11. Known limitations (expected at the end of Phase 5)

- **One currency**, assumed. There is no currency column; mixed-currency accounts would sum incorrectly.
- **Accounts never close.** An account closed in June makes every later month `PARTIAL`. Needs an
  `account.closed_on` (backlog).
- **A refund never restates the purchase month.** A purchase in January and its refund in February show as
  spending in January and a credit in February.
- **Refunds & Rebates reduces total spending but belongs to no expense category.** A Target refund there
  lowers `spending` and not Shopping, so Shopping's trend can look higher than the true net. Moving the
  refund to Shopping fixes it.
- **`nextExpectedDate` is a projection only**; nothing checks it in Phase 5 (missing bills are backlog).
- **An unlinked Transfer-kind row categorized by mistake** (a rule too broad) disappears from spending into
  `unlinkedTransfers`. It is visible there and in the CAT-08 queue, but not in spending.
- **A dismissed spike stays dismissed** for its category and month, even if the amount grows afterwards.
- **No "first seen" for alerts**, because they are computed (§2).
- **Monthly series only**, one series per account and merchant. Two subscriptions with the same merchant on
  one card break the run.
- **Alerts are household-wide**; there are no per-account spikes.
- **Thresholds were checked against the synthetic answer key only.** Real data will need tuning, and every
  threshold is configurable for that reason.

## Backlog (after Phase 5)

- **Missing or late bill.** The sample data has a real case: Skywave Internet has no September charge. The
  recurring endpoint's `nextExpectedDate` is its input.
- **Variable bill changed; recurring income changed; weekly, quarterly and annual cadence.**
- **Budgets** per category and month, with alerts against them.
- **CSV export** of totals and transactions. SEC-11's formula escaping applies when it is built.
- **Account balances over time** (§13): month-end balances from statement balances. They belong with ACC-06
  (net worth, Phase 15). Until then, the account endpoint's `netChange` gives the movement.
- **Category roll-ups by parent** (§13): once categories can have parents.
- **Alert "first seen" and "new since last visit"** (for the Phase 7 UI).
- **`account.closed_on`** for coverage.
- **Moved to other phases:** ANA-05 (monthly summary) to Phase 6; fixed vs discretionary (ANA-02) to Phase 7.
- One real statement failed reconciliation in the owner's test (Phase 4 backlog). Its month will now show
  the flag; the cause still needs a synthetic reproduction.

---

## 12. Open questions (answered at approval, §13)

1. **Unlinked Transfer-kind rows:** exclude them from income and spending and show them as their own bucket
   (§1.4)? *Recommended.* The alternative, counting them as spending until resolved, overstates spending by
   every card payment to a card not in the app, which is the most common case.
2. **Uncategorized rows:** count them in income and spending by sign, with their own visible bucket (§1.3)?
   *Recommended.* Leaving them out understates totals by an amount nobody sees.
3. **Refunds & Rebates:** count it as income, as its kind says (§1.2)? *Recommended* for Phase 5: simple,
   bank-agnostic, and the user can move a refund to its expense category. The alternative, treating it as
   negative spending, needs a special case for one category.
4. **Alerts in Phase 5:** all five candidates (spike, large, duplicate, price increase, new recurring), with
   missing bills and the others in the backlog (§6.1)? *Recommended.* Alternative: four, with new recurring
   in the backlog too, saving about a day.
5. **ANA-05 and ANA-02's "fixed vs discretionary":**
   - ANA-05 moves to Phase 6. *Recommended*: it needs a model, and the chatbot explaining the `MonthTotals`
     and `TrendRow` records is the same feature.
   - Fixed vs discretionary is Deferred to Phase 7, where the UI can let you classify categories.
     *Recommended.* Alternative: V20 now, with seeded values and no way to edit them yet.
6. **Alerts computed on demand, with only dismissals stored** (§2)? *Recommended.* Alternative: stored alert
   rows refreshed by listeners, which gives "first seen" but needs a refresh after every data change.
7. **Trends and alerts in positive magnitudes** (`spent`, `received`), totals in signed amounts (§4)?
   *Recommended.* Alternative: signed everywhere, simpler to describe and easier to misread.
8. **Alert defaults** as in §6.3 (spike +50% and +100.00 over 3 months; large ≥ 500.00 and ≥ 3 × the median
   of 5+ charges in 180 days; duplicate window 1 day; price increase ≥ 0.50 after 2 equal charges; new
   recurring 3 charges within 20%)? *Recommended*; they give exactly the five alerts of §9.3 on the sample
   data.
9. **Spikes in partial months:** evaluated, with complete-month baselines (§6.3)? *Recommended.*
10. **Unknown `accountId` or `categoryId`:** 404 rather than an empty result (§5.1)? *Recommended*, so a zero
    is never mistaken for an answer.

---

## 13. Decisions at approval

Approved on 2026-10-19 with these answers. They override anything older in this file or the conversation.

| # | Question | Answer |
|---|---|---|
| 1 | Transfer-category rows with no link | Excluded from income and spending, shown on their own line (§1.4). As recommended |
| 2 | Uncategorized rows | Counted in totals by sign, on their own visible line with the review-pending count (§1.3). As recommended |
| 3 | Refunds & Rebates | **Not income.** It reduces total spending and appears on its own `refunds` line (§1.2). Expressed as the V18 flag `category.spending_offset` rather than a category name, so analytics stays rule-as-data, and the category's kind (and so categorization) is unchanged. *Differs from the recommendation; one more column in V18 and one more bucket* |
| 4 | Alerts in Phase 5 | All five (§6.1) |
| 5 | ANA-05, fixed vs discretionary | ANA-05 to Phase 6; fixed vs discretionary to Phase 7 |
| 6 | Alerts on demand, dismissals stored | Accepted (§2) |
| 7 | Magnitudes in trends and alerts | Accepted (§4) |
| 8 | Alert defaults | Accepted (§6.3) |
| 9 | Spikes in partial months | Accepted (§6.3) |
| 10 | Unknown ids → 404 | Accepted (§5.1) |

None of 4–10 widens scope beyond the draft: each was already in it, and 5 narrows it.

**Additions at approval.** These widen the draft a little; each is small because it reuses something the plan
already builds:

| Addition | Where | Cost |
|---|---|---|
| Explainable alerts: `observed`, `baseline`, `threshold`, period and the settings applied, in one `AlertBasis` | §6.6, step 5 | The checks already compute these; the change is returning them, plus the rule that the decision is made on the shown, rounded values |
| Recurring charges endpoint (ANA-07), with merchant, amounts, cadence, last date, next expected date and status | §6.5, step 6 | One endpoint over the §6.4 detector |
| Data quality per month (ANA-08): uncategorized and review-pending shares, CAT-08 count, the reconciliation flags, returned with the month's totals | §1.7, step 2 | Counts the bucket query already has |

**Recommendations asked for at approval:**

| Item | In or out | Why |
|---|---|---|
| Account balances over time | **Out** (backlog, with ACC-06) | Statement balances can be null (unverified CSVs) or wrong (failed reconciliation), and card cycles don't end at month ends. A balance series has to settle each of those, which is net-worth work. `netChange` per account and month already gives the movement, and the §9.2 test ties it to the statement balances |
| Category roll-ups by parent | **Out** (backlog) | No seeded category has a parent, and no API creates one. Roll-up code would have nothing to run on and nothing to test against real data |
| jqwik property tests | **In, for the pure-Java parts, if it runs on JUnit Platform 6** (§9.2) | The invariants are exactly what property tests are for. The Testcontainers invariants stay seeded JUnit tests, so `verify` stays fast. Step 1 proves jqwik runs on Spring Boot 4's JUnit Platform 6.0.3 before depending on it, and falls back to seeded-random JUnit tests if it doesn't |

**Backlog**, added: budgets, CSV export, account balances over time, category roll-ups (see Backlog).
