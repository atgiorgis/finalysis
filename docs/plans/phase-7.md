# Phase 7 plan: dashboard and chat UI

Status: **approved** (2026-10-23) with the answers under "Decisions at approval" (§13), which override anything older
in this file. Steps 0 and 1 done (step notes); next: step 2. Branch `v1`, starting after tag `phase-6-done` and the roadmap commit (`ab9d5db`).
Requirements as of `docs/requirements.md` v1.72.

Scope: UI-01 (dashboard), UI-03 (chat panel), UI-04 (every total and transaction traceable to its rows and source
line), UI-05 (the cloud-AI banner, in full), UI-07 (phone layout), UI-08 (the mobile-access decision, §9), and the
GOAL-06 tab shell (Phase 7b). The UI replaces the Phase 5 status page and the Phase 6 chat test page.

Out of scope:
- **Any change to chat behavior.** The chat loop, tools, prompt, grounding, citations, memory, scoring and eval are
  frozen as evaluated in Phase 6. If a UI need seems to require a chat change, the step stops and asks.
- **Any change to how analytics counts.** New endpoints bundle and compare what the Phase 5 services return; no
  bucket rule, average rule or alert check changes.
- **Editing settings** (AI mode, models, caps, thresholds): shown read-only in Settings.
- **Goal logic** (GOAL-01 to GOAL-07 beyond the empty tab): Layer 2.
- **The monthly summary** (`POST /api/chat/summaries`, ANA-05) on the dashboard: not in the mockup; a later phase can
  add it.
- UI-02 (upload page): the first Phase 8 item (§13). ANA-02 (fixed vs. discretionary): waits, phase to be assigned
  (§13).

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

---

## 2. Every figure on screen and where it comes from

"New" marks the reads this phase adds (§3). Everything else exists today.

### 2.1 Shell

| Element | Source |
|---|---|
| Month picker: the months with data, default the latest month | `GET /api/analytics/coverage` (`latestMonth`, `latestCompleteMonth`, per-account spans) |
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

These and alert dismissal are the only writes in the UI.

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
- `@fontsource/inter` (400, 500, 600, 700);
- dev: `@testing-library/user-event`. Step 7 adds Playwright for the layout check (§7.2, question 5).

Exact versions, pinned in `package.json`; nothing loaded from a CDN at runtime.

### 4.2 Theme tokens from the mockup

CSS custom properties in `src/theme.css`, named by role, values from the mockup:

| Token | Value | Use |
|---|---|---|
| `--bg` | `#F3F5F8` | page |
| `--surface` | `#FFFFFF` | cards |
| `--surface-muted` | `#EEF1F5` | table heads, chips |
| `--border` | `#E2E7EE` | card and row borders |
| `--text` | `#17202B` | body |
| `--text-muted` | `#5B6573` | labels, captions |
| `--nav` | `#1B2B44` | header |
| `--accent` / `--accent-strong` | `#1D4ED8` / `#1E3A8A` | links, focus ring, selection |
| `--income` | `#0F766E` | income bars, positive change |
| `--spending` | `#3B5BDB` | spending bars |
| `--warning` / `--warning-bg` | `#B45309` / `#FDF0DC` | partial months, spikes, the banner |
| `--danger` / `--danger-bg` | `#B91C1C` / `#FDECEC` | duplicates, failed reconciliation |
| `--slice-1` … `--slice-6` | `#1E3A8A`, `#3B82F6`, `#D97706`, `#0F766E`, `#EA580C`, `#7C3AED` | donut, by rank |
| `--slice-other` / `--slice-uncat` | `#94A3B8` / `#CBD5E1` | Other, Uncategorized |

Radii 12–14 px on cards, 999 px on pills; spacing on a 4 px scale (4, 8, 12, 16, 20). Contrast checked at 4.5:1 for
text (the muted grey on white passes). A slice's color follows its rank in the month, so the same category can change
color between months; the legend always names it (no meaning carried by color alone).

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

- navigation is a bottom bar with Overview, Transactions, Chat and More (Alerts, Recurring, Goals, Settings), each
  target at least 44 px;
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
| 8 | Close-out: re-capture fixtures (empty diff), newman all folders, README (a section per tab with screenshots on the sample data, the new endpoints, UI-08), requirements (UI-01, UI-03, UI-04, UI-05, UI-07, GOAL-06 tab; UI-08 stays Planned), CLAUDE.md current phase, tag `phase-7-done`. | none | full runs |

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

### Requirement mapping

| Requirement | Step(s) | Evidence |
|---|---|---|
| UI-01 | 2, 3 | dashboard tests, `DashboardMatchesEndpointsTest`, Overview and details tests |
| UI-03 | 6 | Chat tests |
| UI-04 | 3, 3b, 6 | `DashboardInvariantTest`, `TransactionSourceLineTest`, invariant UI test, citation link tests, newman 13 |
| UI-05 | 1 | banner tests (wording, `CLOUD` and `OFF`) |
| UI-07 | 7 | `test:layout`, phone-structure tests |
| UI-08 | 1 (decision and binding) | `ServerAddressTest`; stays Planned (§9) |
| GOAL-06 (tab shell) | 7b | Goals test |
| ANA-02, UI-02 | none | notes only (§13): UI-02 is the first Phase 8 item; ANA-02 waits |

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
