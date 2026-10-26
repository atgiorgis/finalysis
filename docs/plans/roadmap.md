# Finalysis Roadmap

**Version:** 2 · **Date:** 2026-10-23 · **Requirements:** `docs/requirements.md` v1.72

This file supersedes "Finalysis Roadmap V4", which was kept outside the repo. Requirement ids and phase numbers are
unchanged; the requirements file stays the source of truth for each requirement's wording, phase and status.

## How to read this

The roadmap has four layers, and each one builds on the one below it:

1. **Your books:** know where your money went.
2. **Your plan:** decide where it should go.
3. **Your education:** understand the choices.
4. **Your accountant:** act on the choices.

Nothing in a higher layer ships before the layer below is complete. Phase numbers predate the layers and are kept, so a
layer can hold phases that aren't consecutive (Phase 15 is in Layer 1, Phase 14 in Layers 2 and 3); the layer, not
the number, decides the order. These layers are not the three ingestion layers in requirements §2.

| Layer | Phases | Status |
|---|---|---|
| 1. Your books | 1–6, 7, 7b, 3b (rest), 8–13, 15 | Phases 1–6 done; Phase 7 in progress |
| 2. Your plan | Goals (phase to be assigned), 14 | Future |
| 3. Your education | None assigned | Future |
| 4. Your accountant | None (ids keep their old numbers) | Out of scope (listed, not built) |

---

## Layer 1: Your books

**What it is for.** Knowing where your money went: import statements from every account, categorize them, compute
monthly totals and trends, raise alerts, and answer questions about your own data, with every figure traceable to a
statement line. This layer is the shippable product. The AI categorizes, extracts and explains; SQL and Java do the
math (GR-01).

**Status: Phases 1–6 done; Phase 7 in progress; the rest future.**

| Phase | Scope | Requirement ids | Status |
|---|---|---|---|
| 1 | Project setup, synthetic sample data | QA-01, QA-02 (CI, deferred) | Done (QA-02 Planned) |
| 2 | Accounts and data model | DAT-01–DAT-08 | Done |
| 3, 3a, 3c | Ingestion: OFX/QFX, CSV, dedup, transfers, review queue | ING-01–ING-07, ING-10, ING-13, SEC-10, SEC-11 | Done (SEC-11 Planned until the first CSV export) |
| 3b | PDF parsers (paused after step 5; resumes after Phase 7) | ING-08, ING-11, ING-12, ING-02 (PDF part), SEC-09 | ING-08/11/12 Deferred; SEC-09 Done |
| 4 | Categorization: rules, then AI on redacted text | CAT-01–CAT-05, CAT-08, SEC-01, SEC-02, SEC-08 | Done |
| 5 | Analytics and alerts, no AI | ANA-01, ANA-03, ANA-06–ANA-08, ANA-02, ANA-04 | Done (ANA-02 and ANA-04 Planned for their remaining parts) |
| 6 | Chatbot backend: read-only tools, grounding, citations, memory | CHT-01–CHT-06, ANA-05, SEC-03 | Done |
| 7 | Chat UI, dashboard, upload page, polish (`docs/plans/phase-7.md`) | UI-01, UI-02 (step 7d), UI-03–UI-05, UI-07, UI-08 (decision; localhost binding) | **In progress** |
| 7b | Goals tab shell | GOAL-06 (the tab only) | Next, after 7 |
| 8 | Encryption at rest, login and HTTPS for phone access, OCR, local PDF extraction, document Q&A, vision role | UI-08 (login and HTTPS), SEC-04–SEC-07, ING-09, ING-14, CHT-07, AI-01 (vision role) | Future |
| 9 | Eval suites and benchmarks | QA-03, QA-04, AI-07 | Future |
| 10 | Local AI: the `local` profile (Ollama) and model tiers | AI-03, AI-02, AI-04, AI-05, UI-06 | Future |
| 11 | Portfolio polish: README, setup script, demo mode, ADRs | QA-05–QA-07 | Future |
| 12 | Categorization experiments | CAT-06, CAT-07 | Future |
| 13 | Finalysis MCP server (read-only) | INT-01, INT-02 | Future |
| 15 | Bookkeeping: monthly close, tax tags, receipts, rental ledger, net worth, document vault | ACC-01–ACC-07 | Future |
| None | Frontier assist (opt-in, anonymized summaries) | INT-03 | Proposed |

AI-01 (model roles) and AI-06 (token log) are cross-cutting: built in Phases 4 and 6, and Planned until every role is
used. ANA-04's missing and changed bills are in the Phase 5 backlog, with no phase yet.

### Phase 7 (in progress)

