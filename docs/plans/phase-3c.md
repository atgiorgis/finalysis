# Phase 3c plan: transfer detection and review queue

Status: **approved** (2026-10-13, answers under "Decisions at approval" at the end); the
standalone Phase 3b step 8 task (PDF security tests) and steps 1–5 are done; next: step 6. Branch `v1`.
Requirements as of `docs/requirements.md` v1.28 (competing candidates and possible-match dismissal added
to ING-07, step 6).

Scope: ING-05 (transfer detection and links), ING-07 (review-queue API), GR-04 (transfers are never
spending or income, enforced through `txn.is_transfer`), plus the review actions on ING-13 unparsed
uploads. Out of scope: description-based transfer hints and merchant cleaning (Phase 4,
categorization rules), using `is_transfer` in totals (Phase 5 analytics), the upload and review UI
(Phase 7), chatbot tools (Phase 6; they stay read-only and never call these endpoints), PDF parsing
(3b, paused). **No AI anywhere in 3c.**

Working rules (unchanged): one step per commit (small follow-ups are fine), run
`cd backend && ./mvnw clean verify` and `cd frontend && npm test` before every commit, report test
counts, push `v1`, stop for review before the next step.

---

## 1. Model

### 1.1 What a transfer is in the database

- A transfer is a row in `transfer_link`. **`txn.is_transfer` is true exactly when the txn is on a
  link**, on either side, whatever the link's status. Nothing else sets it: not the importer, not
  categorization, not the user directly.
- A database trigger keeps `is_transfer` in step with `transfer_link` (§5). That covers every path,
  including links removed by `ON DELETE CASCADE` when a statement is deleted, which application code
  would never see. JPA maps `is_transfer` read-only.
- A txn belongs to at most one link (V3 trigger, unchanged). Detection is written so it never tries
  to break that rule; the trigger is the backstop.

### 1.2 Link states

| status | sides | method | confirmed_at | Meaning |
|---|---|---|---|---|
| MATCHED | out + in | AUTO | null | Found by detection, not yet looked at by the user |
| MATCHED | out + in | AUTO | set | Found by detection, confirmed by the user |
| MATCHED | out + in | USER | set | Linked by hand |
| UNMATCHED | out **or** in | USER | set | One-sided: the user says it's a transfer to or from an account that isn't in the app |

- `method` says who paired the sides (or, for UNMATCHED, who marked it). `confirmed_at` says the user
  has looked at it. A USER link is always confirmed.
- No AUTO UNMATCHED links, in 3c or later: core code has no way to know that a lone txn is a
  transfer (§2.6). From Phase 4, categorization *suggests* one-sided transfers in the review queue,
  and the user creates the link (§2.6).
- **V9 change:** today `out_txn_id` is NOT NULL, so a one-sided *incoming* transfer (money in from a
  brokerage that isn't in the app) can't be stored. V9 makes either side nullable, with at least one
  side present and MATCHED meaning both.

### 1.3 User decisions that detection respects

- **Rejected pair** (`transfer_rejection`, V9): written when the user unlinks a MATCHED link. Detection
  never proposes that exact pair again; either txn can still match something else.
- **Not a transfer** (`txn.transfer_excluded`, V9): detection never considers the txn. A CHECK
  constraint makes `is_transfer AND transfer_excluded` impossible.

---

## 2. Detection (ING-05)

### 2.1 Candidate pairs: bank-agnostic rules

An **out** txn and an **in** txn are a candidate pair when all of these hold:

| # | Rule | Why |
|---|---|---|
| 1 | `out.amount < 0` and `in.amount = -out.amount` (exact cents) | Equal and opposite. Zero rows are never candidates |
| 2 | Different accounts | A reversal within one account is not a transfer |
| 3 | `abs(eff(in) − eff(out)) ≤ window`, where `eff = coalesce(post_date, txn_date)` | Posting dates are what both banks record; purchase dates don't apply to transfers |
| 4 | `out` account type is CHECKING or SAVINGS; `in` is CHECKING, SAVINGS or CREDIT_CARD | Checking ↔ savings, and checking/savings → card payment. Card → anything (cash advance, balance transfer) is left to a manual link: a card's money out is overwhelmingly purchases |
| 5 | Neither side's `source_section` is FEE or INTEREST | Structural: fees and interest are never transfers |
| 6 | Neither side is `transfer_excluded`; the pair isn't in `transfer_rejection` | User decisions (§1.3) |
| 7 | Neither side is on a link, except the present side of an UNMATCHED link | Never link a txn twice (§2.4 for UNMATCHED) |

Statement status doesn't matter: rows of a NEEDS_REVIEW statement are real rows.

**Card payments** need no special case: a payment is money out on checking and money in on the card,
in the card's PAYMENT section (OFX and CSV put every positive card row there). Rule 4 allows it.

### 2.2 Window: default 5 days, configurable

- `finalysis.transfers.window-days`, default **5**, allowed 0–14, symmetric (the card may credit the
  payment before the checking debit posts).
- Why 5: the sample data's longest gap is 3 days (Summit, 09-25 → 09-28). Real payments take up to
  three business days; a weekend plus a holiday turns that into 5–6 calendar days. A wider window
  only adds chances of a coincidental match.
- Measured on the sample data: windows of 3, 5, 7 and 10 days all give exactly the 18 answer-key
  pairs, with no false or ambiguous candidates. So the edge-case fixtures (§7) have to test the
  boundary.

### 2.3 Choosing among candidates

Candidate pairs form a graph of out txns and in txns. Each connected group is resolved on its own,
deterministically:

1. **One out, one in:** linked (MATCHED, AUTO).
2. **k outs and k ins, all outs on one account and all ins on one account** (e.g. two $250 transfers
   to savings in the same week): pair them in date order (the i-th earliest out with the i-th earliest
   in; ties by txn id). Linked only if every resulting pair is itself a candidate; otherwise rule 3.
   Every pairing gives the same `is_transfer` values and the same totals, so this is safe. Date order
   is the natural reading.
3. **Anything else** (e.g. a −200.00 with a +200.00 on savings *and* a +200.00 on the card): **no link.**
   The group goes to the review queue as `TRANSFER_CANDIDATES` with its candidate pairs (§4). Its txns
   keep `is_transfer = false` until the user decides, because guessing the account would be wrong
   half the time and the user would never notice.

The groups are not stored. They are recomputed from the same query whenever the queue is read, so they
can't go stale (§4.1).

### 2.4 One-sided transfers

- A transfer whose other account isn't in the app (a card you haven't added, a brokerage, a partner's
  account) has no counterpart. **Core detection never creates these:** without description hints there
  is nothing to tell a $1,200 rent payment from a $1,200 transfer.
