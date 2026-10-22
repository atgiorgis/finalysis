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
