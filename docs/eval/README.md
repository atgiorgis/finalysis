# Evaluation results

## Categorization (Phase 4)

Results of the opt-in live accuracy evaluation (CAT-05; `docs/plans/phase-4.md`, step 9a notes), one pair of
files per run:

- `<set>-<commit>-<timestamp>.json`: everything, row by row, with every AI setting, the commit and whether the
  working tree was dirty;
- `<set>-<commit>-<timestamp>.md`: the tables (summary, by source, examples, nearest examples, confidence bands,
  and the min-confidence sweep for dev runs). "Nearest examples" (step 9c) is a diagnostic of the examples feature,
  not an accuracy measure.

The Phase 4 summary is [phase-4-accuracy.md](phase-4-accuracy.md).

Phase 5's analytics totals against the sample data's answer key (ANA-06) are in
[phase-5-totals.md](phase-5-totals.md), with the alerts against its labelled anomalies. They come from
`AnalyticsSampleDataTest` and `AlertSampleDataTest`, which run in every `./mvnw verify` and call no model.

Synthetic data only (`sample-data/categorization/`). Result files are committed as evidence (the dev and holdout
runs of Phase 4 step 9 are), so a new run leaves the working tree dirty until its files are committed or deleted.
The holdout is evaluated once: a holdout run refuses to start while any `holdout-*` file exists here, and both
holdout files are committed, so it can't run again.

## Chat (Phase 6)

Results of the opt-in chat evaluation (`docs/plans/phase-6.md` §13, step 8 notes), files prefixed `chat-`:

- `chat-<set>-<commit>-<timestamp>.json` and `.md`: one run of the dev or holdout question set
  (`sample-data/chat/`) against one chat model: the model id, prompt version, every chat setting and the commit; the
  summary, per category, your own questions apart, grounding, refusals, tool choice, cost and latency; every answer as
  shown, for reading. Scoring is deterministic: no model judges.
- `chat-sweep-dev-<commit>-<timestamp>.json` and `.md`: the dev-only retrieval sweep of the search floor (0.30 to
  0.60), embeddings only.

The chat holdout is evaluated once: a holdout run refuses to start while any `chat-holdout-*` file exists here (the
categorization holdout's `holdout-*` files don't count), and it needs `-Deval.holdout.confirm=true` and a clean tree.

### Run 1: the baseline before the step 8b fixes

The first dev run and the first sweep, committed in step 8c as the baseline for the comparison write-up. They ran on
commit `0de5269` (step 8, the evaluation harness; the files say the working tree was dirty), dev set version 1,
prompt `chat-3`, before any of step 8b's fixes (search index, account-name words, `month` on every range tool,
shares, quarters) and step 8c's (search and merchant totals over every month with data, prompt caching):

- [chat-dev-0de526931d87-20261022T113958Z.md](chat-dev-0de526931d87-20261022T113958Z.md) (and `.json`): Haiku 4.5,
  46 of 58 questions and 54 of 66 turns correct, 954,455 input and 12,860 output tokens, $1.02, uncached;
- [chat-sweep-dev-0de526931d87-20261022T114528Z.md](chat-sweep-dev-0de526931d87-20261022T114528Z.md) (and `.json`):
  the search floor sweep on Titan's bare merchant texts, 8 of 25 targets found at 0.30.

They are evidence, not a target: later dev runs are compared with them, and they are never edited. Runs from step 8c on
also record whether prompt caching was on and split input tokens into uncached, cache reads and cache writes.

### Run 2: after steps 8b and 8c

