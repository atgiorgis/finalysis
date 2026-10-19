# Categorization evaluation results

Results of the opt-in live accuracy evaluation (CAT-05; `docs/plans/phase-4.md`, step 9a notes), one pair of
files per run:

- `<set>-<commit>-<timestamp>.json`: everything, row by row, with every AI setting, the commit and whether the
  working tree was dirty;
- `<set>-<commit>-<timestamp>.md`: the tables (summary, by source, examples, nearest examples, confidence bands,
  and the min-confidence sweep for dev runs). "Nearest examples" (step 9c) is a diagnostic of the examples feature,
  not an accuracy measure.

Synthetic data only (`sample-data/categorization/`). The result files are git-ignored: they stay on this machine
and never make the working tree dirty. The holdout is evaluated once: a holdout run refuses to start while any
`holdout-*` file exists here, so don't delete one. The holdout also needs a clean working tree (ignored files don't
count).
