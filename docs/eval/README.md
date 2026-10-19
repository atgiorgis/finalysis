# Categorization evaluation results

Results of the opt-in live accuracy evaluation (CAT-05; `docs/plans/phase-4.md`, step 9a notes), one pair of
files per run:

- `<set>-<commit>-<timestamp>.json`: everything, row by row, with every AI setting, the commit and whether the
  working tree was dirty;
- `<set>-<commit>-<timestamp>.md`: the tables (summary, by source, examples, nearest examples, confidence bands,
  and the min-confidence sweep for dev runs). "Nearest examples" (step 9c) is a diagnostic of the examples feature,
  not an accuracy measure.

The Phase 4 summary is [phase-4-accuracy.md](phase-4-accuracy.md).

Synthetic data only (`sample-data/categorization/`). Result files are committed as evidence (the dev and holdout
runs of Phase 4 step 9 are), so a new run leaves the working tree dirty until its files are committed or deleted.
The holdout is evaluated once: a holdout run refuses to start while any `holdout-*` file exists here, and both
holdout files are committed, so it can't run again.