- So until the user acts, such a txn is **not** a transfer (`is_transfer = false`), and it counts as
  spending. That's the cost of keeping core bank-agnostic; the Phase 4 suggestion closes the gap
  (§2.6).
- **The user marks it:** `POST /api/transfers` with only one side → UNMATCHED, USER, confirmed;
  `is_transfer = true` on that txn.
- **Completion:** if the counterpart arrives later (the account is added and its statements imported),
  detection treats the UNMATCHED link's present side as a candidate. If its group resolves to a
  single pair, the link becomes MATCHED with method AUTO and `confirmed_at` cleared, since the pairing
  itself is new and unreviewed. Otherwise it stays UNMATCHED and the group is listed for review.

### 2.5 When detection runs

- **After every successful import** (201), in its own transaction **after the import commits**: an
  ingestion event (`StatementImported`) handled by a `@TransactionalEventListener(AFTER_COMMIT)` in
  the transfer package. Ingestion doesn't depend on the transfer package.
- **After a statement is deleted** (`StatementDeleted`, §4.4).
- **On demand:** `POST /api/transfers/detect`, for a changed window or after undoing "not a transfer".
- **Scope:** every eligible txn of every account, not only the new statement's rows. A group can join
  old and new rows, so a full pass is the simple correct choice; it is one SQL self-join, cheap at
  personal-finance volumes. No new index unless a test shows a need.
- **Idempotent:** existing links are never changed by detection, except completing an UNMATCHED one
  (§2.4). A second run creates nothing.
- **Late statements** need nothing special: the card statement imported a month after the checking
  statement is picked up by its own import's run.
- **Concurrency:** detection and every transfer write take one transaction-level advisory lock
  (`pg_advisory_xact_lock` on a fixed key), so two runs can't interleave. Statement delete takes the
  account lock first, then this lock; detection never takes account locks, so the order is fixed and
  can't deadlock. An import committing during a run is covered by that import's own run.
- **Failure:** a detection error is logged (counts and ids only, never descriptions) and doesn't undo
  or fail the import, which has already committed. The next run catches up.
- `ImportSummary` is unchanged. Links are read through `GET /api/transfers`.

### 2.6 What core uses, and where hints go

- **Core uses only:** amount, sign, account, account type, `source_section` (FEE/INTEREST),
  `coalesce(post_date, txn_date)`, and the link, rejection and exclusion state.
- **Core never reads** `raw_description`, `merchant`, `counterparty`, `external_id`, check numbers or
  institution names. The candidate query selects no text column.
- **Enforced by a test:** the full sample set is imported twice, once as is and once with every
  description replaced by random text (same amounts and dates). Detection must give identical links.
- **Phase 4 hook (approved): suggested one-sided transfers (CAT-08).** A txn categorized into a
  Transfer-kind category that has no transfer link goes to the review queue as a **suggested
  one-sided transfer**. This covers card payments to cards that aren't in the app and transfers to
  accounts whose statements aren't imported. The user resolves it with the §3 actions: mark it
  one-sided, link it by hand, or mark it not a transfer (an excluded txn is never suggested).
  - Categorization (rules, which may be bank-specific, or AI) only *suggests*; it never creates a
    link or sets `is_transfer`.
  - Core detection is unchanged and still never reads descriptions.
  - The 3c queue code keeps item kinds open (a sealed `ReviewItem` with a `kind` field), so Phase 4
    adds `SUGGESTED_ONE_SIDED_TRANSFER` as one more kind without changing the others.
- Backlog, not 3c: the OFX `BANKACCTTO`/`CCACCTTO` last four is structured
  standard data that could narrow a candidate to one account. It would need a new parser output field.

### 2.7 Known false positive

A purchase on checking and an unrelated refund of the exact same amount on a card within 5 days pass
every rule and get linked. This is rare (exact cents, two accounts, five days), visible (the link is
AUTO and unconfirmed), and correctable once (unlink records the rejection). It is documented, and a
fixture shows it on purpose (§7).

### 2.8 Validation against the answer key

- The four sample accounts, with all their CSV statements imported, give exactly the answer key's 36
  `is_transfer = true` rows as 18 MATCHED AUTO links, with the right account pairs. Every other row
  has `is_transfer = false`.
- The same result for several import orders: checking first, cards first, interleaved by month, and
  savings last (late arrival).
- A second detection run creates nothing.
- The OFX/QFX variant (Northfield checking OFX + Apex QFX + savings and Summit CSV) gives the same
  links.

---

## 3. User actions on transfers (ING-05)

REST endpoints in a new `TransferController`. These are user actions over the API. They are not
chatbot tools: chat stays read-only (SEC-03).

