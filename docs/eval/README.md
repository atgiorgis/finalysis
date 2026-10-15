# Categorization evaluation results

Results of the opt-in live accuracy evaluation (CAT-05; `docs/plans/phase-4.md`, step 9a notes), one pair of
files per run:

- `<set>-<commit>-<timestamp>.json`: everything, row by row, with every AI setting, the commit and whether the
  working tree was dirty;
- `<set>-<commit>-<timestamp>.md`: the tables (summary, by source, examples, confidence bands, and the
  min-confidence sweep for dev runs).

Synthetic data only (`sample-data/categorization/`). The holdout is evaluated once: a holdout run refuses to start
while any `holdout-*` file exists here. Commit dev results before a holdout run, since the holdout needs a clean
working tree.
