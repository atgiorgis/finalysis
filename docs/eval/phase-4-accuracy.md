# Phase 4 categorization accuracy

CAT-05 · Phase 4 step 9 · 2026-10-19 · model `us.anthropic.claude-haiku-4-5-20251001-v1:0` (categorization),
`amazon.titan-embed-text-v2:0` (embeddings), temperature 0, prompt version 3, `min-confidence` 0.80.

## Headline: the holdout

The holdout (`sample-data/categorization/holdout.csv`, 150 rows, frozen) was evaluated **once**, after every
setting was chosen on the dev set. It will not be evaluated again.

| Configuration | Overall correct | Coverage | Accuracy on categorized | Wrong | Review | Calls | Est. cost |
|---|---:|---:|---:|---:|---:|---:|---:|
| Rules only | **44.7%** (67/150) | 44.7% | 100% | 0 | 0 | 0 | $0 |
| Rules + AI | **93.3%** (140/150) | 95.3% | **97.9%** (140/143) | **3** | **7** | 4 | ~$0.01 |

- "Review" means the AI answered below 0.80, so the txn stays uncategorized and waits in the review queue.
  No holdout row was left uncategorized without a review item.
- **The 0.80 threshold, chosen on dev, held.** The holdout answers between 0.70 and 0.80 were right for
  1 row of 7. At or above 0.80 they were right for 73 of 76 rows (96.7% in 0.80–0.90, 95.7% in 0.90–1.00).
- **The 3 wrong rows are one merchant** (a mobile phone bill, three description formats), answered
  Subscriptions instead of Utilities at confidence 0.85–0.92. It is a boundary between two plausible
  categories, not a nonsense answer, but it is wrong, it is confident, and it went into the totals.
- Usage: 76 items in 4 calls, 4,104 input and 1,331 output tokens, about $0.0108 at on-demand prices
  (`backend/src/test/resources/eval/model-prices.json`; check the AWS pricing page before relying on it).

## Dev set (tuned on this set)

`sample-data/categorization/dev.csv`, 148 rows. The seed rules, cleaning patterns, prompt and the
threshold were all tuned on it, so these numbers are optimistic by construction.

| Configuration | Overall correct | Coverage | Accuracy on categorized | Wrong | Review | Calls | Est. cost |
|---|---:|---:|---:|---:|---:|---:|---:|
| Rules only | 87.2% (129/148) | 87.2% | 100% | 0 | 0 | 0 | $0 |
| Rules + AI | 95.9% (142/148) | 95.9% | 100% | 0 | 6 | 1 | ~$0.002 |

The threshold came from this run's sweep (step 9c notes in `docs/plans/phase-4.md`): at 0.70 the dev set had
3 wrong answers, all between 0.70 and 0.75; at 0.80 it had none, for 2 fewer right answers. A row in review costs
the user one correction; a wrong category is silent. The sweep rests on 19 answered rows, so 0.80 is a first
setting, not a calibration.

## Examples and direct assignment: not exercised

The harness also ran rules + AI with similar-merchant examples, and with direct assignment on top. **Neither
was exercised:** on dev and on the holdout, 0 items had an eligible example at or above the 0.65 similarity floor
(the nearest were 0.60–0.65). Those variants sent the same prompts as rules + AI and got the same answers.
This evaluation makes no claim that examples help, or that they hurt. The synthetic sets give each merchant
distinct names, so close neighbours are rare by design; real data, where a user's corrections accumulate, is
where the feature would have to be measured.

## Informal real-data spot check

The owner ran the app on their own statements and reported counts only. The data stays outside the repository;
no merchant name or description from it appears here or anywhere in the repo. **It is informal and
unlabeled:** there is no answer key, so it gives no accuracy figure.

| | |
|---|---|
| Transactions | 408 |
| Categorized by rules | 38.5% |
| Categorized by the AI | 171 more, in 6 calls, about $0.02 |
| Merchants sent to review at 0.80 | 43 of 134 |
| Blocked by the redaction guard (never sent) | 1 |
| Manual spot check | about 30 AI categories; no obvious errors found |

The same check found a bug, fixed in step 9d: marking a txn "not a transfer" left its rule-assigned Transfer
category in place (`docs/plans/phase-4.md`, step 9d notes).

## Findings

1. **The synthetic dev set was much easier for rules than real data or the holdout.** Rules covered 87.2% of
   dev, 44.7% of the holdout and 38.5% of the real data. The seed rules were written against dev, and the holdout
   shares no distinctive merchant word with it, so dev coverage mostly measures fit to dev. The holdout and the
   real data agree with each other: rules alone cover well under half; the AI carries the rest.
2. **High-confidence errors are the remaining risk.** The threshold removes most bad answers (6 of the 7
   holdout rows below 0.80 were wrong), but 3 holdout rows were wrong at 0.85–0.92 and were applied. The review
   queue never sees them. A correction fixes the merchant for good (it becomes a rule), but only once the user
   notices.
3. **Cost is not a constraint at this scale.** About a cent for 150 synthetic rows and about two cents for 408
   real ones.

## Method

- **Two labeled synthetic sets** with disjoint merchant-name pools (step 1b), committed before any seed rule
  was written. Rules, cleaning, prompt and threshold were tuned on dev only.
- **The holdout is frozen:** `CategorizationSetsTest` pins its SHA-256 (`2020780e…92af`), and a test keeps
  every class from reading it except the live evaluation and the checks on the sets themselves (hash, names,
  categories). The same author wrote the sets and the
  rules, so this is procedural protection, not a blind test.
- **Run once.** The live harness (`CategorizationLiveEvalIT`, opt-in `-Pai-eval`, never in `verify`) refuses a
  holdout run without `-Deval.holdout.confirm=true`, with a changed hash, with a dirty working tree, or while any
  `holdout-*` result exists in `docs/eval/`. Both result files are committed, so a second run is refused.
- **Settings recorded, no tuning after.** Each result file records the commit, the dirty flag and every AI
  setting. The holdout ran at `94ee19a`, which differs from the dev run's `fab8a1c` only in `.gitignore`,
  `docs/eval/README.md` and the Postman collection: the categorization code, rules and prompt were the same.
  Nothing was changed in response to the holdout result; the holdout run has no threshold sweep, by design.
- **Each run starts from the same state:** a fresh Testcontainers database, the set imported as monthly CSV
  statements through the real import path (cleaning and seed rules run as after an upload); labels are used for
  scoring only.
- **In the build:** `CategorizationAccuracyTest` re-checks the dev rules-only result (129/148, 0 wrong) on every
  `./mvnw verify`, with no AI. It never reads the holdout.

## Result files

- Dev: [`dev-fab8a1cdac7a-20261019T160014Z.md`](dev-fab8a1cdac7a-20261019T160014Z.md) (and `.json`, every row)
- Holdout: [`holdout-94ee19afc572-20261019T212935Z.md`](holdout-94ee19afc572-20261019T212935Z.md) (and `.json`)