Plan approved: `docs/plans/phase-7.md` (decisions in §13). It narrows the scope below: UI-02 moved to Phase 8 (its
first item) and came back as step 7d on 2026-10-26 (the Import tab), ANA-02's fixed vs. discretionary waits (phase to be assigned), UI-08 is decided as localhost only until
Phase 8's login and HTTPS, and every Phase 6 backlog item waits (the holdout guard for Phase 9). The scope as first
listed:

- **Chat UI** replacing the minimal Phase 6 test page: the chat panel (UI-03), citations that open the source
  statement line (UI-04), and the cloud-AI banner (UI-05).
- **Dashboard from the approved mockup** (UI-01): the **Main** screen (KPI tiles, data-quality tile, cash-flow chart,
  spending donut, details panel, alerts with their basis, recurring charges, tabs Dashboard / Goals / Chat), the
  **Mobile** layout (UI-07) with its access approach (UI-08), the upload page and review queue (UI-02), and fixed vs.
  discretionary spending (ANA-02).
- **Phase 7b: Goals tab shell** from the same mockup: the tab and its layout with an empty state. Goal records,
  evaluation and the advisor note are Layer 2.
- **Polish** across the app.
- **Candidates from the Phase 6 backlog** (`docs/plans/phase-6.md`, "Phase 7 backlog"), to be chosen in the plan:
  - h-u10: figures where a refusal was expected (a month with no rows).
  - h-x06: no tool for a share of income.
  - The holdout guard is per set, not per model.
  - Haiku 4.5 skips the multi-step search fallback about half the time.
  - The cost estimator is conservative.

---

## Layer 2: Your plan

**What it is for.** Deciding where your money should go, judged only on your own data. Goals are structured records
evaluated each month from the Layer 1 analytics totals, with every gap explained by the same bucket rule as the
drill-down. Each goal has a target, a date (a month, or a target date) and a funding source the app can observe in your
statements (GOAL-07). Investments are limited to holdings and contributions you entered or imported: no market data, no
advice, no forecasts.

**Status: Future.** Starts when Layer 1 is complete.

| Phase | Scope | Requirement ids | Status |
|---|---|---|---|
| To be assigned | Goals: records, monthly evaluation, gaps, the chat-drafted goal, the advisor note, the full Goals tab | GOAL-01–GOAL-07 (GOAL-06 beyond the 7b shell) | Planned |
| 14 | Surplus from cash flow; holdings and contributions against a goal | INV-01, INV-02 | Planned |

Open question for the goals plan: GOAL-03's `ON_TRACK` / `OFF_PACE` projects a partial month by days elapsed. It
describes the current month's pace from observed figures, but it should be checked against Layer 4's "no forecasts"
boundary.

---

## Layer 3: Your education

**What it is for.** Understanding the choices: explanations tied to your own numbers, such as why the savings rate
matters at your income, or what a recurring charge costs over a year. Every figure comes from a Layer 1 or Layer 2
tool result (GR-01, CHT-04); the explanation is read-only and never advice.

**Status: Future direction; no phase assigned.**

| Phase | Scope | Requirement ids | Status |
|---|---|---|---|
| None (ids keep 14) | Curated public sources with dates; repo holds URLs only; case for and against with risks; labels | INV-04–INV-07 | Planned |

INV-04 to INV-07 keep the Phase 14 number they had before the layers; it is revisited when this layer is planned.

---

## Layer 4: Your accountant

**What it is for.** Acting on the choices: anything that recommends, forecasts, or moves money. Listed so the boundary
is written down.

**Status: Out of scope (listed, not built)** for the portfolio project.

| Phase | Scope | Requirement ids | Status |
|---|---|---|---|
| None (ids keep 14) | Market data via MCP | INV-03 | Out of scope (listed, not built) |
| None (ids keep 14) | Projections and Monte Carlo simulations | INV-08 (moved from INV-02) | Out of scope (listed, not built) |
| None (ids keep 16) | Tax rules, snapshots, what-ifs, estimates, deadlines, accountant note and chat, tax package | TAX-01–TAX-10 | Out of scope (listed, not built) |

INV-03 may move to Layer 2 if valuing the user's own holdings needs prices: the boundary is no recommendations and no
forecasts, not no data.

**Why it is out of scope.** A recommendation or forecast about someone's money carries liability that a portfolio
project can't take on: "invest this," "you'll owe that," or "move money here" is advice, and getting it wrong costs the
user money. It also breaks the app's central rule: SQL and Java compute every figure from data that exists, and the
AI only explains them (GR-01); a forecast is a figure about data that doesn't exist yet, and a recommendation is a
judgment no tool result can ground (CHT-04). Moving money would also make the app write to the outside world, while
the chatbot's tools are read-only (SEC-03). Last, acting well needs market data, tax tables and live account access,
which pull a local-first app toward sending more of the user's data out (SEC-01) and holding credentials it never
needs to see.
