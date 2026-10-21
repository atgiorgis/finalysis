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
