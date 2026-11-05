# Phase 10 plan: Corgi's backlog, Plan with Corgi, key rotation, a second region

Status: **approved** (2026-11-05) with the answers under "Decisions at approval" (§12), which override anything
older in this file; the body below has been brought in line with them. Phase 10 starts from tag `phase-9-done` (`fe88af1`) on branch `v1`.
Requirements as of `docs/requirements.md` v1.82, roadmap version 6.

Goal: fix the two faults the Self-hosted measurements found, then add the first planning feature (a long-range
savings goal, a monthly plan and check-ins, every figure from the app's code), and close two operational gaps (a
recovery key that can be replaced, and a second region for the Self-hosted server). A sixth step is written now and
waits for a quota.

| Step | What | Requirement ids | Waits on |
|---|---|---|---|
| 10-1 | "[amount]" copied from the history | CHT-04, CHT-06 | nothing |
| 10-2 | Examples overconfidence | CAT-03, AI-11 | nothing |
| 10-3 | Plan with Corgi | GOAL-01, 02, 03, 04, 07, INV-01, CHT-01, CHT-04, SEC-03, UI-03, new GOAL-08 | nothing (§12, Q1) |
| 10-4 | Recovery-key rotation | SEC-06 | nothing |
| 10-5 | Self-hosted in a second region | AI-08, AI-12 | the Ohio GPU quota (for the owner's run only) |
| 10-6 | gpt-oss-120b on a g6.12xlarge | QA-04, AI-08, AI-05 | the 48-vCPU quota (**when the quota is approved**) |

The order is fixed. Each step is one commit (small follow-ups are fine) and its own review.

What "the owner runs" means here: **no live AI call by me, and no EC2 or Tailscale action by me.** The owner runs every
eval, every `create.sh`/`destroy.sh`, every Start and Stop. The categorization holdout is never run or named; the chat
holdout's question file is never opened. Nothing outside the repo is opened; `~/.aws/credentials`, any AWS key and any
Tailscale auth key are never read, printed or copied. Synthetic data only.

Working rules (as Phase 9): before every commit `caffeinate -i ./mvnw clean verify` in `backend/`, then `npm test` in
`frontend/` once Maven has finished (the Vitest-under-load timeouts, phase-9.md §11), plus `npm run test:layout` where
the UI changes; Maven's summary counts reported; files added by name, never `git add -A`; push `v1`; stop for review.
The owner's backend is stopped and the Java extension's auto-build is off before `clean verify`.

Costs below use the app's own rate for a g6.xlarge in us-east-1, $0.8048 an hour, and the Phase 9 runs' times:
a chat dev run took 408 s and a categorization dev run about 1 minute, and a Start to ready takes 3 to 4 minutes.
Stopped storage (80 GB gp3) costs whatever the server costs while stopped, the same in every step, and isn't repeated.

---

## 1. What the plan was written from

- The owner's Phase 10 brief (2026-11-05).
- The code at `fe88af1` (tag `phase-9-done`):
  - chat memory: `ConversationMemory` (`window`, `strippedAnswer`, `MAX_ANSWER_CHARS`, `SHORTENED`),
    `SummaryFigureStripper` (`[amount]`, `[percent]`, `[number]`), `ChatPayloadGuard` (`checkText` for the question,
    `checkHistory` for answers and the summary);
  - grounding: `AnswerCheck` (`NO_FIGURES` when the extractor finds no figure), `AnswerGrounding` (one retry with
    `RETRY_PREFIX`, then `FallbackAnswer`), `TurnLoop`;
  - the prompt `chat/system-prompt.txt`, version `chat-10` (`ChatPrompt.VERSION`), 31 lines; line 9 describes the
    stripped history;
  - categorization: `AiCategorizer` (the threshold per path, `min-confidence-by-path`), `CategorizationExamples`
    (one `<examples>` block per batch, with no link from an example to an item), `MerchantEmbeddingStage`,
    `SimilarMerchantProperties` (`min-similarity` 0.65, global), prompt version 3;
  - the vault: `VaultService` (`setup`, `recover`, `changePassword`), `VaultKeyFile` (the recovery key wrapped by
    Argon2id + AES-256-GCM), `scripts/vault/create.sh` and `mount.sh` (`hdiutil ... -stdinpass`, a sparse bundle whose
    one passphrase is the recovery key), `RecoveryKeyScreen.tsx` (type the last group back, "I've saved it. Continue");
  - Self-hosted: `scripts/selfhost/create.sh` and `destroy.sh` (both already ask for the region; the billing stack is
    already its own stack in us-east-1), `SelfHostControl` (already finds the server by tag in the region from
    Settings), the template's `InstanceType` (`g6.xlarge`, `g5.xlarge`), `hourly-rates` keyed by instance type only;
  - the next migration is **V27**.
- The committed reports: `docs/eval/chat-dev-bb822aa6b00e-20261103T120333Z` (Self-hosted chat, 50/60, 48/58 without
  IDENTITY), `docs/eval/dev-89e693bc1875-20261104T112142Z` (Self-hosted categorization), `docs/eval/tiers.md`.
- The synthetic sets: `sample-data/chat/dev.yml` (version 6, 60 questions), `sample-data/categorization/dev.csv`
  (148 rows).
- `docs/requirements.md` v1.82 (GOAL-01 to GOAL-07, INV-01, INV-08, CHT-04, CHT-06, CAT-03, SEC-01, SEC-03, SEC-06,
  AI-08, AI-11, QA-04), `docs/plans/roadmap.md` version 6 (its Phase 10 list and the four layers),
  `docs/plans/phase-9.md` §10 and §11.
- `docs/ideas/` is not in the repo, so Plan with Corgi (§4) is designed from the requirements and the brief alone.

---

## 2. Step 10-1: "[amount]" copied from the history

### 2.1 What happens today

- Every earlier answer goes back to the model through `ConversationMemory.window` → `strippedAnswer` →
  `SummaryFigureStripper.strip`: each figure the grounding extractor finds becomes `[amount]`, `[percent]` or
  `[number]`. The summary is stripped the same way. `chat-10`'s line 9 tells the model the figures were removed.
- **The questions are not stripped.** The user's outbound question goes back as typed (redacted, `checkText`), so a
  figure the user typed already reaches later prompts. Only the model's own earlier answers lose their figures.
- On d-m03 turn 2 (the Self-hosted run), Ministral 3 14B called `get_alerts`, whose result had $84.12, and wrote
  "two transactions of [amount] at Safeway". The extractor doesn't see `[amount]` as a figure, so the answer was
  `NO_FIGURES`, passed grounding and was **shown**. Turn 3 (no tool call) repeated it. Sonnet never did this; the bug is
  that the app lets it through, whatever the model.

### 2.2 The fix (recommended): a placeholder in an answer is ungrounded

`AnswerCheck` treats each of `[amount]`, `[percent]`, `[number]` (exact tokens, any case) in a model answer as an
ungrounded figure. The existing path then applies unchanged: one retry, then the Java fallback. The retry message for
this case is its own fixed text, framed like the others (`AnswerGrounding.AUTOMATIC` first):

> Automatic check, not a message from the user: these are placeholders from the earlier conversation, not figures:
> [amount]. Write the answer to the user's question again from the start, as a new answer to that question, with the
> figures from this turn's tool results; call a tool if this turn hasn't returned the figure you need, or say you can't
> tell from the data.

*(Amended at 10-1: the wording first drafted here said "your answer contains", which `RetryWordingTest` forbids,
since it reads as a correction and invites an apology; see the 10-1 notes.)*

- History, the summary, `chat-10` and SEC-01 are unchanged. Nothing new leaves the machine.
- It holds on every path and every model, because it is Java.
- d-m03 turn 2 gets a retry naming the placeholder, with $84.12 already in its tool result; turn 3 gets the same retry
  and has to call the tool.

**Why not the brief's two other candidates:**

- *Keep tool-sourced figures in the history.* Every figure in a shown answer was grounded in a tool result of its own
  turn, so "figures from a tool result in this conversation" is all of them. That reverses CHT-06's rule ("no figure of
  an earlier answer reaches a later prompt"). Grounding is per turn, so a figure copied from history without a new tool
  call still fails grounding; the model would be invited to copy figures, and the cost is more retries and fallbacks
  (on Cloud, more tokens). It also loses the reason the rule was made: every figure needs a fresh tool call.
- *"The amount above" as prose.* Still copyable, and worse: "two transactions of the amount above at Safeway" is a
  sentence the extractor can't catch, so the fault would become invisible instead of fixed.

### 2.3 Tests

- `AnswerGroundingTest`: each placeholder alone, in a sentence, beside a grounded figure, in brackets of another case;
  `[earlier answer shortened ...]` and a citation marker are not placeholders.
- `AnswerGroundingTest` also: the placeholder retry message word for word; a mix of a placeholder and an ungrounded figure
  names both.
- `ChatServiceTest`, the d-m03 shape with the fake model: turn 2's first answer has `[amount]` and `get_alerts` evidence of
  84.12 → retry → "$84.12" shown `GROUNDED`; a model that repeats the placeholder → the fallback is shown, never the
  placeholder; turn 3 with no tool call → retry asks for the tool.
- The chat eval report: a "placeholder retries" count in the summary and per turn, so the rerun shows how often it
  fired.
- `ConversationMemoryTest` and `SummaryFigureStripperTest` unchanged (the history is unchanged).

### 2.4 The owner's rerun and its cost

```sh
cd backend && caffeinate -i ./mvnw test -Pchat-eval -Deval.set=dev -Deval.provider=ollama \
    -Deval.baseUrl=http://finalysis-selfhost:11434 -Deval.model=ministral-3:14b
```

- **Like-for-like baseline: `chat-dev-bb822aa6b00e-20261103T120333Z`** (Self-hosted, 50/60, 48/58), not the Bedrock run.
- Pass: d-m03 correct; no other question worse than in the baseline. d-l01's cold-load ERROR there is excluded
  from the comparison if the owner warms up first (Start does), and recorded either way.
- Cost: one Start (3 to 4 minutes) plus about 7 minutes of run, **about $0.15**; tokens $0.

Requirements: CHT-04 and CHT-06 get a note (v1.83). Status unchanged (Done).

---

## 3. Step 10-2: examples overconfidence

### 3.1 The evidence (`dev-89e693bc1875-20261104T112142Z`)

| | B: no examples | C: examples (floor 0.65) |
|---|---|---|
| Items sent (rows) | 16 (19) | 16 (19) |
| Items with an example at the floor | 0 | **3** |
| Confidence of the answers | 14 rows at 0.4–0.5, 3 at 0.9–1.0 | **6 rows at 0.8–0.9 (33% right), 13 at 0.9–1.0 (92%)** |
| At 0.80: wrong / review | 0 / 16 | 5 / 0 |
| At 0.85: wrong / review | 0 / 16 | 1 / 6 |

The five wrong rows: Saffron Row ×2 (Dining → Shopping, 0.80), Pixelforge Games ×2 (Subscriptions → Shopping, 0.80),
and the Greenvale Market refund (+8.45, Refunds & Rebates → Groceries, **0.90**).

What it shows:

1. **The confidence rose for the whole batch, not just the 3 items with examples.** Saffron Row and Pixelforge were at
   0.4–0.5 in B (sent to review, correctly) and at 0.80 in C. The batch is one call with one `<examples>` block, and the
   block doesn't say which example belongs to which item.
2. **0.85 already removes four of the five.** The one left is the Greenvale refund at 0.90. Its own merchant's history is
   money out (Groceries), so it is not eligible as an example of a money-in item; whatever examples it had came from
   other money-in merchants (payroll, refunds, interest, person-to-person), which name different categories.
3. **The report can't say which items got which examples.** It gives the nearest similarity as a histogram, not per
   item. So the first thing this step adds is the per-item diagnostics (§3.3).

**The target, as written, needs a right answer, not only a cap.** At 0.85 today: 1 wrong, 6 review. Sending the
Greenvale refund to review gives wrong 0 and **review 7**. Wrong 0 with review at most 6 needs that item (or one of the
six) answered correctly at or above 0.85. Decided (§12, Q2): wrong 0 is the rule, and review 7 passes.

### 3.2 The options, and the recommendation

- **(a) A higher floor before an item gets examples at all.** Raising 0.65 to 0.70 removes all 3 items' examples on this
  set; the batch then has no `<examples>` block and behaves like B: wrong 0, **review 16**. It treats the symptom by
  turning the feature off for nomic, and nomic's similarities are lower than Titan's (most nearest examples sit between
  0.45 and 0.60, phase-9.md §10), so a higher floor is the opposite of what the Phase 9 notes suggested.
- **(b) Cap the confidence of an item whose examples disagree (recommended).** Computed in Java from what the app
  already knows when it builds the batch: each item's own examples (the ones selected for it, at or above the floor).
  An item's confidence, for the threshold only, is capped at `finalysis.ai.examples.disagreement-cap` (default 0.75,
  below every path's threshold) when:
  - its own examples name two or more categories, **or**
  - it has examples and the answered category is none of theirs.

  The reported confidence is still stored; the review row says why ("examples disagree"). Items with no examples of
  their own are untouched. This targets the failure seen (a confident answer that its evidence doesn't support), adds no
  model call, and works on every path.
- **(c) A calibration table per path.** From this run it would have two populated bands with 6 and 13 rows; it is fitted
  on the dev set and checked on the same set (the holdout is never run again), and with a monotone table it is the
  per-path threshold again under another name. Not now; worth revisiting only with far more AI items (§11 Q3; §12: not in Phase 10).

**What (b) does and doesn't fix:** it catches the Greenvale refund if its examples disagree or don't include Groceries,
which is what its eligible examples should show; the per-item diagnostics confirm it. It does not undo point 1, the
batch-wide rise; the 0.85 threshold handles that today. If the diagnostics show the batch-wide rise is the larger
problem, the follow-up (not in this step) is to send the examples per item (a `for` attribute on each example), which is
a prompt change (categorization prompt version 4) and its own measurement.

### 3.3 What changes

- The eval report: a per-item table (item, its own examples' similarities and categories, the answer, the reported
  and the effective confidence, the cap's reason). Diagnostics only; no score changes.
- `AiCategorizer`: the cap, as above, before the threshold; the review reason `EXAMPLES_DISAGREE`.
- `SimilarMerchantProperties`: `disagreement-cap` (0 to 1, refused at or above the lowest path threshold at startup,
  so it can't be configured into a no-op).
- `ShippedAiSettingsTest`: the shipped cap.

### 3.4 Tests

- `AiCategorizerTest`: one category among the examples and the answer is it → untouched; two categories → capped;
  the answer is none of the examples' → capped; no examples → untouched; the stored confidence is the reported one;
  the review reason.
- `PromptBuilderTest` unchanged (the prompt is unchanged, version 3).
- The eval report's new table on a fixture run.

### 3.5 The owner's rerun and its cost

```sh
cd backend && caffeinate -i ./mvnw test -Pai-eval -Deval.set=dev -Deval.min-confidence=0.85 \
    -Deval.direct-min-similarity=0.97 -Deval.provider=ollama -Deval.baseUrl=http://finalysis-selfhost:11434 \
    -Deval.model=ministral-3:14b
```

- Baseline: `dev-89e693bc1875-20261104T112142Z`, C at 0.85 (1 wrong, 6 review).
- Pass: C at 0.85 with **wrong 0**. The review count is reported, not targeted (§12, Q2): wrong 0 with review 7 passes.
- Cost: one Start plus about a minute of run, **about $0.07** (or nothing extra if run in the same session as 10-1's).

Requirements: CAT-03 and AI-11 get a note with the measured result (v1.84). The categorization dev set stays version 1
in Phase 10 (§12, Q3), so the roadmap's "0.85 measured on more than 16 AI items" stays open.

---

## 4. Step 10-3: Plan with Corgi

### 4.1 What it is

A conversation in a **Plan** tab of the chat page:

1. **A goal.** The user says what they want: "I want $60,000 saved in 10 years." The goal is a target amount, a target
   month, and a funding source the app can observe in the statements (GOAL-07): the monthly surplus (INV-01), or
   transfers into one of the user's accounts (by alias, `SAVINGS-1`).
2. **A monthly plan.** The app computes what the goal needs each month and compares it with the user's recent surplus.
   If there is a gap, **the model proposes which categories to cut** and by how much, from a fixed menu; the app
   computes what each cut is worth and whether they close the gap. The model explains.
3. **Save.** The draft is shown as a "Proposed in chat · not saved" card with **Save** and **Not now** (GOAL-02). Only
   the user's click saves it, through an ordinary endpoint; no chat tool writes.
4. **Check-ins.** Each month, against actual spending: each planned category's cap against what was spent, the funding
   observed against what the plan needed, and the funding to date against the plan's schedule to date.

**No arithmetic by the model (GR-01).** Every figure is a tool result: the required monthly amount, the surplus, each
cut's dollar value, the gap after cuts, the check-in's results. The model picks categories and a cut size from a menu,
and explains.

**The Layer 4 boundary.** The model may pick cuts, because a pick from a menu over the user's own categories is a
choice the app then computes. It may never project growth: no interest, investment return or inflation, no "you will
have", no income forecast. The required monthly amount assumes no growth, and the card says so ("no interest or
investment growth assumed; this is planning, not financial advice"). A question that needs a projection ("if I invest
it at 7%...") is refused and named as outside the app (INV-08, Layer 4).

### 4.2 The tools (read-only, Plan tab only)

All in `com.finalysis.chat.tool`, thin adapters over a new `plan` feature package (`backend/.../plan`), which does the
math in Java and SQL with `BigDecimal`, on the analytics totals and the drill-down's bucket rule (transfers excluded,
GR-04). Every result carries refs for citations, like the existing tools.

| Tool | Arguments | Returns |
|---|---|---|
| `get_plan_baseline` | `months`: 3 or 6 | the last complete months used; average monthly income, spending and surplus (INV-01); average monthly spending per EXPENSE category; the months' data quality (ANA-08) |
| `draft_plan` | `target_amount`; exactly one of `target_month` (`YYYY-MM`) or `months_ahead` (1 to 600); `funding` (`SURPLUS` or an account alias), `cuts`: up to 6 × {`category`, `cut_percent` ∈ 10, 25, 50, 100} | the resolved target month; months remaining; funded to date (0 for a new plan); required monthly amount; baseline surplus; the gap; each cut's monthly dollars; the cuts' total; the gap after cuts; `closes_gap` |
| `get_plan` | none | the saved plan (target, target month, months remaining, funding, cuts and their caps), or "no plan" |
| `get_plan_checkin` | `month` | per planned category: cap, spent, `MET`/`MISSED`/`SO_FAR`; funding observed against the monthly requirement; funded to date against the schedule to date; months elapsed and months remaining; `INSUFFICIENT_DATA` below the data-quality threshold |

- `draft_plan` is a calculator: nothing is stored. Its arguments are checked like every tool's (`ToolArguments`): a
  category must be an EXPENSE category the turn has seen; the target 1 to 600 months ahead; the amount positive,
  at most `NUMERIC(12,2)`; a cut on a category with no baseline spending is an error, never a zero.
- **No date arithmetic by the model** (§12, the 10-3 change). "$60,000 in 10 years" is `months_ahead: 120`; the model
  never turns years into a month. `draft_plan` takes exactly one of `target_month` or `months_ahead` (both, or neither,
  is an argument error naming the two), and returns the resolved target month and the months remaining, which are
  grounded like any figure. Every "how many months are left" or "how many months so far" the model might count comes
  from a tool: `draft_plan`, `get_plan` and `get_plan_checkin` return it, from the app's clock.
- **The user's target is an argument the model copies from the question**, then a figure the tool returns, so it is
  grounded like any other. The user typed it; it already goes to the model with the question.
- **Funding through an account** sums the transfers into it since the plan's start (observed flows). It is never the
  account's balance, which SEC-01 forbids sending.
- A partial month's check-in shows what is spent so far (`SO_FAR`), with no pace projection; GOAL-03's `ON_TRACK` /
  `OFF_PACE` stays with the Goals phase (§12, Q5).

### 4.3 Saving and the schema

- **V27**: `plan` (one active plan; target `NUMERIC(12,2)`, target month, funding kind, funding account, created),
  `plan_version` (effective month, baseline months), `plan_cut` (version, category, cut percent, **cap
  `NUMERIC(12,2)`** computed and stored at save, so a past month is judged against the cap that applied; GOAL-01's
  versioning).
- `POST /api/plans` (from the card's Save: the draft's arguments, recomputed on the server, never trusted from the
  client), `PUT /api/plans/current` (a new version from next month), `DELETE /api/plans/current`, `GET /api/plans/current`,
  `GET /api/plans/current/checkin?month=`. Behind the vault gate like everything else.
- ArchUnit: chat tools may read `plan`, never call its writers (SEC-03); only `PlanController` writes.

### 4.4 The prompt: `chat-11`

- `chat-11` = every line of `chat-10`, **word for word and in order**, plus a planning section that applies only when
  the plan tools are present:
  - use `get_plan_baseline` before proposing cuts; pick cuts only from the menu and only among the categories it
    returned; never write a dollar amount for a cut that `draft_plan` didn't return;
  - never project growth, interest, returns, inflation or future income; refuse those as outside the app;
  - the plan is saved only by the user's Save button; never say it is saved;
  - prefer categories the user can change month to month, and say why each was picked from the baseline figures.
- `ChatPromptVersionTest` (new): the chat-10 lines appear in chat-11 unchanged and in order (the chat-10 text kept as a test
  resource with its SHA-256 pinned); `ChatPrompt.VERSION` is `chat-11`.
- **Tool exposure:** the Ask tab sends the existing 12 tools, as today; the Plan tab sends those plus the four plan
  tools. So the Ask tab's tool list and behaviour stay comparable with the Phase 9 runs, and a small model isn't handed
  four more tools for every question.

### 4.5 The UI

- **Chat page:** tabs **Ask** and **Plan** (a conversation has a kind, `ASK` or `PLAN`; V27 adds it to
  `chat_conversation`, existing rows `ASK`). The Plan tab's empty state offers three starters ("Set a savings goal",
  "How am I doing this month?", "Show my plan").
- **The draft card:** target, month, required monthly, baseline surplus, each cut with its dollars, gap after cuts,
  the no-growth line, **Save** and **Not now**. Every figure from the tool result as returned; the frontend computes
  nothing (`noArithmetic.test.ts` covers the new files).
- **Settings → Plan:** the saved plan, this month's check-in (from `GET .../checkin`, no AI needed, so it works with
  Corgi off: GOAL-05's template fallback for the figures), baseline months (3 or 6), Delete plan with a confirm.
- **Per path:** Cloud is allowed and shows the per-turn cost line, as every chat turn does; Self-hosted shows the
  running line; Local is allowed. The banner per path is unchanged.
- `test:layout`: the tabs, the card and Settings → Plan at 375 and 1280 px, both themes.

### 4.6 The dev set: version 7, a PLAN category

Eight PLAN questions on the SAMPLE dataset ("today" 2026-10-15), appended to the 60; the 60 are unchanged so their
scores compare with bb822aa. Expected figures are computed in the step by the plan service on the sample data and
written into `dev.yml`; a test recomputes them so the file can't drift.

| Id | Question (sketch) | Expected |
|---|---|---|
| d-p01 | "I want to save $60,000 in 10 years. What would that take a month?" | the required monthly amount and the resolved target month (`months_ahead: 120`, no date counted by the model) |
| d-p02 | "What's my monthly surplus?" | the 3-month average surplus |
| d-p03 | (multi-turn) the goal, then "Which categories could I cut to get there?" | `draft_plan` called with cuts; its gap-after-cuts figure |
| d-p04 | "If I invest it at 7%, how much will I have?" | a refusal; no figure |
| d-p05 | "Save this plan for me." | no write; says to use Save; no figure required |
| d-p06 | "How am I doing on my plan this month?" (a seeded plan) | a check-in figure |
| d-p07 | "Cut dining by $200." | either `draft_plan` at a menu size, grounded, or an explanation of the menu; never an ungrounded $200 cut total |
| d-p08 | "Will inflation change what I need?" | a refusal; no figure |

The PLAN questions run in the Plan tab's tool set; the other 60 in the Ask tab's. The report shows PLAN apart, and the
58 non-IDENTITY, non-PLAN questions stay the comparable score.

### 4.7 What stays out

The Goals tab beyond its shell (GOAL-06), more than one plan, goal types other than a savings amount with a date (caps
on their own, savings rate, no new subscriptions), GOAL-03's pace projection, GOAL-05's written monthly note, fixed vs.
discretionary spending (ANA-02's part), holdings and contributions (INV-02), anything Layer 3 or 4 (education sources,
projections, market data), and a plan the model can save.

### 4.8 Tests

- `ToolArgumentsTest` (the 10-3 change): `draft_plan` with `target_month` only, with `months_ahead` only (each resolved
  against the clock to the same month), with both (refused), with neither (refused), and out-of-range values of each.
- `PlanCalculatorTest` (pure Java): required monthly with `BigDecimal` half-even to the cent; months remaining across
  years; funded to date; each cut; gap closed exactly, short by a cent, over.
- `PlanBaselineTest` (Testcontainers, sample data): averages per category, surplus, transfers excluded, incomplete months
  left out, data quality.
- `PlanCheckinTest`: caps met and missed, `SO_FAR`, `INSUFFICIENT_DATA`, a version change judged by the cap that applied.
- Tool tests per tool: arguments refused (unknown category, INCOME category, cut not on the menu, month in the past,
  seventh cut), refs present.
- `ChatOutboundPayloadTest`, `ChatTurnPayloadTest` and `OllamaTurnPayloadTest` extended: the plan tools' results carry only SEC-01's allowed
  fields; no balance, no account number, no id.
- `PackageDependencyTest`: chat reads `plan` only through its read interface.
- `ChatPromptVersionTest` (chat-10 pinned in chat-11), the tool exposure per tab.
- Frontend: tabs, card (Save, Not now, figures as returned), Settings → Plan, the no-arithmetic guard; layout.
- Postman: a folder **16. Plan** (save, read, check-in, revise, delete, refusals, 423 while locked), AI off, so newman
  runs it without a model.

### 4.9 The owner's runs and their cost

- Self-hosted, dev set version 7 (68 questions), the same command as §2.4: about 9 minutes plus a Start, **about $0.17**.
  Pass: the 58 comparable questions no worse than 10-1's run; PLAN reported apart (no floor this phase: it is the
  first measurement).
- Cloud (optional): the same set on Sonnet 4.6, about **$0.85** at run 6's cached cost per question.
- Local: not measured (9d is still deferred).

Requirements (v1.85): new **GOAL-08** "Plan with Corgi" (the conversation, the menu, the tools, the no-growth rule);
GOAL-01, 02, 03, 04, 07 and INV-01 noted with the part this phase builds (they stay Planned, with the rest of Layer 2);
CHT-01 (16 tools, 4 in the Plan tab), SEC-03 note. Roadmap version 7: the layer exception (§12, Q1).

---

## 5. Step 10-4: recovery-key rotation

### 5.1 What it does

Settings → Vault → **Replace recovery key**. The user enters the password; the app makes a new recovery key, changes
the image's passphrase to it, writes a new key file, and shows the new key once on the same screen as setup (the 13
groups, Copy, the warning, type the last group back, "I've saved it. Continue"). From then on the old key opens
nothing: not this vault, and not with any copy of the old key file.

### 5.2 How, in order (crash-safe)

1. Vault unlocked; password checked by unwrapping the key file (rate-limited like unlock); this gives the old key.
2. New key from `SecureRandom` (`RecoveryKey`, as setup).
3. Write `vault-key.json.next`: the new key wrapped with the same password (fresh salt and nonce), fsync.
4. `hdiutil chpass <bundle> -oldstdinpass -newstdinpass`: both keys on stdin, never in an argument, through a new
   `scripts/vault/chpass.sh` in the same style as `mount.sh`.
5. Atomic move `vault-key.json.next` → `vault-key.json` (the `VaultKeyFile.write` pattern: same folder, `ATOMIC_MOVE`).
6. Return the new key. Old and new key bytes zeroed.

- A crash between 4 and 5 leaves the image on the new key and `vault-key.json` on the old. At unlock, if
  `vault-key.json.next` exists, the app tries it first with the same password and finishes the move. A crash before 4
  leaves a `.next` the image doesn't accept; unlock deletes it after the main file works.
- **Whether `chpass` works while the image is attached** is checked first in the IT. If it needs the image detached,
  rotation stops the database and detaches (requests answer 423 for those seconds, as at lock), runs `chpass`, and
  remounts with the new key (§12, Q7).
- **Backups:** a copy of the bundle taken before rotation is still encrypted with the old key, and the old key file
  still opens that copy. Rotation can't reach copies. The screen and the README say: take a new backup now, and delete
  the old ones if the old key may be exposed.

### 5.3 Endpoint and UI

- `POST /api/vault/rotate-key` `{"password": "..."}` → `200 {"recoveryKey": "..."}`; `401`-style wrong password (as
  `changePassword`), `423` locked, `429` rate-limited, `409` while another vault transition runs. Never logged; the
  response has `Cache-Control: no-store`.
- Settings → Vault: the entry, a password field, a confirm ("Your old recovery key will stop working."), then
  `RecoveryKeyScreen` reused as is.

### 5.4 Tests

- `VaultLifecycleTest` (fakes): the order of the six steps; the `.next` recovery at unlock both ways; wrong password
  changes nothing; locked → 423.
- **Vault IT (`VaultDatabaseIT`, `-Pvault-it`, a throwaway image in a temp folder):** set up; copy the key file; rotate; lock; unlock with
  the password works; **recover with the old key fails**; **unlock with the copied old key file and the password fails**;
  recover with the new key works. Plus a kill between `chpass` and the move, then unlock succeeds.
- `VaultScriptsTest`: `chpass.sh` never puts a key in an argument (the fake `hdiutil` records its arguments).
- Frontend: the Settings entry, the confirm, the key screen; layout.
- Postman folder 14: rotate with a wrong password (refused), rotate (the key's shape checked), recover with the old key
  (refused), recover with the new one, and the folder's last password carried on.

### 5.5 The owner

Nothing to run beyond the review: the IT runs on throwaway images. Rotating the owner's own vault is the owner's choice,
after a backup. Cost $0.

Requirements: SEC-06 note, rotation built (v1.86).

---

## 6. Step 10-5: Self-hosted in a second region

Most of this exists: both scripts ask for the region, the billing stack is its own stack in us-east-1, and the app's
Start, Stop and status already use the region in Settings. What's left:

- `create.sh --region us-east-2` and `destroy.sh --region us-east-2` (flags beside the prompts; the prompt's default
  stays us-east-1). The billing stack stays in us-east-1 whatever the region.
- **One server at a time** (§12, Q8): `create.sh` looks for the `finalysis-selfhost` stack in every enabled region the
  account lists, and refuses if one exists elsewhere, naming it and `destroy.sh --region`. The Tailscale name stays
  `finalysis-selfhost`, and the app's single region setting stays right.
- `hourly-rates` keyed by region and type (`"[us-east-2/g6.xlarge]"`), with a fallback to the type alone; the owner
  enters Ohio's rate from the pricing page. `SelfHostRatesTest` (new): every allowed type has a rate in each listed region.
- Settings → Self-hosted: the region field already exists; it gains a hint ("the region create.sh used"). The status
  line names the region ("Stopped, us-east-2").
- IAM: the Start/Stop statement's ARN for us-east-2, printed by `create.sh` as today.
- README: when and how to move (capacity), the quota per region, the billing alarm staying in us-east-1.
- Tests: `SelfHostScriptsTest` in `--plan` mode with the fake `aws`: `--region` honoured; the refusal when a stack exists
  in another region; the billing stack still in us-east-1; `destroy.sh --region`. `SelfHostControlTest`: the rate by
  region.

**The owner (when the Ohio quota is approved):** `destroy.sh` in us-east-1 (optional, if moving), `create.sh --region
us-east-2`, set the region in Settings, Start, Test, a 5-question subset run (`-Deval.questions=d-l01,d-c01,d-t01,
d-a01,d-s01`), Stop. About 25 minutes of server time for the create and first boot plus a few minutes of run, **about
$0.50**.

Requirements: AI-08 and AI-12 notes (v1.87).

---

## 7. Step 10-6: gpt-oss-120b on a g6.12xlarge (when the quota is approved)

Written now, built only when the owner says the 48-vCPU quota is approved.

- **The instance:** `g6.12xlarge`, 4 × NVIDIA L4 (24 GB each, 96 GB), 48 vCPUs; Ollama splits the model across the four
  GPUs. gpt-oss-120b's MXFP4 weights are about 65 GB.
- **create.sh options:** `--instance-type g6.12xlarge` and `--model gpt-oss:120b`; the template's allowed types gain
  `g6.12xlarge`; the quota check asks for 48 vCPUs for it; `RootVolumeGb` 150 for it (the model, nomic, drivers).
- **Loading time:** at gp3's baseline 125 MB/s, 65 GB takes about 9 minutes to read. So, for this type: the
  creation signal's timeout from 30 to 60 minutes; `warm-up-timeout` per instance type (300 s → 900 s); or gp3
  throughput raised to 500 MB/s for it (about $20 a month more while the volume exists). Recommended: the longer
  timeouts, no extra throughput.
- **Read timeout and context:** `self-hosted-read-timeout` per model (60 s → 120 s for the 120B); `num-ctx` stays
  16,384 (the startup check already ties it to the input budget). `max-turn-time` stays 45 s; the run reports how often
  it is reached.
- **Reasoning text:** gpt-oss on Bedrock put its reasoning in the answer (phase-9.md §11). On Ollama the reasoning comes
  in its own field; a test with the stub pins that only the content is shown.
- **Runs (the owner):** the chat dev set (version 7) and the categorization dev set, with `-Deval.model=gpt-oss:120b`.
- **Cost:** g6.12xlarge on-demand is about **$4.60 an hour** in us-east-1 (the owner confirms from the pricing page and
  enters it in `hourly-rates`). A first boot of about 30 minutes, a warm-up, a chat run of maybe 15 minutes and a
  categorization run: **about $5 for the session**, plus 150 GB of gp3 while it exists. `destroy.sh` afterwards unless it
  stays.
- Tests: the template's allowed types and volume size per type; `create.sh --plan` with the options; the quota check at
  48; the rate present; the timeouts per type and model.

Requirements: QA-04 note with the measured row (v1.88); `docs/eval/tiers.md` gains a column.

---

## 8. Later (no phase yet)

Recorded so they aren't lost; none is in Phase 10.

- **Step 9d, the Local measurements**, when hardware with a GPU Ollama can use exists (phase-9.md §6), with what needs
  the same hardware: AI-02's model tiers, AI-04's loading, AI-05's KV cache and keep-alive, UI-06's notice.
- **Lock before sleep** (the vault locks on wake today).
- **SEC-05**, field-level encryption.
- **Kept statement copies** listed and downloadable.
- **CHT-07**, questions about statement documents.
- From phase-9.md §11, not in the brief's order: the unknown-tool turn failure, gpt-oss's reasoning text on Bedrock, the
  invented `account` parameter, the Vitest timeouts (handled by running the suites one after the other), Cloud
  embedding search by meaning, examples sent per item (§3.2).

---

## 9. Testing summary

| Area | Tests | Step |
|---|---|---|
| Grounding | placeholders ungrounded; the retry text; the d-m03 shape end to end; the report's count | 10-1 |
| Categorization | the cap, its reasons, stored vs. effective confidence; per-item diagnostics | 10-2 |
| Plan math | calculator, baseline, check-in, versions (Testcontainers) | 10-3 |
| Plan tools | arguments, refs, SEC-01 payloads, read-only (ArchUnit) | 10-3 |
| Prompt | chat-10 pinned inside chat-11; tool sets per tab | 10-3 |
| Eval | dev set version 7, expected figures recomputed | 10-3 |
| UI | Plan tab, card, Settings → Plan, key rotation; no arithmetic; `test:layout` | 10-3, 10-4 |
| Vault | rotation order and crash recovery; the IT's old-key and old-key-file failures | 10-4 |
| Self-hosted | `--region`, one server at a time, rates by region; 120B options and timeouts | 10-5, 10-6 |
| Postman | folder 14 additions, folder 16 Plan; newman on every README folder | 10-3, 10-4 |

---

## 10. Steps

| Step | Content | Code | The owner | Cost | Requirements |
|---|---|---|---|---|---|
| 10-1 | Placeholders ungrounded, the retry message, the report count | `chat.grounding`, eval report | Self-hosted chat dev run vs. bb822aa | ~$0.15 | CHT-04, CHT-06 notes; v1.83 |
| 10-2 | Per-item diagnostics; the disagreement cap | `categorize`, eval report | Self-hosted categorization dev run at 0.85 | ~$0.07 | CAT-03, AI-11 notes; v1.84 |
| 10-3 | Plan with Corgi: V27, `plan` package, 4 tools, chat-11, Plan tab, Settings → Plan, dev set v7, Postman 16 | backend, frontend, eval | Self-hosted dev v7 run; Cloud optional | ~$0.17 (+$0.85 Cloud) | GOAL-08 new; GOAL-01–04, 07, INV-01, CHT-01, SEC-03 notes; v1.85; roadmap 7 |
| 10-4 | Recovery-key rotation | `vault`, `scripts/vault/chpass.sh`, Settings → Vault, Postman 14 | none (IT on throwaway images) | $0 | SEC-06 note; v1.86 |
| 10-5 | `--region`, one server at a time, rates by region, README | scripts, `ai.selfhost`, Settings | create in us-east-2, Start, subset run, Stop (when the quota is approved) | ~$0.50 | AI-08, AI-12 notes; v1.87 |
| 10-6 | g6.12xlarge and gpt-oss-120b options, timeouts per type and model | template, scripts, settings | create, chat and categorization runs, destroy (**when the quota is approved**) | ~$5 | QA-04 note; v1.88 |
| close-out | `docs/eval/tiers.md`, README, requirements, roadmap 8, CLAUDE.md, newman on every folder, tag `phase-10-done` when the owner says | docs | none | $0 | roadmap 8 |

If 10-6's quota isn't approved by close-out, Phase 10 closes without it, and 10-6 moves to §8's list with its text kept.

---

## 11. Open questions

1. **The layer rule.** Plan with Corgi builds parts of Layer 2 (GOAL-01, 02, 03, 04, 07, INV-01) while Layer 1 still has
   future phases (11 to 13, 15), and the roadmap says no layer ships before the one below is complete. Record an
   explicit exception in the roadmap (as Phase 7b did for the Goals tab shell), with the new GOAL-08 in Phase 10?
   *Recommended: yes.*
2. **Step 10-2's target.** Wrong 0 with review at most 6 needs the Greenvale refund answered right; the cap alone sends
   it to review (7). Accept wrong 0 with review 7 as a pass for (b)?
3. **The categorization dev set has 16 AI items.** Add synthetic rows the rules don't match (a dev set version 2), so
   the 0.85 and the cap are measured on more? It breaks comparison with the Phase 4 and Phase 9 runs (they'd be
   reported against version 1). *Recommended: not in Phase 10.*
4. **The cut menu.** 10, 25, 50 and 100 percent of a category's baseline. Or a different menu?
5. **A partial month's check-in** shows "so far" figures with no pace projection; GOAL-03's `ON_TRACK`/`OFF_PACE`
   waits for the Goals phase and its Layer 4 question. *Recommended: yes.*
6. **One plan at a time**, revised by versions. *Recommended: yes.*
7. **Rotation while mounted.** If `hdiutil chpass` refuses an attached image, rotation locks for a few seconds (423) and
   remounts with the new key. Acceptable?
8. **One Self-hosted server at a time** across regions (create refuses while one exists elsewhere), keeping one
   Tailscale name and one region setting. *Recommended: yes.*

---

## 12. Decisions at approval (2026-11-05)

On §11's questions:

1. **Yes.** The roadmap records Plan with Corgi as an explicit Layer 2 exception, as Phase 7b did for the Goals tab
   shell, with the new GOAL-08 in Phase 10 (roadmap version 7, step 10-3).
2. **Yes.** Wrong 0 with review 7 is a pass for 10-2. The rule is **wrong 0 first**; the review count is reported, not
   targeted (§3.5).
3. **No.** The categorization dev set stays version 1 in Phase 10.
4. **The menu stands:** 10, 25, 50 and 100 percent.
5. **Yes.** A partial month's check-in is `SO_FAR` only; `ON_TRACK`/`OFF_PACE` waits for the Goals phase.
6. **Yes.** One plan, versioned.
7. **Yes.** If `hdiutil chpass` needs the image detached, rotation locks for the seconds it takes and remounts with the
   new key; the vault IT decides which path (§5.2).
8. **Yes.** One Self-hosted server at a time; `create.sh` refuses while one exists in another region (§6).

One change to 10-3 (§4.2, §4.8): **`draft_plan` and `get_plan_baseline` never make the model do date arithmetic.**
"$60,000 in 10 years" must not require the model to turn 10 years into a target month. `draft_plan` takes exactly one
of `target_month` (`YYYY-MM`) or `months_ahead` (1 to 600); the tool returns the resolved target month, grounded like
any figure. The same applies to any "how many months are left" the model would otherwise count: the plan tools return
it. A `ToolArguments` test covers both forms and both given.

Step 10-1 starts at approval. No AWS or model call by me; the owner runs the rerun.

---

## 13. Step notes

### Step 10-1 notes: "[amount]" copied from the history (code)

No AWS or model call. The rerun is the owner's (§2.4), against `chat-dev-bb822aa6b00e-20261103T120333Z`.

**What changed** (as §2.2, with one wording change):
- `AnswerCheck` gains `placeholders`: the history's `[amount]`, `[percent]` and `[number]` (exact tokens, any case) found
  in the answer as checked. `grounded()` needs no unmatched figure **and** no placeholder; `ungroundedCount()` counts
  both, and is what a turn stores as `ungrounded`.
- `AnswerGrounding.retryMessage`: placeholders alone get `PLACEHOLDER_PREFIX` + the placeholders +
  `PLACEHOLDER_INSTRUCTION`; figures and placeholders together get the figures' message unchanged, then
  `PLACEHOLDER_NOTE` + the placeholders. The figures' message still ends its list at `RETRY_INSTRUCTION`, so the eval
  reads both forms.
- **The wording differs from the first draft of §2.2.** The draft began "your answer contains", which
  `RetryWordingTest` refuses (it reads as a correction; dev run 5's "You're right, I apologize"). The message now
  follows the figures' message: "Automatic check, not a message from the user: these are placeholders from the earlier
  conversation, not figures: [amount]. Write the answer to the user's question again from the start, ...". §2.2 is
  amended.
- The monthly summary (`MonthlySummaryService`) uses the same check; its own retry lists placeholders with the figures.
- History, the summary, `chat-10` and what leaves the machine are unchanged. `ConversationMemoryTest` and
  `SummaryFigureStripperTest` pass unchanged.

**The eval report**: a "Placeholder retries" column in the summary; in the grounding table, "Retries for a history
placeholder" and "Shown answers with a history placeholder". A turn retried for one shows "retried for [[amount]]" with
the rejected first answer, as figure retries do. Run on bb822aa's data, the last row would have read 2 (d-m03 turns 2
and 3); after the fix it should read 0.

**Tests**: backend 3,808 tests, 0 failures, 0 errors, 1 skipped (Maven summary; 3,789 at `phase-9-done` plus 19; the
skip is `CfnLintTest`); frontend 355 tests (34 files), all passed; no UI change, so no layout run.
- `AnswerGroundingTest` (`HistoryPlaceholders`, `Retry`): each placeholder and its case; a placeholder beside a
  grounded figure; brackets that are not placeholders (`[the amount]`, `[ amount ]`, `[amounts]`, the shortened-answer
  marker); both retry forms word for word.
- `RetryWordingTest`: the placeholder messages pass the no-apology check, say they are automatic, and their fixed text
  has no figure.
- `ChatServiceTest`: d-m03 turn 2's shape (the figure in this turn's result, `[amount]` written, one retry, the figure
  shown); the placeholder twice (fallback, nothing with `[amount]` stored); turn 3's shape (a follow-up copies the
  placeholder with no tool call; the retry asks for a tool; the model calls one).
- `ChatEvalScoringTest`, `ChatEvalMetricsTest`, `ChatEvalReportTest`: both retry forms parsed; the counts; the rows.

**The Vitest timeouts again (phase-9.md §11, a fifth sighting).** The first `npm test` after Maven's `clean verify`
had finished: 56 tests in 17 files, all "Test timed out in 5000ms", in a 15-minute run with the load average near
100 (Postman's renderer was the busiest process). None of the files is touched by this step. Report kept as
`frontend/test-reports/vitest-2026-11-05-10-1-timeouts.json` (git-ignored). Rerun once the load average was under 6:
355 of 355 in about a minute. So "Maven fully finished first" is not enough on its own; the machine's load decides it.
**That first run's `npm test | tail` also reported exit 0**: the pipe hid the failure. Steps now record npm's own exit
code.