| Method and path | Does | Success | Errors |
|---|---|---|---|
| `GET /api/transfers?status=&confirmed=&accountId=` | List links, newest effective date first | 200 list | 400 bad filter |
| `GET /api/transfers/{id}` | One link | 200 | 404 |
| `POST /api/transfers` `{outTxnId, inTxnId}` | Manual link (MATCHED, USER, confirmed) | 201 + `Location` | see below |
| `POST /api/transfers` `{outTxnId}` or `{inTxnId}` | Mark one-sided (UNMATCHED, USER, confirmed) | 201 + `Location` | see below |
| `POST /api/transfers/{id}/confirm` | Set `confirmed_at` (idempotent) | 200 link | 404 |
| `DELETE /api/transfers/{id}` | Unlink. A MATCHED pair is recorded in `transfer_rejection`; both sides become `is_transfer = false` | 204 | 404 |
| `POST /api/transactions/{id}/not-transfer` | Remove its link if any (recording the rejection if MATCHED), set `transfer_excluded` | 200 `{txnId, transferExcluded, removedLinkId}` | 404 |
| `DELETE /api/transactions/{id}/not-transfer` | Clear `transfer_excluded`; the txn is eligible again at the next detection run | 204 | 404 |
| `POST /api/transfers/detect` | Run detection now | 200 `{linksCreated, unmatchedCompleted, groupsForReview}` | — |

**Manual link rules:**
- Both txns exist (404 otherwise).
- Different accounts.
- Out amount negative, in amount positive, equal and opposite.
- No date window and no account-type rule: it's the user's call.
- Neither txn is already on a link: 409 `transfer-conflict` naming the link id. The user unlinks first;
  nothing is replaced silently.
- Linking clears `transfer_excluded` on both and deletes any rejection of that pair, since the explicit
  action wins over the earlier decision.

**One-sided rules:** the txn exists and isn't on a link (409); `outTxnId` must be negative and
`inTxnId` positive.

**New problem types:**
- 422 `transfer-invalid`: same account, wrong signs, unequal amounts, zero, or neither side given.
  The detail names the rule; no descriptions or amounts.
- 409 `transfer-conflict`.

**Responses:**
- `TransferLinkResponse`: `id`, `status`, `method`, `confirmedAt`, `createdAt`, `daysApart` (null when
  one-sided), and `out` and `in` as `TxnSummary` (or null).
- `TxnSummary`: `id`, `accountId`, `accountName`, `accountType`, `statementId`, `txnDate`, `postDate`,
  `amount` (`@ApiMoney` string), `sourceSection`, `rawDescription`.
- This is the first API to return descriptions. It's the user's own data on a local API, and the user
  needs them to decide. SEC-10 governs logs and error responses, which still never contain them.

All writes take the transfer advisory lock (§2.5).

---

## 4. Review queue (ING-07, ING-13)

### 4.1 One list across accounts

`GET /api/review-queue?kind=&accountId=` → `{counts, items}`

- `counts`: `{statements, unparsedUploads, transferCandidates, competingCandidates, possibleMatches}`, for
  a UI badge later.
- `items`: newest first by `since`, each with a `kind`:

| kind | Included when | Fields |
|---|---|---|
| `STATEMENT` | `status = NEEDS_REVIEW` and `reviewed_at IS NULL` | `statementId`, `accountId`, `accountName`, `fileName`, `periodStart`, `periodEnd`, `balanceCheck`, `warnings`, `since` = `importedAt` |
| `UNPARSED_UPLOAD` | `resolved_at IS NULL` | `uploadId`, `accountId`, `accountName`, `fileName`, `reason`, `since` = `createdAt` |
| `TRANSFER_CANDIDATES` | a candidate group that §2.3 left unresolved (rule 3) | `key` (`txn-<smallest txn id>`), `txns` (`TxnSummary`), `pairs` (`{outTxnId, inTxnId, daysApart}`), `since` = newest `created_at` of its txns |
| `COMPETING_CANDIDATE` | an unconfirmed AUTO link (MATCHED, `confirmed_at` null) with a txn that forms a candidate pair with a txn outside the link, judged as if the link didn't exist (§4.6) | `key` (`link-<linkId>`), `linkId`, `out` and `in` (`TxnSummary`), `competitors` (`TxnSummary`), `pairs` (`{outTxnId, inTxnId, daysApart}`, the link's own pair included), `since` = newest `created_at` of the competitors |
| `POSSIBLE_MATCH` | two UNMATCHED links, one with only an out side and one with only an in side, whose txns would otherwise match (§4.5) | `key` (`links-<outLinkId>-<inLinkId>`), `outLinkId`, `inLinkId`, `out` and `in` (`TxnSummary`), `daysApart`, `since` = newer link's `created_at` |

- Candidate groups are computed by the same query and resolver as detection, read-only, at request
  time (§2.3). The user resolves a group with the transfer actions (§3): link the right pair, mark a
  txn not-a-transfer, or mark one side one-sided. The group then disappears or shrinks on the next
  read.
- **Unconfirmed AUTO links are not queue items** (decided at approval), except as competing candidates
  (§4.6). They are listed with `GET /api/transfers?confirmed=false` (or `confirmed=true`).
- **Phase 4 adds a fourth kind**, `SUGGESTED_ONE_SIDED_TRANSFER` (§2.6, CAT-08). Not built in 3c.

### 4.2 Mark a statement reviewed

`POST /api/statements/{id}/review` → 200 `StatementResponse`

- Sets `statement.reviewed_at` (V10). Status stays NEEDS_REVIEW and the warnings are kept: the status
  records what the import found, and `reviewed_at` records that the user accepted it.
- Only for NEEDS_REVIEW: anything else is 409 `not-reviewable`. Already reviewed: 200, unchanged.
- **Back in the queue:** if a late arrival's re-check (`reconcileNext`) adds a warning to a reviewed
  statement, `reviewed_at` is cleared, because there is something new to look at.
- `StatementResponse` and `StatementListItem` gain `reviewedAt` (additive JSON).

### 4.3 Dismiss an unparsed upload

`POST /api/unparsed-uploads/{id}/dismiss` → 200 `UnparsedUploadResponse`

- Sets `resolved_at = now()` with no statement; V8 already defines that as "dismissed".
- Already resolved or dismissed: 409 `already-resolved`. Re-uploading a dismissed file still gives a
  new open row (V8 rule, unchanged).