The second sweep and dev run, committed in step 8d. They ran on commit `2e354e2` (step 8c; working tree dirty with the
owner's price file edits, committed with them), dev set version 2, prompt `chat-5`, prompt caching on:

- [chat-sweep-dev-2e354e2fc5d5-20261022T175440Z.md](chat-sweep-dev-2e354e2fc5d5-20261022T175440Z.md) (and `.json`):
  search on the step 8b documents, 11 of 25 targets found at 0.30 and 6 at 0.40 (the floor stays 0.40);
- [chat-dev-2e354e2fc5d5-20261022T175514Z.md](chat-dev-2e354e2fc5d5-20261022T175514Z.md) (and `.json`): Haiku 4.5,
  55 of 58 questions and 63 of 66 turns correct, 83,840 uncached input, 975,921 cache-read, 2,271 cache-write and
  10,947 output tokens, $0.24.

A Sonnet 4.6 run from the same session is not committed: every turn stopped on the cost limit before its first model
call, so it measured that setting, not the model. The check prices a round's estimated input at the cache-write price
plus the whole 1,500-token output cap; at Sonnet 4.6's prices any round over about 7,330 estimated tokens passes $0.05,
and every first round of the dev set is larger (the fixed prefix alone is about 5,950 tokens). Step 8d makes such a setup refuse to start and scores a limit-stopped turn as
never correct.

### Run 3: after step 8d

Two dev runs, committed in step 8e. Both ran on commit `7f09a06` (step 8d; working tree dirty), dev set version 3,
prompt `chat-6`, prompt caching on:

- [chat-dev-7f09a06f2e08-20261022T222033Z.md](chat-dev-7f09a06f2e08-20261022T222033Z.md) (and `.json`): Haiku 4.5,
  52 of 58 questions and 60 of 66 turns correct, search 4 of 8; 78,648 uncached input, 968,752 cache-read, 9,944
  cache-write and 10,214 output tokens, $0.24; turn cost limit $0.05.
- [chat-dev-7f09a06f2e08-20261022T222401Z.md](chat-dev-7f09a06f2e08-20261022T222401Z.md) (and `.json`): Sonnet 4.6,
  52 of 58 questions and 60 of 66 turns correct, search 3 of 8; 80,648 uncached input, 998,006 cache-read, 9,933
  cache-write and 12,182 output tokens, $0.76; turn cost limit $0.15 (`-Deval.maxTurnCost`, this run only).

Read with step 8e (docs/plans/phase-6.md): the brand searches of d-s02, d-s03, d-s05 and d-s08 found nothing because
the name match needed every word of the search in one merchant name; Sonnet's d-m04 turn 3 was scored correct on the
pronoun "one" ("one for $8.00"), and is wrong under step 8e's scoring; Haiku's d-c06 "(unknown)" parameter was a
parameter `get_account_totals` doesn't have, not an unknown tool. Revision 4 rescores d-t04 (Haiku's "cost less in July"
fails) and d-t05 (Haiku's 38.38 and 2.39 passes). These reports keep the scores they were written with.

### Run 4: after step 8e

Two dev runs, committed in step 8f. Both ran on commit `cc040ba` (step 8e), dev set version 4, prompt `chat-7`,
prompt caching on:

- [chat-dev-cc040bab7a2b-20261023T081207Z.md](chat-dev-cc040bab7a2b-20261023T081207Z.md) (and `.json`): Haiku 4.5
  (clean tree), 53 of 58 questions and 61 of 66 turns correct, search 6 of 8; 81,557 uncached input, 996,317
  cache-read, 10,221 cache-write and 10,913 output tokens, $0.25; turn cost limit $0.05.
- [chat-dev-cc040bab7a2b-20261023T081549Z.md](chat-dev-cc040bab7a2b-20261023T081549Z.md) (and `.json`): Sonnet 4.6
  (working tree dirty), 57 of 58 questions and 65 of 66 turns correct, search 8 of 8; 79,144 uncached input, 1,003,442
  cache-read, 10,210 cache-write and 11,543 output tokens, $0.75; turn cost limit $0.15 (`-Deval.maxTurnCost`, this
  run only).

Read with step 8f (docs/plans/phase-6.md): Haiku's d-s05 took 17 from `get_top_merchants`' 6-month default where
search and merchant totals cover all data (18); Haiku's d-m03 turn 2 saw its own turn-1 answer cut at 1,000
characters, just before the duplicate alert, and answered from memory; revision 5 accepts Haiku's d-t01 (356.91 and
93.83). On these runs step 8f makes Sonnet 4.6 the default chat model at $0.15 a turn, with Haiku 4.5 at $0.05 as the
budget option. These reports keep the scores they were written with.

### Run 5: after step 8f

Two dev runs, committed in step 9a. Both ran on commit `03ac886` (step 8f), dev set version 5, prompt `chat-8`,
prompt caching on:

- [chat-dev-03ac8864c009-20261023T085241Z.md](chat-dev-03ac8864c009-20261023T085241Z.md) (and `.json`): Haiku 4.5
  (clean tree), 56 of 58 questions and 64 of 66 turns correct, search 7 of 8; 85,539 uncached input, 1,018,302
  cache-read, 10,470 cache-write and 10,555 output tokens, $0.25; turn cost limit $0.05 (`-Deval.maxTurnCost`, the
  budget option).
- [chat-dev-03ac8864c009-20261023T085623Z.md](chat-dev-03ac8864c009-20261023T085623Z.md) (and `.json`): Sonnet 4.6
  (working tree dirty), 56 of 58 questions and 64 of 66 turns correct, search 8 of 8; 86,858 uncached input, 1,048,408
  cache-read, 10,459 cache-write and 12,408 output tokens, $0.80; turn cost limit $0.15 (the app's setting).

Read with step 9a (docs/plans/phase-6.md): three shown answers began "You're right, I apologize" or "You're right."
(Sonnet d-s08 and d-m03 turn 2, Haiku d-x08), the model answering the grounding retry as if the user had corrected it;
step 9a rewords both retries and the report now counts such answers. Sonnet's d-c08 failed only because its phrase
check met "more in **March 2026**"; under step 9a's scoring it is correct. d-x06 (a clarifying question where a refusal
is expected) is the remaining miss in both runs, and Haiku's d-s03. These reports keep the scores they were written
with.
