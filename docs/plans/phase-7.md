# Phase 7 plan: dashboard and chat UI

Status: **done** (2026-10-26, tag `phase-7-done`). Approved 2026-10-23 with the answers under "Decisions at approval"
(§13), which override anything older in this file. Steps 0 to 8 done, with 3b, 7b, 7c, 7d and 7e (step notes); the
phase summary, known limitations and backlog are at the end of §11. Branch `v1`, starting after tag `phase-6-done` and
the roadmap commit (`ab9d5db`). Requirements as of `docs/requirements.md` v1.76.

Scope: UI-01 (dashboard), UI-03 (chat panel), UI-04 (every total and transaction traceable to its rows and source
line), UI-05 (the cloud-AI banner, in full), UI-07 (phone layout), UI-08 (the mobile-access decision, §9), the
GOAL-06 tab shell (Phase 7b), and UI-02 (the upload page, step 7d, pulled forward from Phase 8). The UI replaces the Phase 5 status page and the Phase 6 chat test page.

Out of scope:
- **Any change to chat behavior.** The chat loop, tools, prompt, grounding, citations, memory, scoring and eval are
  frozen as evaluated in Phase 6. If a UI need seems to require a chat change, the step stops and asks.
- **Any change to how analytics counts.** New endpoints bundle and compare what the Phase 5 services return; no
  bucket rule, average rule or alert check changes.
- **Editing settings** (AI mode, models, caps, thresholds): shown read-only in Settings.
- **Goal logic** (GOAL-01 to GOAL-07 beyond the empty tab): Layer 2.
- **The monthly summary** (`POST /api/chat/summaries`, ANA-05) on the dashboard: not in the mockup; a later phase can
  add it.
- ANA-02 (fixed vs. discretionary): waits, phase to be assigned (§13). UI-02 was out of scope at approval and came
  back as step 7d (2026-10-26, §13).

Working rules (unchanged): one step per commit (small follow-ups are fine); before every commit run
`caffeinate -i ./mvnw clean verify` in `backend/` and `npm test` in `frontend/`, and report Maven's summary counts;
push `v1`; stop for review before the next step. No live AI call in any step. The categorization holdout is never run
and the chat holdout question file never opened. Synthetic data only; nothing outside the repo is opened.

**Approach.** The frontend is a view over the API. Every number on screen is a string the API returned, formatted
for display (a minus sign, a dollar sign, thousands separators) without being parsed into arithmetic. Where the
mockup shows a figure no endpoint returns (a change from last month, an "Other" slice, a categorized share), the
backend computes it in Java and returns it; the frontend never adds, subtracts, averages or divides. Charts need
numbers for geometry (bar heights, slice angles), so values are converted for drawing only, never shown or summed.
As in earlier phases, the backend reads come first in each step, with Testcontainers tests on synthetic data; the
screen that uses them comes after, rendered in tests from fixed fixtures.

---

## 1. What the plan was written from

- **The approved mockup**: the "Finalysis Dashboard" design canvas (approved at requirements v1.49, kept outside the
  repo), three artboards: **Main** (desktop page, 1440 wide), **Mobile** (390 × 844) and **Goals** (desktop page).
  Read for layout, copy, colors and spacing only; its numbers are synthetic and come from its own script.
- **The approved mockup v2** (step 7c, 2026-10-26): the "Finalysis UI Directions" design canvas (kept outside the
  repo), artboard **B · Midnight**: dark by default with a light switch, a top bar with a slide-in menu, colourful
  charts. It replaces v1 for look, layout of the shell and the Overview; read for layout, spacing, colours and copy,
  and overridden by the step 7c brief where they differ (§1.2). Artboards A and C were the directions not chosen.