- New `GET /api/unparsed-uploads/{id}`. The import's 202 gains `Location: /api/unparsed-uploads/{id}`,
  which 3b deferred to 3c.
- `UnparsedUploadResponse` gains `resolvedAt`, `resolvedStatementId` and `state` (OPEN, RESOLVED,
  DISMISSED).

### 4.4 Delete a statement

`DELETE /api/statements/{id}` → 200 `{statementId, transactionsDeleted, transferLinksRemoved}`

- Allowed for any status, not only queue items: deleting a statement imported into the wrong account
  is the main use.
- Runs under the account lock (as imports do), then the transfer lock.
- **Cascades, all from existing constraints:**
  - `txn` rows (`fk_txn_statement_account ON DELETE CASCADE`);
  - links on either side (`fk_transfer_link_* ON DELETE CASCADE`). The trigger clears `is_transfer`
    on the surviving counterparts. A confirmed or USER link is lost too; the response counts it;
  - rejections (`ON DELETE CASCADE`, V9);
  - an unparsed upload this statement resolved keeps `resolved_at` and loses `resolved_statement_id`
    (V8 `ON DELETE SET NULL`).
- Then `StatementDeleted` triggers detection, so the counterparts can match again.
- The file hash is gone, so the same file can be imported again (to the right account).
- **Not re-run:** the continuity check of the next statement, which may have used this one as its
  predecessor, keeps its stored result. Documented limitation; re-importing the deleted statement
  re-checks only an UNVERIFIED successor, as today.
- The CSV mapping's `used_at` stays set.

### 4.5 Possible matches between one-sided links (added 2026-10-13, step 6)

Two one-sided user links whose txns would otherwise match appear in the queue as a **possible match**
the user can join. Detection still never joins them automatically (step 2 notes: joining would replace
two user decisions with one AUTO link).

- **Would otherwise match:** the out link's txn is negative, the in link's txn is the equal and opposite
  amount, the accounts differ, and the effective dates are within the detection window
  (`finalysis.transfers.window-days`). The pair isn't in `transfer_rejection`. No account-type or
  section rule: the user has already said both are transfers.
- **One txn, several possible matches** (e.g. two one-sided ins of the same amount): each pair is its own
  item; joining one removes the others on the next read, since the joined txns are no longer UNMATCHED.
- **Join** (confirmed 2026-10-13): `POST /api/transfers/join {outLinkId, inLinkId}`
  replaces the two UNMATCHED links with one MATCHED, USER, confirmed link, in one transaction under the
  transfer lock. 201 + `Location`; 404 if either link is missing; 409 `transfer-conflict` if either is no
  longer an UNMATCHED link with that side; 422 `transfer-invalid` if the amounts aren't equal and opposite
  or the accounts are the same (the manual-link rules of §3; no window, as for any manual link).
- **Dismiss** (confirmed 2026-10-13): `POST /api/transfers/join/dismiss {outLinkId, inLinkId}` records the txn pair in
  `transfer_rejection`, so the item disappears and both one-sided links stay. A later manual link of the
  pair still wins (§3).
- **Only these conditions apply** (confirmed): equal and opposite amounts, different accounts, within the
  window, and the pair not already rejected.
- Computed at request time like candidate groups, with a read-only query beside `TransferCandidateQuery`;
  it selects ids, account ids and dates only, never a text column.

### 4.6 Competing candidates for an early link (decided 2026-10-13, step 6)

Answers the step 3 open question with option (c). Detection runs per import and **never changes a link**
(§2.5, unchanged). So when one counterpart arrives first, its pair is linked (AUTO, unconfirmed), and a
second counterpart imported later can't form a review group with it (step 3 notes).

- Instead, the queue lists that link as a **competing candidate**: an unconfirmed AUTO link with a txn
  that would form a candidate pair (§2.1) with a txn outside the link if the link didn't exist. Rule 7 is
  waived for the link's own two txns only; the competitor must pass every rule as usual (on no link, or
  the present side of an UNMATCHED one), and the pair must not be rejected.
- **Resolving it** uses the §3 actions, nothing new:
  - *Confirm* the link: it is no longer unconfirmed, so the item disappears and the link stays.
  - *Unlink* it: the pair is recorded as rejected, and detection runs on the next import or
    `POST /api/transfers/detect`. The freed txn then meets the competitor under the usual rules: one
    candidate each way is linked (AUTO, unconfirmed), anything larger becomes a `TRANSFER_CANDIDATES`
    group. Unlink doesn't run detection itself (§3, unchanged).
  - *Mark the competitor not a transfer*, or link it elsewhere by hand: it stops being a candidate.
- Confirmed and USER links are never listed: the user has already looked at them.
- Computed at request time with a read-only query beside `TransferCandidateQuery` (ids, account ids and
  dates only, never a text column).

---

## 5. Schema

### V9 `V9__transfer_detection.sql` (step 1)