- **The brief** for this plan (the owner's, 2026-10-23), which overrides the mockup where they differ (§1.1).
- **The API as built** (controllers and response records on `v1` at `ab9d5db`).
- `docs/requirements.md` v1.71, `docs/plans/roadmap.md` and the Phase 7 backlog in `docs/plans/phase-6.md`.

### 1.1 Where the brief and the mockup differ

| Mockup | This plan | Why |
|---|---|---|
| Tabs Dashboard / Goals / Chat (Phase 6) | Overview, Transactions, Alerts, Recurring, Chat, Goals, Settings | The brief's list. Overview keeps the mockup's alerts and recurring panels as summaries that link to their tabs. |
| Banner: "Only merchant names and amounts leave this computer. Totals are always computed locally." | The current wording, unchanged: "Cloud AI is on (Amazon Bedrock): merchant names and amounts are sent for categorization, and chat questions with the figures they need are sent for answers." | SEC-01 (v1.69). The mockup predates chat sending figures; its wording is no longer true. |
| Recurring status "Price up" / "New" | `ACTIVE` / `ENDED` from ANA-07 | The API has no link from a series to its alerts; price increases and new series show in Alerts. |
| "Sample data · 3 accounts" | The account count from `GET /api/accounts` | The sample data has four accounts; the line comes from the API. |
| Inter from Google Fonts | Inter bundled with the app (`@fontsource/inter`) | Local-first: the app makes no request to a third party (§4.2). |
| Details panel for "Other" with one transaction list | Its member categories, each with its total and its own drill-down | The drill-down takes one category; a multi-category drill-down would change Phase 5 code (§3.2). |

### 1.2 Where the step 7c brief and mockup v2 differ

| Mockup v2 (Midnight) | Built | Why |
|---|---|---|
| Light shades income `#0D9488`, spend `#E11D48`, s3/s5/s6 as label text | Light income `#0F766E` and spend `#BE123C`; s3/s5/s6 keep their fills, with text shades `#92400E`, `#C2410C`, `#0369A1` (`--s3-ink`, `--s5-ink`, `--s6-ink`) | Below 4.5:1 as text on the light surfaces (3.30, 4.14, 2.81, 3.14, 3.61 on surface2); `theme.test.ts` checks every pair |
| White text on the accent | The page background colour on the accent in dark (`--on-accent`) | White on `#8B7CFF` is 3.27:1 |
| No Uncategorized colour | `#9AA0B4` dark, `#CBD5E1` light, apart from Other in lightness | The donut has an Uncategorized slice |
| Month picker: ‹ "Sep 2026 · complete" › | ‹ a list of the months with data › | The list picks any month directly and marks partial ones; the arrows step |
| Picker and switch 40 px tall | 44 px | UI-07's touch targets |
| An alert count badge on the menu's Alerts | None | It would be a sum of the per-type counts; the API returns no total |
| "All 5 →" over the alerts | "All alerts →" | Same reason |
| "Sample data · 4 accounts / Latest complete month" in the menu | "N accounts / Showing {month}" | The count from `GET /api/accounts`; the month shown, which is what the menu's links keep |
| Lead sentence "You kept {net}…" for every month | "You spent {net without its minus} more than came in." when net is negative; no sentence without income; the partial-month note on a partial month | The owner's answers at the 7c plan |
| No data-quality tile, no "at a glance" | Both kept, below alerts and recurring | ANA-08 and the transfers-excluded line are requirements; the mockup was drawn without them |
| Recurring shows name and amount only | Name, amount, and a muted "category · next date" line | The list already returned them; the line keeps the old table's facts |
| Cash flow with no month buttons | The six month buttons with their net stay under the chart | They select a month by tapping (UI-07) and give each net as text |
| Google Fonts link for Manrope | Manrope bundled (`@fontsource/manrope` 5.3.0) | Local-first: no third-party request |

---

## 2. Every figure on screen and where it comes from

"New" marks the reads this phase adds (§3). Everything else exists today.

### 2.1 Shell

| Element | Source |
|---|---|
| Month picker: the months with data, default the latest complete month (the latest month when none is complete; owner's decision at step 2's approval) | `GET /api/analytics/coverage` (`latestMonth`, `latestCompleteMonth`, per-account spans) |
| Partial-month marker on the picker | coverage, per month |
| Cloud-AI banner | `GET /api/status` → `ai.mode == CLOUD` (wording above) |
| Account count in the header | `GET /api/accounts` |

### 2.2 Overview

| Panel | Element | Source |
|---|---|---|
| KPI tiles | spending, income, net, savings rate for the month | **New** `GET /api/dashboard?month=` → `month` (the month's `MonthTotals`) |
| | each against last month (amount and percent; savings rate in points) | **New**, computed in Java: spending and income from the trends' `changeAmount` / `changePercent`, net and savings rate in the dashboard service (`HALF_EVEN`, one decimal) |
| Data-quality tile | categorized share, waiting for review, partial-month line, flags | **New**: `dataQuality` as today plus `categorizedPercent` (100 − uncategorized share, in Java); flags from `MonthTotals.dataQuality` |
| Cash flow by month | 6 bars, income vs. spending, net under each, partial months striped | **New** `cashFlow`: the 6 `MonthTotals` ending at the month (`GET /api/analytics/months` rule), with coverage |
| Where the money went | donut: top 6 expense categories, Other, Uncategorized; share and amount per row; spending in the centre | **New** `spendingSlices`: category rows and `spendingSharePercent` from `/categories` (ANA-01 v1.64); Other's amount and share summed in Java, with its member categories |
| At a glance | each category's amount, income, "Transfers between your accounts: Excluded" | the month's category rows; linked-transfer total from `MonthTotals.linkedTransfers` |
| Alerts summary | count per type and the newest 3, not dismissed | **New** `alerts`: from the alert query service (`counts`, `items`) |
| Recurring summary | active series, newest first (6) | **New** `recurring`: from the recurring service, `ACTIVE`, limit 6 |

The overview is one call (`GET /api/dashboard`), as the brief asks.

### 2.3 Category drill-down (details panel)

| Element | Source |
|---|---|
| Total and share of spending | **New** `GET /api/dashboard/details?month=&categoryId=` (or `&uncategorized=true`, or `&line=INCOME`) → `total`, `spendingSharePercent` |
| Against the 3-month average (amount and percent) | **New**: `trailing3` from the trends row; the difference and percent computed in Java |
| 6-month trend, average line, partial month hollow and left out of the average | **New** `trend`: the category's monthly cells (`/categories` rule) with coverage; the line is the month's `trailing3`, drawn flat (it already skips partial months) |
| Top merchants | `merchants` from the merchant totals service (`/merchants` rule, limit 5, with the rest) |
| Transactions | `GET /api/analytics/transactions` with the `drillDown.params` the details response returns, paged |
| "12 transactions · adds up to $412.30 ✓" | the drill-down's `total` and `count`; the ✓ shows when that string equals the panel's `total` string (a comparison, not arithmetic), and a warning shows when it doesn't |
| A transaction's source line (UI-04) | **New**: `sourceLine` and the statement period added to `GET /api/transactions/{id}` (§3.3), step 3b |

### 2.4 Transactions

| Element | Source |
|---|---|
| List with filters month, category, account | `GET /api/transactions` (`from`/`to` are the month's first and last day; `categoryId`, `accountId`, `uncategorized`), paged |
| Category and account options | `GET /api/categories`, `GET /api/accounts` |
| Review queue for uncategorized items | `GET /api/review-queue?kind=AI_CATEGORY_REVIEW`, `NOT_TRANSFER_UNCATEGORIZED`, `SUGGESTED_ONE_SIDED_TRANSFER`, and `GET /api/transactions?uncategorized=true` |
| Categorize | `PUT /api/transactions/{id}/category` (with "apply to this merchant", default on); the response's other updated rows shown as a sentence |
| Undo a correction | `DELETE /api/transactions/{id}/category?removeRule=true` |
| Not a transfer / undo | `POST` / `DELETE /api/transactions/{id}/not-transfer` |
| Transaction detail (sheet) | `GET /api/transactions/{id}` with its source line (§3.3) |

These and alert dismissal were the only writes in the UI until step 7d, whose Import tab adds an account, an
import, a CSV mapping and the existing review and delete actions (step 7d notes).

### 2.5 Alerts

| Element | Source |
|---|---|
| List for the picker's 3 months, filter by type, show dismissed | `GET /api/alerts?from=&to=&type=&includeDismissed=` |
| Basis text: observed, baseline, threshold, period, settings | the alert's `basis`, each value as returned |
| The txns it is about | `txns`, each opening its transaction detail |
| Dismiss / restore | `PUT` / `DELETE /api/alerts/{key}/dismissal` (V20) |

### 2.6 Recurring

| Element | Source |
|---|---|
| Series with status, typical and last amount, cadence, next expected date | `GET /api/analytics/recurring?status=ALL` |
| A series' charges | `GET /api/analytics/transactions?accountId=&merchant=&from=&to=` (the Phase 5 Postman check's parameters) |

### 2.7 Chat

| Element | Source |
|---|---|
| Conversation list, open, delete | `GET /api/chat/conversations`, `GET …/{id}`, `DELETE …/{id}` |
| Ask | `POST /api/chat/conversations` (first question), `POST …/{id}/turns` |
| Answer, its markers and citations | `answer`, `citations` (§5.2) |
| Per-turn cost | `usage.estimatedCost` (null shown as "no price") |
| Limit reached | `limitHit` → a plain sentence per value (§5.3) |
| AI off | `GET /api/status` `ai.mode == OFF`, and a turn's `503 ai-off` |

### 2.8 Settings (read-only)

| Element | Source |
|---|---|
| AI mode, provider, model per role | `GET /api/status` → `ai` |
| Turn and daily cost caps, today's spend | **New** `GET /api/chat/usage` (§3.4) |

### 2.9 Goals (7b)

No data. "No goals yet" and the Layer 2 explanation (§8).

---

## 3. Backend additions

Each is read-only, `@Transactional(readOnly = true)`, tested with Testcontainers on the analytics fixture and the
sample data, and added to Postman **13. UI reads**. None touches the chat loop, tools, prompt or eval.

### 3.1 `GET /api/dashboard?month=` (step 2)

A new feature package `com.finalysis.dashboard`, depending only on the analytics and alert query services (an
ArchUnit rule, like chat's: no writer, nothing in `categorize`, `ingestion`, `transfer` or `chat`; nothing depends on
it). `month` defaults to the latest month (the picker's default); a month after the data or more than 24 months back
is a 400, a month without data a 200 with empty lists.

```
{ month: "2026-10", coverage: "PARTIAL", coveredThrough: "2026-10-14",
  totals:       MonthTotals,                         // as /months returns it, dataQuality included
  categorizedPercent: "93.8",                        // 100 − uncategorized share by count; null when nothing counted
  vsLastMonth:  { previousMonth, spending:{changeAmount, changePercent}, income:{…}, net:{…},
                  savingsRatePoints },                // null parts when last month has no data
  cashFlow:     [ { month, coverage, income, spending, net } × 6 ],
  spendingSlices: { spending, rows: [ { kind: CATEGORY|OTHER|UNCATEGORIZED, category, amount,
                    spendingSharePercent, members:[category…] /* OTHER only */ } ] },
  alerts:       { counts: {type: n}, newest: [Alert × ≤3] },
  recurring:    { active: n, items: [RecurringCharge × ≤6] } }
```

Rules, each tested:
- Every figure equals its existing endpoint's for the same month (`DashboardMatchesEndpointsTest`, like Phase 6's
  `ToolsMatchEndpointsTest`).
- The slices' amounts add up to `spending` exactly, and their shares to the sum of the rows' shares (Other's share is
  the sum of its members' shares as returned, so rounding can't drift from what the categories endpoint shows).
- Refunds & Rebates (a spending offset) is not a slice: it already reduces its categories and `spending`; a refund
  bigger than its category's charges makes a row positive, and such a row goes to Other's members, never a negative
  slice. (To confirm on the fixture; if the categories endpoint shows a refund row separately, the slice follows it.)
- Changes: `changePercent` null when last month is null or 0; savings-rate points null when either rate is null.

### 3.2 `GET /api/dashboard/details` (step 3)

One of `categoryId`, `uncategorized=true` or `line=INCOME`, plus `month`. Returns `total`, `spendingSharePercent`,
`trailing3` with `differenceFromAverage` and its percent (Java, `HALF_EVEN`), `trend` (6 cells with coverage),
`topMerchants` (limit 5, with the rest), and `drillDown: {path, params, total, count}` built like the citations'
drill-downs (`/api/analytics/transactions` with exact parameters).

"Other" has no details call: its panel lists its member categories from the dashboard's slice, each opening its own
details. This keeps the drill-down at one category (Phase 5's `DrillDownFilter` unchanged).

**The invariant, asserted three times:**
- backend: for every slice of every sample-data month, the slice's amount = details `total` = the drill-down's
  `total` over all pages (`DashboardInvariantTest`);
- frontend: the panel shows the clicked slice's figure, the ✓ appears when the drill-down total equals it, and a
  mismatched fixture shows the warning instead (`CategoryDetails.test.tsx`);
- newman: folder 13 compares slice, details and drill-down totals on the sample data.

### 3.3 Source line on `GET /api/transactions/{id}` (step 3b)

UI-04 asks that a click on a transaction shows its statement line. Citations already carry it (`CitedTxn.sourceLine`);
the transaction detail doesn't. Proposed: add `statement: {id, periodStart, periodEnd}` and
`sourceLine: {number, text}` to `TransactionResponse` (additive; existing fields unchanged; the source line is local
data on a local API, never logged, as the raw description already is). Decided at approval (§13).

### 3.4 `GET /api/chat/usage` (step 1)

For Settings: `{ day: "2026-10-23", spentToday: "0.214300", maxDailyCost: "1.00", maxTurnCost: "0.15",
priced: true }`. `spentToday` uses the store's existing daily-sum query (the one the cap uses), so Settings shows the
same figure the cap enforces. A new read-only controller in `chat`; the loop, the store's writes and `ChatProperties`
are untouched. Works with AI off (0 and the configured caps).

### 3.5 Citations: no backend change needed

The brief allows an endpoint or a response shape for citation links. The existing shape is enough, so none is added:

| Citation kind | Carries | Opens |
|---|---|---|
| `TRANSACTION` | `txn.id`, `status` | Transactions › detail sheet for that id; `GONE` shows "statement deleted", no link |
| `TOTAL` | `label`, `drillDowns[].params`, `total` | a drill-down view (`/drill?…` with the exact params) listing the rows and their total; several drill-downs (a SUM of terms) are listed one per term with its sign |
| `ALERT` | `alertKey`, `month` | Alerts with that alert open (`GET /api/alerts/{key}`); a key that no longer matches shows "this alert no longer applies" |
| `SERIES` | `accountId`, `merchant`, `drillDown` | Recurring with that series highlighted, its charges from the drill-down |

The citation is resolved by the backend on every read of a conversation, so a link never points at stale text.

**Links come from the citation object only** (the owner's note at step 3b's approval): a link's target and parameters
are built from the citation's own fields (`txn.id`, `drillDowns[].params`, `alertKey`, `accountId`), never from text in
the answer, which the model wrote and which can quote untrusted merchant text. Step 6 tests it with an answer whose
text looks like a link or parameters.

### 3.6 Bind the backend to localhost (step 1, UI-08)

`server.port` is set, `server.address` isn't, so Spring Boot listens on every interface: anyone on the same network can
read the API today. The database is already bound to `127.0.0.1` (`compose.yaml`). Proposed: `server.address:
127.0.0.1` in `application.yml`, with a test that the property is set (§9).

### 3.7 Not added

Everything else the screens need already exists: the transactions list and its filters, categories, accounts, the
review queue, alerts and dismissal, recurring, coverage, conversations. No migration is needed in this phase.

---

## 4. Frontend architecture

### 4.1 Libraries (step 0)

- `react-router-dom` (routes and URL state: the month and filters live in the URL, so a citation link or a reload
  lands on the same view);
- `recharts` (the stack's chart library) for the cash-flow bars, the donut and the 6-month trend;
- `@fontsource/inter` (400, 500, 600, 700); replaced in step 7c by `@fontsource/manrope` 5.3.0 (500, 600, 700, 800);
- dev: `@testing-library/user-event`. Step 7 adds Playwright for the layout check (§7.2, question 5).

Exact versions, pinned in `package.json`; nothing loaded from a CDN at runtime.

### 4.2 Theme tokens (mockup v2, step 7c)

CSS custom properties in `src/theme.css`, one set per theme on `:root[data-theme]`; `theme.ts` sets the attribute
before the first render from `localStorage['finalysis.theme']` (read and written in try/catch; dark when unreadable or
unset). The step 1–7 role names (`--text-muted`, `--warning`, `--danger`, `--slice-*`, `--series-*`, …) map onto the
sets, so every page took the theme without markup changes.

| Token | Dark | Light | Use |
|---|---|---|---|
| `--bg` / `--surface` / `--surface2` | `#0A0B10` / `#13151D` / `#1B1E2A` | `#F6F6FA` / `#FFFFFF` / `#F0F0F7` | page, cards, controls and chips |
| `--text` / `--muted` / `--border` | `#F3F4F8` / `#9CA2B8` / `#262A3B` | `#15171F` / `#636A80` / `#E5E6EE` | |
| `--accent` / `--on-accent` | `#8B7CFF` / `#0A0B10` | `#5B47E0` / `#FFFFFF` | links, focus, selection; text on the accent |
| `--income` / `--spend` | `#2DD4BF` / `#FB7185` | `#0F766E` / `#BE123C` (adjusted) | bars, edges, good and bad changes |
| `--s1` … `--s6` | `#8B7CFF`, `#2DD4BF`, `#FBBF24`, `#F472B6`, `#FB923C`, `#38BDF8` | `#5B47E0`, `#0D9488`, `#D97706`, `#DB2777`, `#EA580C`, `#0284C7` | slices by rank; s3 the net line and savings edge |
| `--s-other` / `--s-uncat` | `#5B6075` / `#9AA0B4` | `#9AA0B4` / `#CBD5E1` | Other, Uncategorized |
| `--s3-ink` / `--s5-ink` / `--s6-ink` | as the fills | `#92400E` / `#C2410C` / `#0369A1` (added) | alert-type labels, warnings |

Tinted backgrounds (`--warning-bg`, `--danger-bg`, `--positive-bg`, `--selected-bg`) are `color-mix` of a colour into
the surface. `theme.test.ts` checks every text colour on every background it is used on at 4.5:1, in both themes.
Radii 16 px on cards, 10 px on controls, 999 px on pills; Manrope with tabular figures. A slice's colour follows its
rank in the month and the legend always names it; `--s-other` is 2.9:1 against the dark surface, a shape rather than
text, named in its legend row.

### 4.3 Layout of the code

```
frontend/src/
  api.ts                 the single API module (CLAUDE.md): types and calls, grouped by feature
  format.ts              money, percent and date display from API strings, no float parsing
  theme.css
  App.tsx                router, shell
  shell/                 Header, MonthPicker, CloudAiBanner, NavTabs, BottomNav, BottomSheet
  pages/                 OverviewPage, TransactionsPage, AlertsPage, RecurringPage, ChatPage, GoalsPage, SettingsPage
  components/            KpiTile, CashFlowChart, SpendingDonut, CategoryDetails, DrillDownList, TxnSheet, …
  test/fixtures/         JSON captured from the API on the sample data (§6.1)
```

`format.ts` turns `"-1850.00"` into `−$1,850.00` by string handling only (sign, grouping, the two decimals as
returned), and is unit-tested on edge cases (`"0.00"`, `"-0.05"`, `"1234567.89"`, null shown as "—").

### 4.4 Routes

`/overview?month=` (details open as `?detail=cat:12|uncat|income|other`), `/transactions?month=&categoryId=&accountId=&review=`,
`/transactions/:id`, `/drill?…` (the generic drill-down for a TOTAL citation), `/alerts?month=&key=`,
`/recurring?series=`, `/chat`, `/chat/:id`, `/goals`, `/settings`. The Phase 5 status page and `ChatPage.tsx` (Phase 6)
are deleted in steps 1 and 6.

---

## 5. Chat UI (UI-03)

### 5.1 Screens

Desktop: the conversation list on the left, the conversation on the right. Phone: the list, then the conversation as
its own screen with a back button. Each answer shows its text with markers as links, a citations list under it, and a
footer with the model, the estimated cost of the turn, and "checked: n figures" from `grounding`. A fallback answer
(`answerSource: FALLBACK`) is labelled "The model's answer couldn't be checked, so here are the figures it used."
Delete asks for confirmation.

### 5.2 Markers

The answer's `[n]` markers become links to citation `n` (§3.5). Markdown is not rendered as HTML: the answer is plain
text with line breaks and list markers kept, so nothing in an answer can inject markup (the answer can quote merchant
text, which is untrusted). Citations hold account names and statement lines; they're local data and shown as such.

Emphasis markers are stripped for display (§13): `**`, `*` and `_` at word edges (`**March 2026**` → `March 2026`,
`_note_` → `note`), so answers don't show literal asterisks. A `*` or `_` inside a word (`SQ *TNK`, `snake_case`) and a
list marker at the start of a line (`* item`, `- item`) stay. String handling only, in `chatText.ts`, unit-tested;
the stored answer is unchanged.

### 5.3 Limits and errors, shown plainly

| Case | Shown |
|---|---|
| `limitHit` `ROUNDS`, `TOOL_CALLS`, `TOKENS`, `TIME`, `COST` | "This answer stopped early: it reached the limit on model calls / tool calls / length / time / cost for one question." |
| `503 ai-off` | the AI-off state (below) |
| `429` (daily cap) | "Today's chat budget ($1.00) is used up. It resets at midnight." with the cap from `/api/chat/usage` |
| other problems | the problem's `detail`, as the Phase 6 page does |

AI off: the Chat tab shows "Chat needs an AI model. AI is off in this setup (no `aws` profile)", the input disabled,
earlier conversations still readable.

### 5.4 The first turn is shown from the POST response

The first question of a new conversation is shown from the `POST …/turns` response, not by reading the conversation
back (decided at step 6's approval). The first question creates the conversation and moves the page to its URL
(`/chat/:id`), which would normally read that conversation. That read can be answered before the turn is stored, so it
could replace the answer just shown with an empty conversation; the page therefore skips the read for a conversation it
created itself. The response is the same record the API stores and re-resolves (answer, citations, usage, limit), so
nothing is lost by not reading it back; the next visit to the URL reads the stored conversation as usual, with its
citations re-resolved.

---

## 6. Testing

### 6.1 Fixtures

Step 0 adds `frontend/scripts/capture-fixtures.sh`: with the backend running on the sample data, AI off, it saves each
read the UI uses as JSON under `src/test/fixtures/`. Chat fixtures (a turn with each citation kind, a fallback, a
limit) are hand-written from the Phase 6 response records, since capturing a real turn needs a live model. Fixtures
are synthetic only and typed against `api.ts` (a type error fails `tsc`). The close-out re-captures and the diff must
be empty, so the fixtures can't drift from the API.

### 6.2 Frontend tests (Vitest, Testing Library)

Every screen has at least one test that renders it from fixtures with `fetch` stubbed:

- shell: tabs, month picker (changing the month changes the URL and the reads), banner on for `CLOUD`, off for
  `OFF`, with the exact SEC-01 wording;
- Overview: each KPI and its change as returned, partial bar striped, donut rows and shares as returned, alerts and
  recurring summaries link to their tabs;
- details: the invariant (§3.2), the trend's partial point hollow, Other lists its members;
- Transactions: filters change the request, categorize sends the right body and shows the response's sentence, undo,
  not-a-transfer;
- Alerts: basis values, dismiss and restore;
- Recurring: statuses, a series opens its charges;
- Chat: list, ask, each citation kind's link target, `GONE`, fallback label, each `limitHit` sentence, 429, AI off;
  `chatText.ts`: emphasis stripped at word edges, kept inside words and as list markers, markers `[n]` untouched;
- Settings: mode, models, caps, spend;
- Goals: the empty state;
- `format.ts`: the edge cases above;
- **no arithmetic**: a test that fails if a page component imports a helper that sums or divides money (a lint rule
  on `src/pages` and `src/components` banning `parseFloat`/`Number(` on API money outside chart geometry, which lives
  only in the chart components).

### 6.3 Backend tests

`DashboardMatchesEndpointsTest`, `DashboardInvariantTest`, `DashboardApiTest` (defaults, 400s, empty database),
`DashboardDetailsTest`, `DashboardReadOnlyTest` (ArchUnit), `TransactionSourceLineTest`, `ChatUsageApiTest` (AI off,
after ledger rows, the day boundary), `ServerAddressTest`.

### 6.4 Postman

Folder **13. UI reads**: the dashboard for the sample data's months, its figures checked against folder 9's
endpoints; details and the drill-down invariant; the transaction source line; `/api/chat/usage` with AI off. Run
with newman, AI off, like folders 1–12.

### 6.5 Smoke tests (the owner's)

On the sample data after step 7, then on real statements after step 8's docs are ready. Findings that are UI bugs
become 7x steps; chat findings go to the Phase 8 backlog.

---

## 7. Mobile (UI-07)

### 7.1 The layout

Mobile first: every screen is built for 375 px and widens at 768 px and 1200 px. On a phone:

- navigation (since step 7c, at every width) is the top bar's menu button opening a slide-in menu with the seven tabs;
  it closes on a tab, ✕, the backdrop or Escape, keeps Tab inside while open and returns focus to the menu button; the
  month picker takes the top bar's second row; every target at least 44 px (steps 1–7 had a bottom bar with More);
- details (category details, a transaction, an alert's basis) open as a bottom sheet, as in the mockup;
- charts tap instead of hover (a tapped bar or slice selects, the value shows below the chart, not in a tooltip);
- tables become stacked rows; wide content scrolls inside its card, never the page;
- the banner wraps, full wording kept.

### 7.2 How 375 px is tested

jsdom has no layout, so a Vitest test can't see horizontal scroll. Decided (§13): Playwright (Chromium only) in step 7,
`npm run test:layout`, opening each route at 375 × 812 against the Vite dev server with the API answered from the same
fixtures, and asserting `document.documentElement.scrollWidth <= 375` and that every button and link is at least
44 px tall. Run before every commit from step 7 on, with `npm test`. The Vitest tests also render each screen with a
`(max-width: 767px)` media match to check the phone structure (bottom bar, sheet).

---

## 8. Goals tab shell (7b, GOAL-06 tab only)

The tab, its header and an empty state: "No goals yet", and two sentences on Layer 2: goals will be set by you (the
chat may suggest one, and only your save creates it), checked each month against your own totals, with every gap
explained by the transactions behind it. No goal data, endpoint or table. The mockup's report card, cards and detail
panel are Layer 2.

---

## 9. Mobile access (UI-08), decided here

UI-08 requires login and HTTPS before any phone can reach the backend. Decided for Phase 7 (§13): **no phone access to
the backend.** The backend binds to `127.0.0.1` (§3.6), the phone layout is built and tested in a browser at phone width,
and login with HTTPS is deferred to the phase that adds encryption at rest (Phase 8, SEC-04), when the data a phone
would reach is protected on disk as well. UI-08 stays Planned with that note.

---

## 10. Phase 6 backlog: what goes in Phase 7

| Item | Proposed | Why |
|---|---|---|
| h-u10: figures where a refusal was expected | **Wait (Phase 8 chat backlog)** | Chat behavior; chat is frozen in this phase, and a fix needs new dev questions first. |
| h-x06: no tool for a share of income | **Wait (Phase 8 tool)** | A new tool changes chat behavior and needs dev questions. |
| Holdout guard per set, not per model | **Wait (Phase 9, eval suites)** | It's eval-only, but its purpose is a second holdout run (Haiku 4.5), and the chat holdout is "evaluated once and never run again" (CLAUDE.md). Changing the guard should come with a decision on that rule, or with a second frozen set; Phase 9 (QA-03, AI-07) is where eval suites are planned. |
| Haiku 4.5 skips the search fallback about half the time | **Observation only** | Recorded; affects only the budget option. |
| Cost estimator is conservative | **Leave** | Safe direction; Settings shows the real daily spend next to the caps, which makes the gap visible. |

---

## 11. Steps

| Step | Content | Backend | Tests |
|---|---|---|---|
| 0 | This plan approved. Scaffolding: router, shell placeholder, `theme.css` tokens, `format.ts`, Inter bundled, API types for every read the UI uses, `capture-fixtures.sh` and the first fixtures. The old status page still renders until step 1. | none | `format.test.ts`, router smoke test |
| 1 | Shell: header, month picker, banner, desktop tabs, phone bottom bar placeholder; Settings read-only. Status page removed. Server bound to `127.0.0.1`. | `GET /api/chat/usage`; `server.address` | `ChatUsageApiTest`, `ServerAddressTest`; shell, banner, picker, Settings tests; Postman 13 started |
| 2 | Overview: KPI tiles with changes, data-quality tile, cash flow, donut, at a glance, alerts and recurring summaries. | `GET /api/dashboard` | `DashboardMatchesEndpointsTest`, `DashboardApiTest`, `DashboardReadOnlyTest`; Overview tests |
| 3 | Category drill-down: details panel, 6-month trend, top merchants, the transactions list, the ✓ check. | `GET /api/dashboard/details` | `DashboardDetailsTest`, `DashboardInvariantTest`; invariant UI test |
| 3b | Transaction detail sheet with its statement and source line; the `/drill` view (a TOTAL citation's target). | `statement` and `sourceLine` on `GET /api/transactions/{id}` | `TransactionSourceLineTest`; sheet and `/drill` tests |
| 4 | Transactions: list, filters, review queue, categorize, undo, not-a-transfer. | none | Transactions tests |
| 5 | Alerts (basis, dismiss, restore) and Recurring (statuses, charges). | none | Alerts and Recurring tests |
| 6 | Chat UI: list, conversation, citations as links, emphasis markers stripped, cost, limits, AI off. `ChatPage.tsx` and its test deleted. | none | Chat tests, `chatText.test.ts` |
| 7 | Mobile pass at 375 px for every screen: bottom bar, bottom sheets, tap, 44 px targets. Playwright layout check. | none | `test:layout` on every route |
| 7b | Goals tab shell. | none | Goals test |
| 7c | Restyle to mockup v2 (Midnight): dark default and light theme with a switch, top bar and slide-in menu at every width, the banner as a strip, the Overview's hero, lead sentence, KPI edges, net line, donut legend, alert cards and recurring list; Manrope. | none | theme, menu, lead-sentence and hero tests; `test:layout` with the menu open in both themes |
| 7d | Import tab (UI-02, pulled forward from Phase 8): account picker and add-account, one file dropped or chosen, the import's result as returned, the CSV mapping step, statements by account with delete, the review queue's statements, uploads and transfer matches, the privacy line. | `statementId` filter on `GET /api/transactions` | `ImportPage.test.tsx`, Transactions statement filter, `TransactionApiTest.filtersByStatement`, `LastFourOutboundTest`, `LastFourChatPayloadTest`; `test:layout` for the Import screens; newman 13 |
| 7e | Corgi branding (UI only), cost display, and three import follow-ups from the owner's smoke test: Categorize now, the CSV sign choice, the embeddings line. | `totalCost` on conversations; this month on `GET /api/chat/usage`; `summary` and `searchIndex` on `POST /api/categorization/run`; `signExample` on the CSV preview | `ConversationCostTest`, `ChatUsageApiTest`, `RunResponseTest`, `AiCategorizerTest.theRunSummary…`, `CsvImportTest` sign examples, `AiStatusTest`; chat, Settings, `CategorizeNow`, naming and sign tests; `test:layout`; newman |
| 8 | Close-out: re-capture fixtures (empty diff), newman all folders, README (a section per tab with screenshots on the sample data, the new endpoints, UI-08), requirements (UI-01, UI-02, UI-03, UI-04, UI-05, UI-07 Done; GOAL-06 tab and UI-08 noted, Planned), CLAUDE.md current phase, tag `phase-7-done`. The 7e frontend failures recorded; Vitest's JSON report kept. | none | full runs |

### Step 0 notes: scaffolding

Done: "Phase 7 step 0: frontend scaffolding". Backend 3,236 tests (from the surefire reports), 0 failures, 0 skipped
(no backend change); frontend 47 tests (6 files), with `tsc` now part of `npm test`.

- **Dependencies**, pinned exactly: `react-router-dom` 7.18.4, `recharts` 3.10.1, `@fontsource/inter` 5.3.0; dev
  `@testing-library/user-event` 14.6.7 (14.6.8 was under two weeks old) and `@types/node` 22.20.5 (the guard test reads
  files). No runtime request leaves the machine: Inter is bundled.
- **Router**: `routes.tsx` holds the seven tabs in the plan's order, each a placeholder naming its step; `/` is still
  the status page with the Phase 6 chat page until step 1; an unknown path goes to `/`.
- **Theme**: `theme.css` with the §4.2 tokens; `index.css` no longer sets the font or color scheme.
- **`format.ts`**: money, percents, months and dates from the API's strings, by string handling only (no float
  parsing); a value in an unexpected shape is shown as it came.
- **API types** in `api.ts` for every read the UI uses, a `getJson` helper and a `reads` object; the chat calls are
  unchanged.
- **Guard** (`noArithmetic.test.ts`): no file under `shell/`, `pages/` or `components/` (except
  `components/charts/`) may call `parseFloat`, `parseInt`, `Number(`, `Math.*` or `toFixed`; a second test shows each
  form is caught.
- **Fixtures**: `scripts/capture-fixtures.mjs` (Node, not the plan's `.sh`: `jq` isn't installed, and Node's fetch reads
  JSON) saves 18 reads under `src/test/fixtures/`, refuses to run unless AI is off, and replaces run-dependent
  timestamps with a fixed one. Captured from a throwaway Postgres container (port 55432) and a backend on port 18080,
  loaded by the README's newman folders (254 requests, 0 failures), then both removed; the dev database was not
  touched. Two captures gave identical files. `src/test/fixtures.ts` types each file against `api.ts` at compile time
  (string unions compared as strings); a fixture missing a required field fails `tsc` (checked by hand).
- **Finding:** `GET /api/analytics/categories` returns `spending` and each row's `spendingSharePercent` as JSON
  numbers, unlike every other amount and percent (strings). Both were added in Phase 6 step 8b without `@ApiMoney` /
  `@ApiPercent`. Typed as numbers and not shown; the dashboard endpoint (step 2) returns its shares as strings. Not
  changed here: it is Phase 6 code that works, and the chat tool reads the same record. The fixture also shows REFUND
  rows with negative shares, which step 2's slices follow (§3.1).

### Step 1 notes: shell, Settings, localhost binding

Done: "Phase 7 step 1: shell, month picker, banner, Settings". Backend 3,244 tests (Maven summary), 0 failures,
0 skipped; frontend 62 tests (8 files); newman 255 requests, 0 failures, with folder 13.

- **Localhost binding (UI-08):** `server.address: 127.0.0.1` in `application.yml`. `ServerAddressTest` starts a real
  server on a random port: the property is set, a connection to loopback succeeds, a connection to this machine's
  network address is refused (skipped on a machine with none), and no profile file sets another address. The newman
  run uses `http://localhost:…` and still reaches it; the Vite proxy now targets `127.0.0.1:8080` explicitly.
- **`GET /api/chat/usage`** (`ChatUsageController`, chat package, read-only): `{day, spentToday, maxTurnCost,
  maxDailyCost, resetsAt}`. `spentToday` is the store's existing `costSince(local midnight)`, the daily cap's own sum,
  six decimals like a turn's cost; caps keep their configured value with at least two decimals (`1.00`, `0.15`), null
  when off. The plan's `priced` flag was left out: nothing on the page needs it. No change to the loop, the store's
  writes or `ChatProperties`. Tests: `ChatUsageApiTest` (empty ledger, every kind since midnight only, unpriced rows
  count 0, the day boundary with the mutable clock) and `ChatAiOffApiTest` (AI off: 0 and the caps).
- **Shell:** `ShellDataProvider` loads status, coverage and accounts once, then the picker's months (from the first
  month any account has data, at most 24, through the latest; months without data left out). `useMonth()` reads
  `?month=` (falling back to the latest month when it isn't a month with data) and keeps other query parameters when
  it changes; tab links keep the month. Header: name, picker (newest first, "(partial)" marked), "N accounts ·
  Month", desktop tabs; the phone bottom bar (Overview, Transactions, Chat, More) shows below 768 px, its full pass is
  step 7. The banner moved into the shell with the same wording and shows on every tab.
- **Removed:** the Phase 5 status page (`App.tsx`) and its test; its banner tests moved to `Shell.test.tsx`. `/` opens
  the Overview; an unknown path goes there too. The Phase 6 chat page sits on the Chat tab until step 6.
- **Settings:** the AI mode (with what Off means), the provider, the model of each role, and the chat costs; no
  control that changes anything (tested).
- `months.ts` (calendar stepping, a month's first and last day) sits outside the screen folders, so the
  no-arithmetic guard stays strict for screens.
- Postman **13. UI reads** started (chat usage with AI off); the README's newman command runs it. A re-capture of the
  fixtures on a fresh sample-data backend gave no diff. The usage fixture is hand-written in `test/renderApp.tsx`.

### Step 2 notes: Overview, dashboard endpoint, categories as strings

Done: "Phase 7 step 2: Overview and GET /api/dashboard (UI-01)". Backend 3,266 tests (Maven summary), 0 failures,
0 skipped; frontend 74 tests (9 files); newman 260 requests, 0 failures.

- **Categories as strings** (the owner's instruction at step 1's approval): `CategoryTotalsResponse.spending` is
  `@ApiMoney` and `Row.spendingSharePercent` `@ApiPercent`, a JSON serialization change only. The model never sees this
  record as JSON: `OutboundProjector` reads the two `BigDecimal`s and writes the tool view itself, so the model-facing
  text cannot change. Evidence: every chat test passes unchanged (750 in a targeted run after a clean, then the full
  build), including `ChatOutboundPayloadTest` (each tool's payload equal to its hand-checked file in
  `src/test/resources/chat/outbound/`, `get_category_totals` among them), `ChatTurnPayloadTest` and
  `ToolsMatchEndpointsTest`; no file under `chat/` (code, tests or expected payloads) changed. New:
  `AnalyticsApiTest.categorySpendingAndSharesAreStrings` (March by hand). Newman folder 9 never read the two fields; folder 13
  now checks they are strings. A first targeted run showed an "Unresolved compilation problem" in `ChatReadOnlyTest`:
  classes compiled into `target/` by the IDE's Eclipse compiler; after `clean` it passed, and every run since is `clean`.
- **`GET /api/dashboard?month=`** (`com.finalysis.dashboard`): the month's `MonthTotals`, `categorizedPercent`
  (100 − the uncategorized share by count), `vsLastMonth` (spending, income and net from `ComparisonService`, the
  `compare_periods` rule; savings-rate points subtracted in Java; null when last month has no data, compared with a
  partial month as the trends do), the six cash-flow months, the slices, the alerts of the three months to the month
  (counts and the newest three) and the active recurring series (count and the first six). One read-only,
  repeatable-read transaction, so every figure is one snapshot. 400 for a month after the latest with data; without
  statements, a 200 with nothing. Changes from the §3.1 sketch: no `coveredThrough` (the household's last covered day
  isn't each account's; the per-account partial-coverage flags say it instead) and no per-slice `count` (the
  Uncategorized row's count includes money in, so it wouldn't match the slice's drill-down).
- **Slices** (§3.1, decision 7): the six expense categories with the most spending (ties by name), then Other (the
  rest, expense categories whose refunds exceed their charges, and the Refunds & Rebates line, with its members),
  then Uncategorized money out. Every spending row lands in one slice, so they add up to spending exactly.
- **Tests:** `DashboardSlicesTest` (the rule by hand), `DashboardMatchesEndpointsTest` (fixture, four months: every
  figure equals its endpoint's JSON), `DashboardInvariantTest` (sample data, March to October: each slice equals its
  drill-down, the slices add up to spending; the sample data has months with an Other slice), `DashboardApiTest`
  (default month, March by hand, the first month, before the data, 400s), `DashboardEmptyDatabaseTest`,
  `DashboardReadOnlyTest` (no writer, no SQL, no chat or AI; nothing depends on it; one read-only snapshot; each rule
  shown to catch a violation).
- **Palette (§4.2 changed):** the mockup's slice colors failed the data-viz validator (`#1e3a8a` outside the
  lightness band, `#0f766e` below the chroma floor). Slices stay colored by rank (as planned; colors that follow the
  category can't pass, since any two would meet). Slot 1 meets every later slot around the ring, and any slot can meet
  Other's grey, so the order was searched under those constraints: `#2f55c5, #db2777, #d97706, #16a34a, #ea580c,
  #be185d`, then the mockup's two greys. Every pair that can meet passes the colorblind and normal-vision floors; the
  greys are below 3:1, so every slice is named in its legend row with its share and amount. Cash flow: income
  `#0d9488` (the mockup's second teal), spending `#3b5bdb`.
- **Overview:** KPI tiles (each change as returned, "vs Aug", toned by the sign of the API's string), a caveat when the
  month shown is partial, the data-quality tile (flags named by account), the cash-flow chart (striped partial months;
  a bar or a month's button selects that month; each month's net as text under the chart), the donut with its legend
  (Other's members listed; money in shown with a plus), at a glance (income, spending, refunds, transfers excluded,
  unmatched transfers not counted), the alerts and recurring summaries linking to their tabs. Charts turn strings into
  numbers in `components/charts/` only, for drawing.
- **Fixtures:** re-captured on a fresh sample-data backend: the two categories files changed only by the two fields
  becoming strings (18 lines); `dashboard-latest` (October, partial, no txns yet) and `dashboard-complete-month`
  (September) added.
- **For the owner:** on the sample data the default month is October 2026, partial with no txns yet, so the Overview
  opens on $0 and "−100%" against September, now with the partial-month caveat. The picker defaults to the latest month
  as planned (step 1); the trends and alerts default to the latest complete month instead.
- Not looked at in a browser yet (jsdom has no layout); step 7's Playwright run and the owner's smoke test will. The
  production build warns that the bundle is over 500 kB (Recharts); splitting it is left for the close-out.

### Step 3 notes: details panel, and the picker's default

Done: "Phase 7 step 3: details panel and GET /api/dashboard/details (UI-01, UI-04)". Backend 3,275 tests (Maven
summary), 0 failures, 0 skipped; frontend 90 tests (10 files); newman 263 requests, 0 failures.

- **Picker default** (the owner's decision at step 2's approval): the latest complete month, the latest month when
  none is complete, as the trends and alerts default; the partial month stays listed and marked. On the sample data
  the Overview now opens on September 2026.
- **`GET /api/dashboard/details?month=&categoryId= | uncategorized=true | line=INCOME`** (exactly one; `month`
  required; 400 otherwise or after the latest month with data; 404 for an unknown category), one read-only
  snapshot: `total` (signed, the figure the Overview showed), `spendingSharePercent` (the month's category row),
  `amount` (as the trends measure it), `average` (the trends' trailing 3 months, with the difference and its percent
  computed in Java: `part × 100 / whole`, one rounding, `HALF_EVEN`, the analytics rule, written out because
  `Arithmetic` is for the alerts only), `trend` (six months as magnitudes, with coverage), `topMerchants` (categories
  only) and `drillDown: {path, params, total}`. The Refunds & Rebates line is read in its REFUND bucket. Other has no
  details call: its panel lists its members, each opening its own (decision at the plan, §3.2).
- **Invariant, now through the panel:** `DashboardInvariantTest` checks, on the sample data for March to October, that
  every slice, each of Other's members and the income line equal their panel's total, which equals its drill-down's
  total run as named, whose listed rows add up to it. `DashboardDetailsTest` (fixture): each part equals its endpoint
  (category row, trends average, the six months, merchants), the refunds line, income and Uncategorized, a month
  without rows, and every refused request.
- **Found and fixed:** the dashboard's alert counts were copied with `Map.copyOf`, whose order changes from one JVM
  run to the next, so the JSON order changed between restarts (the fixture re-capture caught it; the endpoint test
  compares JSON objects, which ignores order). They now keep the alert types' order, as the alerts endpoint does;
  `DashboardApiTest.alertCountsAreInTheAlertTypesOrder`; a capture, a backend restart and a second capture gave
  identical files.
- **Panel (frontend):** the donut's legend rows and slices, Other's members and the income line open it; the open
  panel is `?detail=cat:<id> | uncategorized | income | other`, so a link or a reload opens the same panel. It shows
  "September 2026 › Groceries", the total, the share and the month against its average in words ("$189.25 (−28.4%)
  below the 3-month average ($667.19)"), the 6-month trend (line, dashed average, partial months hollow; the values
  also listed as text), top merchants and the transactions (50 a page, Show more). **The ✓:** "8 transactions · add up
  to $477.94 ✓" shows only when the rows' total, the panel's total and the figure the Overview showed are the same
  string; otherwise "add up to $470.00, not $477.94" in red, tested both ways. Focus moves to the panel's heading.
  Transaction rows open nothing yet: the transaction sheet is step 3b.
- `formatSpent` (format.ts): money out as its size, money in with a plus, for the legend, Other and the panels.
- Fixtures: three panels of September (Groceries, Health from Other, income) and the first page of each drill-down.

### Step 3b notes: transaction sheet with its source line, the drill view

Done: "Phase 7 step 3b: transaction sheet and source line, /drill (UI-04)". Backend 3,277 tests (Maven summary),
0 failures, 0 skipped; frontend 104 tests (12 files); newman 264 requests, 0 failures.

- **`statement` and `sourceLine` on the transaction response** (§13 decision 4, additive): `statement: {id,
  periodStart, periodEnd}` and `sourceLine: {number, text}` (null when the import kept neither) on
  `TransactionResponse`, so `GET /api/transactions/{id}`, the list and the correction and undo responses all carry
  them; every existing field is unchanged (`TransactionApiTest`, `CorrectionTest`, `NotTransferCategoryTest` pass
  untouched, and the re-captured fixtures differ only by the added fields). The line is local statement text like
  `rawDescription`: SEC-10's leak test (logs and problem responses) passes unchanged. `TransactionSourceLineTest`:
  on the analytics fixture, every txn's line is its file's own line at that number, and its statement is the one it
  was imported into, with the manifest's period; the list carries the same fields.
- **The sheet** (`shell/TxnSheet.tsx`): a dialog any page opens with `?txn=<id>` (the page's own parameters kept), so the
  details panel's rows open it now and the Alerts and Chat links can later. It shows the amount, date and posting
  date, account, category and who set it, the transfer state, the raw description, the statement's period and the
  statement line as printed. Close, Escape and the backdrop close it; focus goes to its heading; a malformed `?txn` is
  ignored and reads nothing. A bottom sheet on a phone, a side panel from 768 px. Read-only: corrections are step 4.
  `/transactions/:id` opens the Transactions tab with the sheet (the plan's §4.4 route, kept as a link form).
- **`/drill`** (`pages/DrillPage.tsx`, the target of a TOTAL citation in step 6): forwards only the drill-down's own
  parameters (`month`, `from`, `to`, `bucket`, `measure`, `accountId`, `categoryId`, `merchant`, `noMerchant`; anything
  else in the URL is dropped, tested), shows the filters in words, the rows' total (money out and in when both),
  the rows (50 a page, Show more; each opens the sheet), and, with `expect=<figure>`, the ✓ only when the rows' total
  is that string, otherwise the difference. Optional `label` names the total.
- Newman folder 13: a transaction's statement and source line (the first row of the details panel's drill-down).
- Choice: the sheet is a URL parameter over the current page rather than its own route, so opening a transaction
  never loses the panel, the month or the chat it was opened from.

### Step 4 notes: Transactions, review, corrections

Done: "Phase 7 step 4: Transactions tab, review queue, corrections". Backend 3,277 tests (Maven summary, no backend
change), 0 failures, 0 skipped; frontend 120 tests (14 files). No new endpoint, so no newman change (folder 8 covers the
correction API).

- **Corrections live in the transaction sheet** (`shell/TxnActions.tsx`), so a transaction can be corrected wherever it
  opens (Transactions, the Overview's panel, later Alerts and Chat): Save category (`PUT …/category`, "Also for this
  merchant's other transactions" on by default = `applyToMerchant`), Undo my category (only for a USER category;
  `DELETE …/category?removeRule=true`, the full undo), Not a transfer (a transfer, or a Transfer-kind category;
  `POST …/not-transfer`) and Undo "not a transfer" (`DELETE`, 204). Each result is said in words from the response
  ("Saved as Dining. 3 other TRADER JOE S transactions changed too, and future ones will follow. 1 other kept the
  category you set."); a refused request shows the API's own `detail`. With alert dismissal (step 5), the only writes.
- **After a write every page reads again:** a data version in the shell's context, bumped by the sheet, is in the
  Overview's, the details panel's and the Transactions lists' read dependencies. The sheet shows the transaction as
  the response returns it (or reads it again after a transfer action).
- **Transactions** (`?view=` absent): the month from the picker as `from`/`to` dates, filters Category (every category,
  or Uncategorized) and Account in the URL, 50 rows a page with Show more, each opening the sheet.
- **To review** (`?view=review`): Needs a category (every month: `uncategorized=true`, with why each waits, from the
  transaction's own `aiReviewReason` and `transferExcluded`), Transfers without a match (the queue's
  `SUGGESTED_ONE_SIDED_TRANSFER` items; the sheet offers Not a transfer and a category), and the queue's other kinds
  as counts, "resolved through the API until the upload page (Phase 8)" (§13 decision 1).
- No combined "to review" count on the toggle: it would add two counts in the UI; each section shows its own.
- Category names now load once with the shell (status, coverage, accounts, categories), for the filters, the sheet
  and `/drill`.
- Recorded for step 6 (§3.5, the owner's note at step 3b's approval): citation links are built from the citation
  object's fields only, never from text in the answer.

### Step 5 notes: Alerts and Recurring

Done: "Phase 7 step 5: Alerts and Recurring tabs". Backend 3,277 tests (Maven summary, no backend change), 0 failures,
0 skipped; frontend 136 tests (16 files). No new endpoint, so no newman change (folder 9 covers alerts, dismissal and
recurring).

- **Alerts** (`pages/AlertsPage.tsx`, `components/AlertCard.tsx`): the three months to the month picked (the endpoint's
  default length), a type filter and "Show dismissed alerts" in the URL, the counts per type as returned. Each alert:
  its type and month, its subject (a large transaction's merchant, else the category, else the merchant), what it
  found in a sentence from its own `details` strings ("$340.63 spent, $132.84 (+63.9%) above the 3-month average of
  $207.79."), its basis (observed, baseline, threshold "it fires at this or more", period, every setting in words), the
  transactions it is about (each opens the sheet), and the data flags of the months it used, named by account.
  Dismiss with an optional note (at most 200 characters, the API's limit; no note sends `{}`), Restore for a dismissed
  one; a refused request shows the API's reason; after either, every page reads again.
- **`?key=`** (the Overview's summary links; chat's alert citations in step 6) focuses that alert; a key outside the
  list's months is read on its own (`GET /api/alerts/{key}`), and one that no longer matches says "This alert no
  longer applies."
- **Recurring** (`pages/RecurringPage.tsx`): every series (Active / Ended / All in the URL, the endpoint's limit of
  100), with its account, category, typical and last amount ("the amount varies" when the last three differ), the
  next expected date as a projection or when it ended, and its charges. "See its charges" opens `/drill` built from
  the series' own fields (`series.ts`: account, merchant, first to last month, money out). `?series=<accountId>:
  <merchant>` marks one series (the Overview's recurring rows link to it; chat's series citations in step 6).
- The alert wording lives in `components/alertText.ts`, shared by the Overview's summary and the Alerts tab.

### Step 6 notes: Chat UI

Done: "Phase 7 step 6: chat UI, Phase 6 test page removed (UI-03, UI-04)". Backend 3,277 tests (Maven summary, no
backend change; nothing in the chat loop, tools, prompt or eval touched), 0 failures, 0 skipped; frontend 165 tests
(17 files). No live AI call: chat responses in tests are hand-written from the Phase 6 records (`test/chatFixtures.ts`).

- **Page** (`pages/ChatPage.tsx`, `/chat` and `/chat/:id`): the conversation list (title, question count, last
  update; New conversation), the conversation, the question box (1,000 characters, counted). The first question
  creates the conversation and moves to its URL; follow-ups continue it. Delete asks first ("Delete" / "Keep it"),
  then returns to the list. On a phone, the list or one conversation (with "‹ All conversations"); from 900 px, side by
  side. `ChatPage.tsx` and `ChatPage.test.tsx` of Phase 6 are deleted.
- **Answers as text** (`chatText.ts`, §5.2, §13): never HTML or markdown. Emphasis pairs at word edges are stripped
  (`**`, `*`, `***`, `_`, `__`, nested too); a lone or in-word marker (`SQ *TNK`, `snake_case`) and list markers
  (`* item`) stay; line breaks stay. `[n]` becomes a link to citation `n` below only when the turn has citation `n`.
- **Links from citation objects only** (§3.5, the owner's rule): `citationLinks.ts` builds every target from the
  citation's fields: a transaction opens the sheet by `txn.id` (a GONE one has no link), a total opens `/drill` with
  each drill-down's own `params`, its `label` and `expect` = its `total.net` (so the ✓ shows when the rows still add
  up), an alert opens `/alerts?month=&key=`, a series `/recurring?series=` and its charges in `/drill`. Tested with an
  answer holding `[click here](/drill?bucket=EXPENSE&evil=1)`, a bare path and `[9]`: every link on the turn is one of
  the citation targets, and none carries the text's parameters.
- **Each turn:** the fallback label ("The model's answer couldn't be checked, so here are the figures it used."), a
  limit in words ("…reached the limit on cost for one question."), the model, the estimated cost or "no price", and
  how many figures were checked. A stored turn that failed says so.
- **Problems in words:** `ai-off`, `chat-budget` (with the daily cap read from `/api/chat/usage`), `chat-busy`,
  `turn-in-progress`, `ai-unavailable`, else the API's `detail`; the question stays in the box. With AI off the box is
  replaced by the reason, and conversations stay readable.
- **A race fixed before it shipped:** the first question navigates to the new conversation's URL, which would read the
  conversation back and could replace the answer with an older read; the page skips that read for a conversation it
  created.
- **Test stability:** the suite runs its files in parallel; a few tests checked a URL or a request before the async
  step behind it finished. They now wait for it, and Testing Library's async timeout is 5 s (`test/setup.ts`). Six full
  runs in a row passed.

### Step 7 notes: the phone pass at 375 px

Done: "Phase 7 step 7: phone layout at 375 px, Playwright layout check (UI-07)". Backend 3,277 tests (Maven summary, no
backend change), 0 failures, 0 skipped; frontend 165 tests (17 files); layout check 36 passed, 2 skipped (phone-only
checks in the desktop run).

- **Playwright** 1.64.0 (pinned; Chromium only, §13 decision 5), `npm run test:layout`, run beside `npm test` from now
  on. It starts the Vite dev server and answers every `/api` request from the unit tests' fixtures (`e2e/mockApi.ts`),
  so it needs no backend. Vitest only collects `src/**/*.test.*`; the fixtures' JSON imports carry `with { type: 'json'
  }`, which Node needs to load them outside Vite.
- **Checked on 16 screens** (Overview in a complete and a partial month and with the cloud banner, the details panel for
  a category, Other and income, the transaction sheet, Transactions, To review, Alerts, Recurring, `/drill`, the chat
  list and a conversation, Goals, Settings), at 375 × 812 and 1280 × 900: no horizontal scroll; on a phone the bottom
  bar and no header tabs, on desktop the reverse; on a phone every button, link, select and input at least 44 px tall,
  except links inline in running text and checkboxes (whose label is the target), as WCAG 2.5.8 allows. A test shows
  the measure catches a 20 px button and skips an inline link. Also: More reaches the other tabs; the transaction sheet
  is a bottom sheet on a phone and a 420 px side panel on desktop.
- **Found by the check:** a chat citation button at 34 px (fixed).
- **Found by looking at screenshots** (the checks pass but say nothing about how a screen reads): the Overview's
  recurring amounts and the cash-flow nets broke mid-number at 375 px (numbers now never wrap; the six months sit in two
  rows of three below 480 px, as do the trend's values); the cash-flow bars animated from zero (animation off, as for
  the donut and the trend); the trend's x-axis dropped and clipped its end labels (every tick shown, the axis padded);
  a heading focused by code showed a focus ring (none for `h1`/`h2` with `tabindex=-1`, which aren't controls).
  Screenshots stay out of the repo until the close-out's README.

### Step 7b notes: the Goals tab shell

Done: "Phase 7b: Goals tab shell (GOAL-06, tab only)". Backend 3,277 tests (Maven summary, no backend change),
0 failures, 0 skipped; frontend 167 tests (18 files); layout check 36 passed, 2 skipped.

- **`pages/GoalsPage.tsx`**: the tab, "No goals yet", and three sentences on Layer 2: goals are set by you (the chat
  may suggest one; only your save creates it), checked each month against your own totals, the same figures as the
  Overview, with every gap explained by the transactions behind it; "Planning from your own data, not financial
  advice." No goal data, endpoint, table or control; a test shows the page reads nothing about goals and has no
  button, field or link. The mockup's report card, goal cards, detail panel and advisor note are Layer 2.
- Every tab is built, so step 0's placeholder page and the tabs' step numbers are removed; the routes follow the tab
  order. The layout check now waits for "No goals yet".

### Step 7c notes: the Midnight restyle (mockup v2)

Done: "Phase 7 step 7c: Midnight restyle, dark theme, menu shell (UI-01, UI-05, UI-07)". Backend 3,277 tests, 0 failures, 0 skipped (Maven
summary, no backend change); frontend 196 tests (20 files); layout check 43 passed, 1 skipped (a phone-only check in
the desktop run).

- **Mockup v2** is the "Finalysis UI Directions" canvas, artboard B · Midnight (§1); the brief's differences are in
  §1.2. No data-layer, API, chat or eval change.
- **Theme** (`theme.css`, `theme.ts`): the two sets of §4.2 on `:root[data-theme]`, dark by default; `main.tsx`
  applies the stored choice before the first render. Storage that throws gives dark and a switch that still works for
  the visit. **Contrast:** every dark pair passes; in light, income, spend and the s3/s5/s6 labels failed and were
  darkened (§1.2); white on the dark accent (3.27) became the page colour. `theme.test.ts` applies both sets, checks
  each token, the switch, persistence, unreadable storage, and every text/background pair at 4.5:1 with `color-mix`
  resolved.
- **Shell:** `Navigation.tsx` (header tabs, bottom bar, More) is replaced by `TopBar.tsx` and `Sidebar.tsx`. The menu
  is a modal dialog rendered only while open (fixed, inside a clipped layer, so it can't widen the page). Focus goes to
  the current tab's link, Tab cycles inside, and the Layout returns focus to the menu button whenever it closes. The
  backdrop is a pointer convenience, not a second "Close menu" button. The month picker gained ‹ › (stepping through
  the months the picker lists, disabled at the ends); on a phone it takes the top bar's second row, and below 420 px
  the brand name is hidden visually so the tab name fits.
- **Banner:** the same component and SEC-01 wording, as a strip under the top bar; the tests still check the wording.
- **Overview:** the hero (`Complete month · N accounts` or `Partial month · …`, the month as the page's `h1`, and the
  lead sentence or the partial-month note), KPI cards with coloured edges, the cash flow as a `ComposedChart` with the
  net line in s3 (net drawn with its sign, for drawing only, in `components/charts/`), the donut legend in two
  columns, the newest alerts as cards with a top edge by type, recurring as a list. `leadSentence.ts` joins only
  formatted API strings; a test holds every figure in it to one the API returned, across the full, no-last-month,
  down, negative-net and large cases. The page's `h1` is "Overview" until the month loads.
- **Other tabs:** no markup change; the shared CSS changed (surfaces, borders, 16/10 px radii, Manrope, tabular
  figures) and the remaining hard-coded colours became tokens.
- **Layout check:** the "bottom bar vs header tabs" assertion became "menu button shown, no tabs until opened"; the
  More test became a menu test in both themes (fits inside 375 px, 44 px targets with it open, a tab reached, focus
  back on the menu button), plus Escape, and the Overview with the details panel and the banner in the light theme.
- **Found by looking at screenshots:** on desktop the cash-flow card stretched to the donut card's height, leaving an
  empty block; the grid now aligns cards to the top.

### Step 7d notes: the Import tab (UI-02)

Pulled forward from Phase 8 by the owner (2026-10-26), with the plan's answers: (1) a `statementId` filter on
`GET /api/transactions` with a clearable "Statement {period}" chip; (2) statements grouped by account from the existing
per-account endpoint; (3) the last four a required field, shown as `···1234` in the picker, with a test that it never
reaches an AI payload; (4) a minimal CSV mapping step, the page's third write. Done: "Phase 7 step 7d: Import tab
(UI-02)". Backend 3,280 tests, 0 failures, 0 errors, 0 skipped (Maven summary); frontend 225 tests (21 files);
layout check 61 passed, 1 skipped (a phone-only check in the desktop run); newman 266 requests, 0 failures.

- **Backend, one read addition:** `statementId` on `GET /api/transactions` (`TransactionFilter`, `TransactionQuery`):
  the rows the statement owns, combinable with the other filters; an unknown id matches nothing, a non-number is a
  400 naming it (`TransactionApiTest.filtersByStatement`). Newman 13 "Transactions: one statement's rows" checks that
  every row belongs to the statement and the page's `total` equals the statement's `transactionCount`.
- **Last four never leaves the machine:** `LastFourOutboundTest` (fake models, AI on) creates accounts with
  `POST /api/accounts` as the form sends them, imports a sample QFX with the page's multipart request, adds synthetic
  rows carrying each last four (`CARD ENDING 2001`, `XXXX2001`, `KIOSK 7364`, `*7364`), and runs
  `POST /api/categorization/run`: no prompt and no embedding input contains a last four, an account name, the
  institution or the account number. `LastFourChatPayloadTest` does the same through a chat turn whose question names
  both accounts by name, institution and last four, with tools reading every account and row. The existing payload
  tests already checked fixtures; these follow the Import page's own path.
- **The tab** (`/import`, between Transactions and Alerts; the menu has eight tabs): `pages/ImportPage.tsx` with
  `components/import/` (`AccountPicker`, `FileDrop`, `ImportResult`, `StatementsList`, `ReviewSections`,
  `importText.ts`). Writes, all through `api.ts` `imports`: add an account, import a file, save a CSV mapping, and the
  review and delete actions that already existed (statement review and delete, upload dismissal, transfer link,
  join, dismiss join, confirm, unlink).
- **Account picker:** `GET /api/accounts` (the shell's), each as `name ···lastFour`, closed ones marked. "Add an
  account" posts `{name, institution, type, lastFour}`; the API validates, and its field errors are shown named as the
  form labels them. The new account is chosen (`?account=`), and the shell reads its accounts again.
- **File:** a drop zone and a file button (`.ofx,.qfx,.csv,.txt,.pdf`, the backend's list), one file at a time (two
  dropped are refused), name and size in bytes (`formatBytes`: grouped digits, no rounding into KB). Image files
  aren't accepted by the API (OCR is Phase 8); a scanned PDF comes back as an unparsed upload, `NO_TEXT_LAYER`.
- **Import:** one multipart part, `file`, to `POST /api/accounts/{id}/imports`; an indeterminate progress bar while it
  runs (`fetch` reports no upload progress, and the parse is the slow part). Results, each as returned:
  - 201: period, rows parsed, new transactions, duplicates skipped, status and balance check, every warning
    verbatim; links to its transactions, the review queue and "Needs a category". **"Categorize now" isn't offered:**
    `POST /api/categorization/run` runs over every transaction, not one statement, and the rules already ran with the
    import (AI in the background only when `run-after-import` is on).
  - 202: the upload's `detail` and its reason in words, pointing to the review queue.
  - 409 `already-imported`: a notice (not an error) with the API's title and detail and a link to that statement.
  - 422 `mapping-required`: the preview (`POST …/imports/preview`, which writes nothing) shows the file's first rows
    and the suggested mapping with each field's confidence; "Use this mapping" saves it unedited
    (`PUT /api/accounts/{id}/csv-mapping`) and imports the same file again. The problem's `detail`, which tells an API
    user which endpoints to call, isn't shown: the page makes those calls.
  - 422 `mapping-mismatch`: the problem as returned, with the same preview, and **no** button: replacing a mapping
    already used can change fingerprints (the API's mapping lock), so it stays an API action.
  - Anything else (422 parse-error, 413, 415): the problem's title, detail and line, as an error.
- **Found while capturing:** for a card CSV with more payments than purchases (the transfers edge-case file), the
  suggester guesses `sign: INVERTED`, and the import would turn payments into money out. The mapping step shows the
  sign in words ("Money out is printed positive: every amount is flipped") with its confidence ("guessed, check
  it"), so the guess is visible before it is saved; the page can't edit it. Worth watching in the smoke test.
- **Statements:** one read per account, grouped by account, each group newest period first as returned. A row is its
  period (a link to `/transactions?statement={id}`), its transaction count, rows in the file, status (", accepted"
  once reviewed), balance check and import date. Delete asks first: "Delete the statement {period} of {account}? This
  removes the {n} transactions it imported, and any transfer links on them. The file can be imported again
  afterwards." Then the response is said ("Statement deleted: 8 transactions and 4 transfer links removed.").
- **Every page reads again** after an Import-page write: `dataChanged({ shell: true })` also reloads the shell's
  accounts, coverage and the picker's months (a reload that fails keeps what the shell had).
- **Review queue** (`#review`), each section with the queue's own count: statements to review (warnings verbatim;
  Accept as it is, or Delete with the same question), uploads not recognized (reason; Dismiss), and transfer matches:
  candidate groups ("Link these two" per pair, with both sides and the days apart), competing candidates (Keep this
  link, Unlink), possible matches (Join as one transfer, Not the same transfer). Every transaction opens the sheet,
  where "Not a transfer" and a category already live. On the Transactions tab, "resolved through the API until the
  upload page (Phase 8)" became a link to `/import#review`.
- **Transactions' statement filter:** `?statement=` replaces the month's dates with `statementId` (a statement can
  span months), shows "Statement {period} · {account}" as a chip from `GET /api/statements/{id}`, and ✕ clears it.
- **Privacy line** under the drop zone: "The file is read on this computer. With cloud AI on, only merchant names and
  amounts leave it, for categorization." (the banner's wording family, SEC-01), tested word for word.
- **No arithmetic:** the guard covers `components/import/`; counts are shown as returned, the "nothing to decide"
  check uses `every` on the lists rather than adding their lengths, and plurals choose by `=== 1`.
- **Fixtures:** `capture-fixtures.mjs` reads every sample account's statements, then runs an import pass on new
  "Fixture Bank" accounts with the `edge-cases/transfers` CSVs, `csv-card-refund.csv`, `csv-windows-1252.csv` and the
  unrecognized and no-text PDFs: 201 (reconciled; NEEDS_REVIEW from request balances that don't add up), 202 (both
  reasons), 409, 422 (mapping required, mismatch, parse error), the preview and saved mapping, a delete, and the
  review queue with a candidate group, then a competing candidate (the card statement deleted and imported again),
  then a possible match (that link unlinked, both sides marked one-sided). `importedAt`, `resolvedAt` and
  `confirmedAt` are now normalized; two captures gave identical files and the earlier fixtures didn't change.
  Captured on a throwaway Postgres (port 55432) and backend (18080) after the README's newman folders (266 requests,
  0 failures), both removed afterwards.
- **Layout check:** `/import` with each transfer kind, the Transactions statement chip, and, by interaction, a result
  with warnings, the mapping step (its wide preview scrolls inside the card), the already-imported notice, the
  add-account form and the delete question. The target check now measures a visually hidden input by its label (the
  file button), with a case in its self-test. Found by looking at screenshots: the account select overflowed its card
  at 375 px with a long account name; it now fills the column.

### Step 7e notes: Corgi, cost display, import follow-ups

From the owner's brief (2026-10-26), read beside the "B · Chat with Corgi" artboard of the "Finalysis UI Directions"
canvas (the brief wins where they differ). Plan answers: (1) "merchants indexed" only in the Categorize-now summary,
since the import's AI runs in the background and its response can't know the count; (2) Settings keeps the model ids
as facts under Corgi's roles; (3) newman on a throwaway backend. Done: "Phase 7 step 7e: Corgi, cost display, import
follow-ups (UI-03, CHT-01, CAT-02, ING-01)". Backend 3,289 tests, 0 failures, 0 errors, 0 skipped
(Maven summary); frontend 265 tests (24 files); layout check 69 passed, 1 skipped (the phone-only check in the desktop
run); newman 266 requests, 0 failures.

- **Corgi is a display name, UI only.** One constant, `AI_NAME` in `corgi.ts`, with the tagline and the path badge;
  code identifiers, the system prompt (`chat-9`), the loop, tools, grounding, caps and eval are untouched (the Corgi
  prompt is a Phase 8 step). `CorgiAvatar` draws the initial on the accent (`--on-accent` text, already in the contrast
  check) and can take an image later. No gradient (the artboard's), no pun, no emoji, no new tokens.
- **Chat header:** avatar, "Corgi", "Reads your books. Never changes them.", and the path badge from
  `GET /api/status`, which already carried `provider`: CLOUD → "Cloud · Bedrock" in s3 ink, LOCAL → "Local · {provider}"
  in the income colour (a provider the UI doesn't know shows as configured); none with AI off, where the AI-off note
  stays. `AiStatusTest` checks the provider per mode; `AwsProfileWiringTest` already checked `bedrock` end to end. The
  conversation's title, its total and Delete sit under the header.
- **Each answer:** the small avatar and "Corgi" above it; under it "Figures computed by Finalysis, not by the model · n
  checked" with a calculator icon when `grounding.figuresChecked > 0` (it replaced "n figures checked" in the meta
  line), then the model and cost. A failed turn has no Corgi label: there is no answer.
- **While answering:** the question and "Corgi is thinking…" with the avatar (`PendingTurn`), until the answer or the
  problem replaces them. The input: placeholder "Ask Corgi about your spending" (the label stays, visually hidden),
  and under it "Corgi reads your data and never changes it. It can't give financial advice." with the counter
  ("0 / 1,000").
- **Categorizer naming:** the sheet's "suggested by Corgi", the review reasons "Corgi wasn't sure" and "Corgi's
  answers were invalid", Settings' "Corgi · chat" and "Corgi · categorization" (the search embedding keeps "Search
  (embedding) model": it isn't Corgi answering). "rule" and "you" stay. `corgiNaming.test.tsx` renders each place and
  checks its text and tooltips for "AI", "the model" and model names. Not renamed: the cloud banner and the import
  privacy line (SEC-01's wording), Alerts' "no AI", and chat's AI-off and service messages (about the service).
- **Conversation totals:** `totalCost` on the list and the detail, `sum(chat_turn.estimated_cost)` in the store's
  queries (six decimals; unpriced turns add nothing; null when no turn has a price). Summary calls aren't included:
  they are in the ledger, which has no conversation id (V22). The list shows "3 turns · $0.03 · Oct 23"; the header
  "This conversation: $0.03 estimated". After each answer the page reads the conversation again for its total (and
  the list and today's usage): the turn's own cost is never added to anything. `ConversationCostTest` (Testcontainers).
- **Cost rounding, one exception to format.ts's rule:** costs stay exact in the API (six decimals). `formatCost` shows
  them to the cent, half up, on the digit string (never a binary number), "under $0.01" for a cost above zero that
  rounds to nothing; the per-turn line keeps the exact estimate as before. It is the only rounding in `format.ts`,
  documented there and tested case by case; costs are estimates from list prices and labelled so.
- **Budget and month:** the chat page's left column reads `GET /api/chat/usage`: "$0.21 of $1.00 today · resets at
  midnight" ("· no daily limit" when the cap is off). The endpoint gains `month`, `spentThisMonth` (every ledger kind
  since local midnight on the first) and `questionsThisMonth` (TURN rows, failed ones included); Settings shows
  "This month (October 2026): $1.23 · 42 questions · estimated from list prices". The ledger is purged with
  `retention-days` (default 90), so a retention under a month would undercount; said in the record's comment.
- **Local:** with mode LOCAL every cost line (turn, conversation, list, budget, Settings) shows "$0.00 · local"
  (`costText`). No local profile exists yet; the tests use a LOCAL status fixture.
- **Categorize now** (`components/categorize/`): on the Import result and on "Needs a category", while the
  uncategorized count is above zero (the page's own total on Transactions; one `size=1` read on the Import result). It
  posts `POST /api/categorization/run`, shows a progress bar, then the backend's `summary` in words: "97 categorized,
  34 to review, 1 held back for privacy, 4 model calls" ("rules only (Corgi is off)" with AI off), "126 merchants
  indexed for search" when the refresh embedded any, and a plain line when Corgi stopped at its cap or on an error.
  Then `dataChanged({ shell: true })`: every page reads again, and the summary stays. "Held back" is a button with the
  redaction explanation as its tooltip and its description, shown inline on tap (UI-07: no hover-only text). This
  replaces 7d's reason for not offering it: the copy says it covers every uncategorized transaction, not one statement.
- **Run summary (backend):** `RunResponse` gains `searchIndex` (the refresh's counts, logged until now) and `summary`:
  `categorized` = rule + memory + AI, `toReview` = `txn` rows without a category (the same rows
  `?uncategorized=true` counts; `UncategorizedCount`), `heldBack` = the AI stage's `redactionBlocked`, `modelCalls` =
  its `calls`, `merchantsIndexed` = merchant documents the refresh embedded (category names not counted;
  `MerchantIndexRefresh.refreshCounted`, `refresh()` unchanged for its other callers).
- **CSV sign choice:** when the suggestion's sign confidence is GUESSED, the preview carries `signExample`, the first row
  with a non-zero amount read by `CsvAmount` with each sign (amount without its sign, `asIs` and `inverted` as
  MONEY_OUT/MONEY_IN), so the page parses nothing. The page shows "Purchases are negative in this file" and "Purchases
  are positive in this file", each with that row read its way ("$4.50 at SQ *CORNER BEAN CAFE → money out"), in place
  of "Use this mapping"; the suggested mapping's sign row says "You choose below…" instead of the guess. The PUT body is
  the suggestion with the chosen `sign`. A matched sign (debit/credit columns) keeps the single button. An error
  response carries no example (no rows, SEC-10). This answers 7d's finding (the transfers card file guessed INVERTED).
- **Fixtures:** two new captured files, `import-preview-card.json` (the transfers card CSV's preview, sign guessed, with
  its example) and `categorization-run-off.json` (the run with AI off, last in the script); AI-on runs and chat costs
  are hand-written in the tests. Every earlier fixture came out byte-identical. Captured on a throwaway Postgres
  (port 55432) and backend (18080, the packaged jar, AI off) after the README's newman folders (266 requests, 760 assertions, 0 failures), both
  removed afterwards; the dev database was not touched.
- **Layout check:** the chat header with each badge, the figures line and the input note; the two sign buttons with
  their examples; Categorize now with its summary and the held-back explanation open. "Held back" is a 44 px target.
  Screenshots at 375 and 1280 looked as the artboard, without its gradient.

### Step 8 notes: close-out

Done: "Phase 7 step 8: close-out". Backend 3,289 tests, 0 failures, 0 errors, 0 skipped (Maven summary); frontend 265
tests (24 files); layout check 69 passed, 1 skipped (the phone-only check in the desktop run); newman 266 requests,
0 failures; fixtures re-captured with an empty diff.

- **The 7e frontend failures, for matching a recurrence.** In step 7e, the first `npm test` after a full
  `caffeinate -i ./mvnw clean verify` reported **7 failed tests in 7 of 24 files** (258 passed of 265). Their names
  were not kept: that run's output was filtered to the summary lines, and Vitest's result cache was overwritten by the
  next runs. The three runs that followed passed 265 of 265. A reproduction in step 8 ran `npm test` three times
  during a full Maven verify (which itself passed, 3,289 tests): 265 of 265 each time, so it was **not reproduced**.
  Most likely timeouts under load (`findBy…` waits a second by default); unconfirmed. From now on every run writes
  `frontend/test-reports/vitest.json` (Vitest's JSON reporter, git-ignored, outside Playwright's `test-results/`,
  which each layout run empties), so a failing run keeps the names of its failed tests. On a recurrence: copy that
  file before the next run and record the names here.
- **Fixtures re-captured: empty diff.** A throwaway Postgres (port 55432) and the packaged backend on port 18080
  (AI off), the README's newman folders (266 requests, 760 assertions, 0 failures), then `npm run capture-fixtures`:
  `git diff` over `src/test/fixtures/` is empty.
- **Newman:** the README's folders (1–6, 8, 9, 12, 13) twice on fresh databases, 266 requests and 0 failures each.
  Folders 7 (your own files), 10 (lookups) and 11 (live AI) are manual, as the README says, and weren't run.
- **README:** a new "The app" section, one subsection per tab with a screenshot on the sample data
  (`docs/screenshots/`, 1280 px, dark theme, plus the Overview at 375 px), and "Phone access (UI-08)". The screenshots
  were taken from a second throwaway database holding the newman sample data only (the capture's import pass adds
  "Fixture Bank" accounts), through a temporary Vite server on port 5175 proxying to 18080; the chat screenshot is the
  tests' fixture with cloud AI shown on, said in its caption, since the sample-data backend runs with AI off. The
  temporary config and script, both throwaway services and the jar copy were removed; the dev backend on 8080 and the
  dev database weren't touched. The API table gains `GET /api/dashboard`, `/api/dashboard/details`,
  `/api/chat/usage`, the `statementId` filter and the transaction's `statement` and `sourceLine`; the old "chat page is
  a Phase 6 test surface" text is replaced. The Tests block names `test:layout` and the JSON report.
- **Requirements v1.76:** UI-01, UI-02, UI-03, UI-04, UI-05 and UI-07 Done, each with its evidence; UI-08 and GOAL-06
  stay Planned with their Phase 7 parts noted. Roadmap: Phases 7 and 7b Done. CLAUDE.md: the current phase line.

### Phase summary

Phase 7 turned the API into the app: eight tabs (Overview, Transactions, Import, Alerts, Recurring, Chat, Goals,
Settings) from mockup v2, dark and light, one layout from 375 px up. Every figure on screen is a string the API
returned; where the mockup needed a figure no endpoint had, the backend computes it (`/api/dashboard`,
`/api/dashboard/details`, conversation totals, the run summary). Every Overview figure opens the transactions that add
up to it with a ✓, every transaction its statement line, every chat citation the same targets. Corgi names the AI in
the UI; chat's behavior is as evaluated in Phase 6. New backend: four reads and additive fields, no migration, no
change to analytics counting or to chat.

### Known limitations

- **Costs are rounded to the cent in lists, budget and Settings' month** (`formatCost`), while Settings' "Spent today"
  and each answer's line show the exact six-decimal estimate (the figure the daily cap checks). Both are estimates
  from list prices.
- **This month's chat spend undercounts if `retention-days` is under a month** (the ledger is purged with it).
- **No streaming:** an answer appears whole, with "Corgi is thinking…" until then.
- **Categorize now runs over every uncategorized transaction**, not one statement (the endpoint has no scope), and it
  holds the request until the run ends.
- **Settings is read-only;** AI mode, models, caps and thresholds change in configuration only.
- **The import tab takes one file at a time**, and scanned images wait for OCR (Phase 8).
- **No phone access** until Phase 8's login and HTTPS (UI-08).
- **The 7e frontend failures** (step 8 notes) weren't reproduced; their names weren't kept.

### Backlog (for later phases)

- **Phase 8:** the Corgi system prompt (the owner's brief: a Phase 8 step; chat stays on `chat-9` until then);
  h-u10 (figures where a refusal was expected) and h-x06 (a tool for a share of income), both with new dev questions;
  login and HTTPS for phone access (UI-08); OCR for scanned statements.
- **Phase 9:** the holdout guard per set rather than per model, with a decision on a second holdout run.
- **Phase to be assigned:** ANA-02's fixed vs. discretionary (needs a setting the user edits); the monthly summary
  (ANA-05) on the Overview; Settings edits.
- **Observations kept:** Haiku 4.5 skips the search fallback about half the time (budget option only); the cost
  estimator is conservative, and Settings shows the real spend beside the caps.

### Requirement mapping

| Requirement | Step(s) | Evidence |
|---|---|---|
| UI-01 | 2, 3, 7c | dashboard tests, `DashboardMatchesEndpointsTest`, Overview and details tests, hero and lead-sentence tests |
| UI-03 | 6, 7e | Chat tests; Corgi header, badge, figures note, costs (`ChatPage.test.tsx`), `corgiNaming.test.tsx`, `ConversationCostTest`, `ChatUsageApiTest`, `test:layout` |
| UI-04 | 3, 3b, 6 | `DashboardInvariantTest`, `TransactionSourceLineTest`, invariant UI test, citation link tests, newman 13 |
| UI-05 | 1, 7c | banner tests (wording, `CLOUD` and `OFF`), unchanged under the 7c strip |
| UI-07 | 7, 7c | `test:layout` (the menu open at 375 px in both themes), menu tests |
| UI-08 | 1 (decision and binding) | `ServerAddressTest`; stays Planned (§9) |
| GOAL-06 (tab shell) | 7b | Goals test |
| UI-02 | 7d | `ImportPage.test.tsx`, the Transactions statement-filter tests, `TransactionApiTest.filtersByStatement`, `LastFourOutboundTest`, `LastFourChatPayloadTest`, `test:layout` (Import screens), newman 13 |
| ANA-02 | none | notes only (§13): ANA-02 waits |

---

## 12. Open questions (answered at approval, §13)

1. **UI-02, the upload page** (account picker, import, the rest of the review queue: statements, unparsed uploads,
   transfer candidates). The roadmap puts it in Phase 7; the brief lists only the categorize and transfer writes.
   *Proposed:* wait, and say so in UI-02's note; imports stay through the API and Postman. Alternative: a step 4b with
   the import and the Phase 3c review actions (more writes in the UI).
2. **ANA-02, fixed vs. discretionary.** The Phase 5 plan moved it to Phase 7 "where the UI can classify categories", but
   classifying means a setting the user edits, and this phase edits no settings. *Proposed:* wait (ANA-02 stays
   Planned, note updated). Alternative: a seeded `category.spending_class` (migration) and a read-only split on the
   Overview.
3. **UI-08.** No phone access in Phase 7, backend bound to localhost, login and HTTPS with Phase 8 (§9)? *Proposed.*
4. **Source line.** Add `statement` and `sourceLine` to `GET /api/transactions/{id}` (§3.3)? *Proposed.* Alternative: a
   separate endpoint, leaving the Phase 4 response as it is.
5. **Layout test.** Playwright (Chromium only, a browser download of about 150 MB, a second test command) for the
   375 px check (§7.2)? *Proposed.* Alternative: Vitest browser mode (also a real browser, one command), or manual
   checks in the smoke test only.
6. **Holdout guard** (§10): wait for Phase 9? *Proposed.* Alternative: step 8a, keyed by set and model, with CLAUDE.md's
   "never run again" amended to "once per model".
7. **The donut's refunds** (§3.1): a category whose refunds exceed its charges in a month goes into Other's members
   rather than a negative slice. Agreed?

---

## 13. Decisions at approval (2026-10-23)

1. **UI-02 waits.** It is the first Phase 8 item; imports stay through the API and Postman until then.
   *Changed 2026-10-26:* the owner pulled UI-02 forward as step 7d (the Import tab), before the smoke test; the UI
   fixes the smoke test finds are step 7e.
2. **ANA-02 (fixed vs. discretionary) waits**, phase to be assigned; ANA-02 stays Planned.
3. **UI-08:** the backend binds to `127.0.0.1` now (step 1); no phone access in Phase 7; login and HTTPS with Phase 8.
4. **Source line:** `statement` and `sourceLine` added to the existing `GET /api/transactions/{id}` response, additive.
5. **Layout test:** Playwright, Chromium only (step 7).
6. **Holdout guard** waits for Phase 9.
7. **Donut refunds:** agreed; a category whose refunds exceed its charges in a month goes into Other's members.

Additions:
- **Step 3 is split:** step 3 is the details panel, trend, top merchants and the ✓ check; step 3b is the transaction
  sheet with the source line and the `/drill` view (§11).
- **Chat display:** no HTML rendering, but emphasis markers (`*`, `**`, `_` at word edges) are stripped for display;
  line breaks and list markers stay; string handling only, with a test (§5.2).