```sql
-- Either side may be absent (one-sided transfers), never both; MATCHED means both.
ALTER TABLE transfer_link
    ALTER COLUMN out_txn_id DROP NOT NULL,
    DROP CONSTRAINT ck_transfer_link_status_matches_in,
    ADD CONSTRAINT ck_transfer_link_has_side CHECK (out_txn_id IS NOT NULL OR in_txn_id IS NOT NULL),
    ADD CONSTRAINT ck_transfer_link_status_sides
        CHECK ((status = 'MATCHED') = (out_txn_id IS NOT NULL AND in_txn_id IS NOT NULL)),
    ADD COLUMN confirmed_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_transfer_link_user_confirmed CHECK (method <> 'USER' OR confirmed_at IS NOT NULL);

ALTER TABLE txn
    ADD COLUMN transfer_excluded BOOLEAN NOT NULL DEFAULT false,
    ADD CONSTRAINT ck_txn_transfer_excluded CHECK (NOT (is_transfer AND transfer_excluded));

CREATE TABLE transfer_rejection (
    out_txn_id BIGINT      NOT NULL,
    in_txn_id  BIGINT      NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_transfer_rejection PRIMARY KEY (out_txn_id, in_txn_id),
    CONSTRAINT fk_transfer_rejection_out FOREIGN KEY (out_txn_id) REFERENCES txn (id) ON DELETE CASCADE,
    CONSTRAINT fk_transfer_rejection_in FOREIGN KEY (in_txn_id) REFERENCES txn (id) ON DELETE CASCADE,
    CONSTRAINT ck_transfer_rejection_distinct CHECK (out_txn_id <> in_txn_id)
);
CREATE INDEX ix_transfer_rejection_in ON transfer_rejection (in_txn_id);

-- is_transfer = "on a transfer link", kept by the database on every path (cascades included).
CREATE FUNCTION transfer_link_sync_is_transfer() RETURNS trigger ...   -- AFTER INSERT/UPDATE/DELETE, per row:
--   UPDATE txn SET is_transfer = EXISTS (link with out_txn_id = txn.id OR in_txn_id = txn.id)
--   WHERE id IN (old and new out/in ids, nulls ignored)
CREATE TRIGGER trg_transfer_link_sync_is_transfer ...;

-- Backfill (a no-op today: nothing writes transfer_link yet).
UPDATE txn SET is_transfer = EXISTS (...);
```

- The V3 trigger (`trg_transfer_link_txn_once`) works unchanged with a null side: `IN` with a null
  member never matches. A test proves it.
- `uq_transfer_link_out` and `uq_transfer_link_in` already allow several nulls.

### V10 `V10__statement_review.sql` (step 5)

```sql
ALTER TABLE statement
    ADD COLUMN reviewed_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_statement_reviewed CHECK (reviewed_at IS NULL OR status = 'NEEDS_REVIEW');
```

Two migrations rather than one, so each step's commit carries its own schema.

---

## 6. Code layout

```
com.finalysis.transfer/                 new feature package
  TransferLink, TransferLinkRepository, TransferMethod, TransferStatus   moved from ingestion (unused there)
  TransferRejection, TransferRejectionRepository
  TransferProperties          @ConfigurationProperties("finalysis.transfers"): windowDays
  TransferCandidateQuery      the native SQL of §2.1 → List<CandidatePair> (ids, account ids, dates only)
  TransferResolver            pure Java: candidate pairs + UNMATCHED sides → links to create + groups for review
  TransferDetector            lock, query, resolve, write; returns counts
  TransferDetectionListener   StatementImported / StatementDeleted → detector (AFTER_COMMIT)
  TransferService             the user actions of §3
  TransferController, TransferLinkResponse, TxnSummary, CreateTransferRequest
com.finalysis.review/
  ReviewQueueService, ReviewQueueController, ReviewItem (sealed: StatementItem, UnparsedUploadItem, TransferCandidatesItem, CompetingCandidateItem, PossibleMatchItem)
com.finalysis.ingestion/
  StatementImported, StatementDeleted   event records (ids only)
  StatementController / StatementImportService   + review, delete
  UnparsedUploadController              GET, dismiss
```

- **Dependencies:** `transfer` → `ingestion` (txn, events) and `account`; `review` → `ingestion` and
  `transfer`; `ingestion` depends on neither.
- An ArchUnit test (dependency approved in 3b) checks these rules. It also checks that `transfer`
  doesn't depend on `categorize`, `chat` or Spring AI.
- `Txn.isTransfer` becomes `insertable = false, updatable = false`, and `Txn` gains `transferExcluded`.
- `TransferLink.outTxn` becomes optional.

---

## 7. Sample data

New edge cases, written last by `tools/SampleDataGenerator.java` without the random generator, so
existing files are unchanged (`SampleDataTest` covers them). Folder `sample-data/edge-cases/transfers/`:
three CSV statements in the existing Northfield checking, Northfield savings and Summit layouts for one
month (fictional merchants), plus `transfers-expected.csv` listing each txn's expected outcome (linked
to which line, review group, or none).

| Scenario | Expected |
|---|---|
| Checking → savings, same day | Linked |
| Checking → card payment, card posts 3 days later | Linked |
| Card payment 5 days later / 6 days later | Linked / not linked (window boundary) |
| Checking → card, card credits the day **before** checking posts | Linked (symmetric window) |
| Two 250.00 transfers checking → savings, 2 days apart | Two links, in date order |
| −200.00 on checking; +200.00 on savings and +200.00 on the card | No link; one review group with 2 pairs |
| −42.00 purchase on checking; +42.00 refund on the card 2 days later | Linked (the documented false positive, §2.7) |
| −75.00 on the card; +75.00 on checking (cash-advance shape) | Not linked (rule 4) |
| A checking payment with no counterpart in any account | Not linked; marked one-sided via the API in tests |

- FEE/INTEREST exclusion (rule 5): CSV can't produce those sections. It is tested with an OFX edge
  case (`edge-cases/transfers/ofx-fee-interest.ofx`, a SRVCHG and an INT row of equal and opposite
  amounts) or with rows inserted in a repository test.
- Zero amounts (rule 1) are tested the same way.
- **Duplicate-account case** (no new file): the Northfield checking CSV imported into account A and
  its OFX into account B. Every savings transfer then has two out candidates and goes to the review
  queue. Deleting B's statement resolves it, and the next detection links them. That's the realistic
  story for "delete a statement".

---

## 8. Tests and requirement mapping

| Requirement | Tests (new unless noted) |
|---|---|
| ING-05 schema | `TransferSchemaTest` (V9: either side nullable but not both, MATCHED ⇔ both, USER ⇒ confirmed, trigger sets and clears `is_transfer` on insert, update, delete and cascades from txn and statement deletion; V3 rule still holds with null sides; excluded + transfer impossible; rejection cascade); `TransferLinkRepositoryTest`, `SchemaMigrationTest` (updated) |
| ING-05 rules | `TransferCandidateQueryTest` (each rule of §2.1 on both sides of its boundary, window from config, effective date uses `post_date`); `TransferResolverTest` (pure: 1:1, k:k same accounts in date order, k:k with a non-candidate pairing, 1:2, 2:1, UNMATCHED completion, chains, determinism under input order) |
| ING-05 detection | `TransferDetectionTest` (answer key: 18 links and exact `is_transfer` set across four import orders; OFX/QFX variant; idempotent second run; late arrival; edge-case fixtures against `transfers-expected.csv`; description-scramble invariance; detection failure doesn't fail the import; concurrency: two runs serialize) |
| ING-05 actions | `TransferApiTest` (every endpoint and error in §3, Location headers, rejection prevents relinking, not-transfer removes link and blocks detection, undo + detect relinks, manual link clears exclusion and rejection, one-sided completion clears confirmation, money as strings) |
| ING-07, ING-13 | `ReviewQueueApiTest` (five kinds across accounts; competing candidates: listed when a counterpart arrives after the link, not for confirmed or USER links, gone after confirm, unlink then detect re-resolves; possible matches: listed within the window and not outside it, join, dismiss, a rejected pair not listed, detection never joins; counts, filters, order; review keeps warnings and removes the item; late-arrival warning re-queues; dismiss and 409; unparsed GET and 202 `Location`; duplicate-account story end to end); `StatementDeleteTest` (cascades, counts, `is_transfer` cleared on counterparts, detection re-run, file re-importable, unparsed upload keeps `resolved_at`, account lock) |
| GR-04 | covered above: `is_transfer` is exactly "on a link", on every path |
| SEC-10 | `SensitiveDataLeakTest` (extended: new endpoints' errors and logs carry no descriptions or amounts) |
| Architecture | `PackageDependencyTest` (ArchUnit, §6) |

Database tests use Testcontainers. All data comes from `sample-data/`.

**Postman:** a new folder, "8. Transfers and review queue", run against a freshly reset dev database
with the Postman CLI. It contains:
- setup: four accounts and the sample CSV statements;
- transfers: list (18 links, all AUTO and unconfirmed), confirm, unlink, rejection holds after detect,
  manual relink, not-transfer and undo, one-sided;
- review queue: a NEEDS_REVIEW statement (`ofx-ledgerbal-mismatch.ofx`), an unparsed upload
  (`pdf/edge-cases/pdf-unrecognized-layout.pdf`) and a transfer group (edge-case fixtures); then mark
  reviewed, dismiss, and delete a statement.

Existing folders get the new errors (`transfer-invalid`, `transfer-conflict`, `not-reviewable`,
`already-resolved`).

---

## 9. Steps

| # | Step | Commit content | Main tests | Status |
|---|---|---|---|---|
| 0 | Plan | This file; requirements v1.24 (ING-05, ING-07, ING-13 text; CAT-08 Phase 4 hook; ING-02 and SEC-09 PDF parts deferred). Commit "Phase 3c plan" | — | Done: "Phase 3c plan" |
| — | Phase 3b step 8 (standalone) | PDF security tests, before step 1 because PDFs are accepted today; recorded in `phase-3b.md` | `SensitiveDataLeakTest`, `MultipartDiskTest`, `NoAiInIngestionTest`, `ExposureTest` | Done: "Phase 3b step 8: PDF security tests" |
| 1 | Schema | V9; move transfer entities to `transfer`; `TransferRejection`; `Txn` mapping changes. With it: SEC-09 and SEC-10 → Done (requirements v1.26) | `TransferSchemaTest`, repository and migration tests | Done: "Phase 3c step 1: transfer schema (V9)" |
| 2 | Candidates + resolver | `TransferProperties`, `TransferCandidateQuery`, `TransferResolver`, `TransferDetector`, `POST /api/transfers/detect` | `TransferCandidateQueryTest`, `TransferResolverTest` | Done: "Phase 3c step 2: transfer candidates and resolver" (notes below) |
| 3 | Detection wiring + validation | `StatementImported` event and listener; transfer edge-case fixtures; answer-key validation | `TransferDetectionTest` | Done: "Phase 3c step 3: detection wiring and validation" (notes below) |
| 4 | Transfer actions | `TransferService`, `TransferController`, responses, problem types | `TransferApiTest` | Done: "Phase 3c step 4: transfer actions" (notes below) |
| 5 | Review actions | V10; mark reviewed (+ re-queue on late arrival), unparsed GET/dismiss + 202 `Location`, statement delete + `StatementDeleted` | `StatementDeleteTest`, review/dismiss parts of `ReviewQueueApiTest` | Done: "Phase 3c step 5: review actions" (notes below) |
| 6 | Review queue | `review` package, `GET /api/review-queue`, competing candidates (§4.6), possible matches between one-sided links with join and dismiss (§4.5) | `ReviewQueueApiTest`, ArchUnit, leak test extensions | Planned |
| 7 | Docs | README (transfers, review queue, endpoints, errors, limitations), Postman folder, requirement statuses (ING-05, ING-07, ING-13 → Done), change log, phase summary here | Postman run against a reset database | Planned |

### Known limitations (expected at the end of 3c)

- One-sided transfers count as spending until the user marks them (Phase 4 rules will help).
- A coincidental equal-and-opposite pair within the window is linked (§2.7).
- Card → bank transfers (cash advances, balance transfers) are never auto-linked.
- Deleting a statement doesn't re-run its successor's continuity check.
- Deleting a statement drops confirmed links on its rows; detection re-creates them unconfirmed.
- Deleting the statement that resolved an unparsed upload leaves the upload reading as DISMISSED: V8
  stores both as `resolved_at` without a statement (step 5 notes).
- An ambiguous group (§2.3 rule 3) forms only when its txns meet in one detection run. If one
  counterpart arrives first, that pair is linked (AUTO, unconfirmed), and detection never reopens it.
  A second counterpart imported later puts the link in the review queue as a competing candidate
  (§4.6), where the user confirms it or unlinks it so it re-resolves.

### Step 2 notes

Built as planned. Choices the plan left open:
- **Both sides already on links:** never a candidate pair, even two UNMATCHED links (an out marked
  one-sided and an in marked one-sided). Both are already transfers, and joining them would replace
  two user decisions with one AUTO link. The user can link them by hand (step 4).
- **Completion needs a one-pair group:** an UNMATCHED side is completed only when its group is that
  single pair (§2.4). A k:k group that contains an UNMATCHED side goes to review instead of being
  paired in date order, so a link the user made is never re-paired by a guess.
- **Wrong-sign one-sided links:** the schema allows a negative txn as the *in* side of an UNMATCHED
  link (the API won't, §3). Such a txn is never a candidate, since completing it would put it on the
  wrong side.
- `CandidatePair` sides carry the UNMATCHED link id (or null), so the resolver needs no second input.
  `CandidateGroup` is a record now; the review queue (step 6) reuses it.
- `TransferLock` is its own component (`pg_advisory_xact_lock`, key = "transfer" in ASCII,
  `Propagation.MANDATORY`), so step 4's writes and step 5's delete take the same lock.
- The window is checked in `TransferProperties`' constructor (the `PdfLimits` idiom), so an
  out-of-range value fails startup; `@ConfigurationPropertiesScan` on the application registers it.
- `TransferController` holds only `/detect` for now; step 4 adds the rest.
- The description-scramble invariant is tested now at the query level (every text column of txn and
  account replaced); the full sample-set version stays in step 3's `TransferDetectionTest`.
- Extra tests beyond the plan: `TransferDetectorTest` (writes, idempotence, completion, configured
  window, lock held, endpoint) and `TransferPropertiesTest` (default, range, startup failure).

### Step 3 notes

Built as planned. Choices and findings beyond the plan:
- **Event and listener.** `StatementImportWriter.write` publishes `StatementImported(statementId,
  accountId)` inside the import's transaction. `TransferDetectionListener` handles it after commit and
  runs detection in a new transaction (`REQUIRES_NEW` through a `TransactionTemplate`: after commit the
  finished transaction is still bound to the thread, and joining it would never commit). The listener is
  synchronous, so links exist by the time the 201 is returned; the cost is one detection query per
  import.
- **Failure log:** the statement id and the exception's class name only, never its message, which could
  quote row values (SEC-10). The test throws an exception whose message holds a description and an
  amount and checks neither reaches the log.
- **Fixtures** (`sample-data/edge-cases/transfers/`): three May 2026 statements and
  `transfers-expected.csv` (`file,line,date,amount,expected,counterpart,scenario`; `expected` is LINKED
  with the counterpart's `file:line`, REVIEW with a group name, ONE_SIDED or NONE). The generator writes
  them last without the random generator; every existing file is byte-identical.
- **One-sided in the fixture test** is marked through `TransferLinkRepository`, since the API is step 4.
  Detection then keeps it UNMATCHED. Step 4's `TransferApiTest` covers the API path.
- **FEE/INTEREST and zero amounts:** no OFX fixture. Step 2's `TransferCandidateQueryTest` already
  covers both sides of rules 1 and 5 with inserted rows, which §7 allowed.
- **Scramble test, full version:** the whole CSV sample set imported twice, the second time with every
  description cell (and Apex's category), the file names and the account names and institutions
  replaced by random text, the CSV mapping reused. Links and `is_transfer` rows, compared by sample file
  and line, are identical.
- **Answer key:** four import orders, the OFX/QFX variant and late savings statements all give the 18
  links; the expected pairs are derived in the test from the answer key (each out transfer row with the
  one opposite in row within the window), not from the app's code.
- **Isolation:** detection covers every account, so `TransferDetectionTest` asserts at the start of each
  test that the database holds no txns.
- **Finding, open question: ambiguity depends on arrival order.** Detection runs per import and never
  changes an existing link (§2.5). The −200.00 fixture is a review group only when its out side meets
  both ins in one run; the fixture test imports checking last for that reason. If checking and savings
  arrive first, they are linked, and the card's +200.00 imported later stays unlinked
  (`aCounterpartArrivingAfterALinkDoesNotReopenIt` pins this). The answer key is unaffected (no
  ambiguous groups). Options: (a) keep it and document it (the link is AUTO and unconfirmed, like §2.7);
  (b) let detection dissolve an unconfirmed AUTO link when a new candidate shares a side, sending the
  group to review, which changes the "never changes a link" rule; (c) keep the link, but list it in the
  review queue (step 6) when another candidate shares a side. Built as (a) until you decide.
  **Decided 2026-10-13: (c)**, see §4.6; the rule that detection never changes a link stands.

### Step 4 notes

Built as planned. Choices the plan left open:
- **Check order:** 404 (a txn is missing), then 422 `transfer-invalid` (neither side, signs, equal and
  opposite, different accounts, in that order), then 409 `transfer-conflict`. The 409 detail names the
  txn and the link; `linkId` is also a property. No detail quotes an amount or a description.
- **Marking one-sided also clears `transfer_excluded`**, as a manual link does: the explicit action wins,
  and the V9 CHECK wouldn't allow an excluded txn on a link anyway.
- **Write order under the CHECK:** Hibernate flushes inserts before updates and deletes last, so the
  service flushes between steps: exclusions are cleared before a link is inserted, and a link is deleted
  before an exclusion is set.
- **List:** filtered and sorted in Java over one fetch-join query (a household has a few hundred links at
  most). A link's effective date is the later of its sides'; ties go newest id first. An unknown
  `accountId` gives an empty list, not a 404, since it's a filter.
- **Idempotent:** `POST .../not-transfer` again is 200 (no link to remove), `DELETE .../not-transfer` on a
  txn that isn't excluded is 204, and unlinking never duplicates a rejection.
- **Paths:** link ids are digits only (`{id:\d+}`), so `GET` or `DELETE /api/transfers/detect` is a 405
  instead of matching `{id}` and failing the conversion with a 400. `/api/transfers/abc` is a 404.
- The `not-transfer` endpoints live in `TransferController` (no class-level path), as §6 lists one
  controller.
- `TransferDetectionTest`'s edge-case test now marks the one-sided payment through the API (step 3
  notes).

### Step 5 notes

Built as planned. Choices the plan left open:
- **Transfer lock without a dependency on `transfer`.** Delete publishes `StatementDeleting` inside its
  transaction, after the account lock and before any row goes; `TransferDetectionListener` handles it
  synchronously and takes the transfer lock, held to commit. `StatementDeleted` follows for the
  after-commit detection run. So the order is account lock, then transfer lock, and `ingestion` still
  depends on neither `transfer` nor `review` (§6).
- **`transferLinksRemoved`** is the number of the statement's txns with `is_transfer`: a link's two
  sides are in different accounts, so at most one is in the statement, and ingestion never reads
  `transfer_link`.
- **Delete re-checks under the account lock** that the statement still exists, so two concurrent deletes
  give one 200 and one 404. The log carries ids and counts only.
- **Review takes the account lock** and re-reads the statement, since a late arrival's re-check rewrites
  the same row and may clear `reviewed_at`. Reviewing again keeps the first time.
- **Re-queue:** a passed re-check adds no warning (it only removes "balance unverified"), so only a
  failed one clears `reviewed_at`. Tested both ways.
- **Dismiss** is one conditional `UPDATE` on the database clock (`resolved_at IS NULL`), so it needs no
  account lock: whichever of a dismiss and a resolving import commits second finds the row no longer
  open. The 409 detail names the state as re-read (`RESOLVED` or `DISMISSED`). Reading and dismissing
  live in a new `UnparsedUploadService`.
- **`state` and `detail`:** `state` is derived (OPEN: no `resolved_at`; RESOLVED: with a statement;
  DISMISSED: without). `detail` is fixed text per state. Known limitation: once its resolving statement
  is deleted, an upload reads as DISMISSED, since V8 has no other way to store it; it stays out of the
  queue either way, and widening V10 for it wasn't in the plan.
- **Times the app sets** (`reviewed_at`, and step 4's `confirmed_at`) use `Timestamps.now()`: UTC to the
  microsecond, as Postgres returns them. Before, the response right after a write showed the server's
  local offset while later reads showed UTC; the review tests caught it.
- Tests beyond the two named classes: V10's constraint in `SchemaMigrationTest`; the resolved upload
  whose statement is deleted in `UnparsedUploadImportTest`, which has the test parser that can resolve
  one; its 202 test now expects `Location` and the new fields.

---

## 10. Decisions at approval

Approved on 2026-10-13 with these answers. They override anything older in this file or the
conversation.

| # | Question | Answer |
|---|---|---|
| 1 | Window | 5 calendar days either way, on `coalesce(post_date, txn_date)`, configurable (`finalysis.transfers.window-days`) |
| 2 | Unconfirmed AUTO links in the review queue | No. `GET /api/transfers` filters by `confirmed=true/false` |
| 3 | Ambiguous groups | Not transfers (`is_transfer = false`) until the user decides |
| 4 | Card → bank | Manual links only (recommendation accepted) |
| 5 | Manual link amounts | Must be equal and opposite in 3c (recommendation accepted) |
| 6 | Statement delete | Any status, 200 with counts, no confirmation parameter (recommendation accepted) |
| 7 | Package | New `transfer` package; the transfer entities move there from `ingestion` |
| 8 | ING-13 | Marked Done at the end of 3c (step 7) |
| 9 | Un-review | Not built (recommendation accepted) |

- **Phase 4 hook (CAT-08):** a txn categorized into a Transfer-kind category with no transfer link
  becomes a suggested one-sided transfer in the review queue (§2.6, §4.1). Core detection still
  never reads descriptions.
- **Before step 1:** Phase 3b step 8 (the PDF security tests) runs as a standalone task, because
  PDF uploads are accepted today.

Later decisions (2026-10-13, before step 4):
- **Early links (step 3 open question):** option (c). Detection still never changes a link; the review
  queue lists an unconfirmed AUTO link that a later txn competes for (§4.6).
- **Possible matches (§4.5):** join and dismiss confirmed as proposed; only equal and opposite amounts,
  different accounts, the window, and "pair not already rejected" apply.

<details><summary>The open questions as asked</summary>

1. **Window:** 5 calendar days, symmetric, on `coalesce(post_date, txn_date)`? *Recommended.*
   Alternatives: 3 days, or business days (needs a holiday calendar).
2. **Unconfirmed AUTO links in the review queue?** *Recommended: no.* Every month's payments would
   flood it. They are listed with `GET /api/transfers?confirmed=false`, and the queue's `counts` could
   show the number without listing them.
3. **Ambiguous groups:** keep `is_transfer = false` until the user decides? *Recommended.* The other
   choice, marking every txn in the group as a transfer, hides spending if one of them isn't one.
4. **Card → bank** left to manual links? *Recommended.*
5. **Manual links require equal and opposite amounts?** *Recommended for 3c.* A wire with a fee
   deducted (−1,000.00 out, +985.00 in) would need a split-amount model; that comes later, with
   ACC-02 split transactions.
6. **Statement delete:** any status, 200 with counts, no confirmation parameter (the Phase 7 UI asks)?
   *Recommended.*
7. **Move the transfer entities** from `ingestion` to a new `transfer` package? *Recommended:* they
   belong to the new feature, and nothing in ingestion's main code uses them.
8. **ING-13 status:** dismissal completes its review-queue part, and it doesn't depend on any PDF
   parser. Mark it Done at the end of 3c? *Recommended*, but left unchanged until you say so.
9. **Un-review** a statement (`DELETE /api/statements/{id}/review`)? *Recommended: no* until the UI
   needs it.

</details>
