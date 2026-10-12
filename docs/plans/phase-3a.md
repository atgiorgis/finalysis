# Phase 3a plan: standard-format ingestion (OFX/QFX + CSV)

Status: steps 1–5 done; step 6 next. Branch `v1`. Read this before starting a step, then the
requirements it cites in `docs/requirements.md` (currently v1.15).

Scope: ING-01 to ING-07, ING-10, SEC-10, SEC-11 for OFX/QFX and CSV. Out of scope for 3a: PDF
parsers (3b), transfer detection and the review-queue API (3c), categorization and merchant
cleaning (Phase 4), the upload UI (Phase 7).

Working rules: one step per commit (small follow-up commits are fine), run
`cd backend && ./mvnw clean verify` and `cd frontend && npm test` before every commit (CI is not
active), report test counts, push `v1`, and stop for review before the next step.

## Decisions

These are approved and override anything older in the conversation history.

### Parser contract (step 1)
- `StatementParser`: `id()`, `detect(ImportFile)` judged by content only (`FormatMatch` NONE /
  FALLBACK / DEFINITE), `parse(ImportFile, ParseContext)`.
- `StatementParserRegistry`: exactly one DEFINITE wins; else exactly one FALLBACK (CSV); none →
  unsupported; several at the deciding level → ambiguous (a detection bug, reported, not guessed).
- `ParsedStatement` / `ParsedTxn` / `AccountHints` validate every parser's output: amounts padded
  to scale 2, never rounded (more than two decimals is rejected), within NUMERIC(12,2); raw
  descriptions passed through untouched; `toString()` never prints contents (SEC-10).
- `Statement.sectionTotals` is `Map<SourceSection, BigDecimal>`, validated on write (ING-10 note).
- Optional parser outputs are nullable fields, not `Optional`.

### OFX/QFX (steps 2–3)
- In-house `OfxReader` (no XML parser): DTDs, `<!ENTITY`, comments, CDATA and processing
  instructions in the body are rejected; only the five predefined entities and numeric references
  are decoded; a bare `&` (AT&T) stays literal. Limits: depth 32, tag name 32, value 4096,
  1,000,000 elements. Errors carry line numbers and tag names only.
- Charsets: `ENCODING:UTF-8` and `ENCODING:UNICODE` → UTF-8; `USASCII` with `CHARSET`
  1252/NONE/absent → windows-1252; XML `encoding` UTF-8 (default), US-ASCII, windows-1252. All
  else is a parse error. UTF-8 BOM stripped. Strict decoding.
- Value boundaries (fingerprint-critical, locked by tests): a value runs to the next `<`; only
  spaces, tabs, CR and LF right after `>` and right before the next tag are removed (file syntax,
  not content); entities decoded after that.
- SGML recovery: when a closing tag matches an ancestor, open elements in between become empty
  leaves and their children move up in document order. XML stays strict.
- `OfxStatementParser` (id `ofx`): one statement per file; `CURDEF` is a hint (import rejects
  non-USD); `DTPOSTED` → printed and post date, `DTUSER` → transaction date, date part only;
  `TRNAMT` kept as printed and as a number (OFX signs already match the app's convention);
  description = NAME + " " + MEMO when both non-empty, else whichever is non-empty, neither = parse
  error; section table locked by a test (FEE/SRVCHG → FEE, INT → INTEREST, bank CHECK → CHECK,
  else by sign: bank WITHDRAWAL/DEPOSIT, card PURCHASE/PAYMENT; zero → DEPOSIT/PAYMENT);
  TRNTYPE/sign contradictions are warnings; only ACCTID's last four leave the parser, and
  BANKACCTTO/CCACCTTO ACCTIDs are masked (`****1002`) in `sourceLineText` (the STMTTRN block).
- Known limitation: a full account number printed inside NAME/MEMO stays in the raw description
  (descriptions are never redacted); SEC-08 redacts before cloud calls in Phase 4.

### Import flow (step 4 and follow-ups)
- `POST /api/accounts/{id}/imports` (multipart `file`; optional `periodStart`, `periodEnd`,
  `openingBalance`, `closingBalance`) → 201 `ImportSummary` + `Location: /api/statements/{id}`.
  `GET /api/statements/{id}` returns the statement with its stored warnings. The list endpoint
  comes in step 8.
- Order: account lookup → upload checks → SHA-256 → identical hash = 409 `already-imported` with
  the existing `statementId` → detect + parse outside any transaction (any bad row fails the file:
  422 `parse-error` with `line`) → currency (non-USD = 422 `unsupported-currency`) and account
  hints (type or last four differ = 422 `account-mismatch`; missing hints are not checked) →
  `StatementImportWriter` in one transaction with the account row locked (PESSIMISTIC_WRITE).
- **Period overlap is a warning, not a block.** `uq_statement_account_period` is dropped (V5);
  rows already imported are skipped by fingerprint. An identical file is still 409.
- **Overlap status rule:** an overlapping import that inserts 0 rows is IMPORTED with
  "period overlaps statement {id}; no new rows"; one that inserts 1+ rows is NEEDS_REVIEW with
  "period overlaps statement {id}".
- Always NEEDS_REVIEW: parser warnings (e.g. TRNTYPE/sign), request-merge conflicts, rows dated
  outside the statement period (imported anyway, never dropped; warning lists up to 10 lines),
  mixed formats on one account, and the FITID safety net. Warnings are stored in
  `statement.import_warnings` and returned by GET.
- **FITID safety net:** FITID is stored in `txn.external_id`. A new row whose FITID the account
  already has under a different fingerprint is inserted (dropping it could lose a real
  transaction) with a "possible duplicate" warning → NEEDS_REVIEW.
- **Fingerprint excludes FITID:** raw printed values (account, printed date, amount,
  description, section) plus an occurrence number within the statement. Some institutions
  reissue FITIDs on later downloads, so hashing them would re-insert rows. Two identical same-day
  purchases are both kept (occurrence 1 and 2); a refund is its own row. Encoding locked by a
  golden-value test.
- Request values: the period fields override the file's; balance fields only fill what the file
  doesn't print, conflicts keep the file's value with a warning (→ NEEDS_REVIEW).
- **Money as JSON strings:** API responses write money as a string with exactly two decimals
  (`"closingBalance": "4066.58"`) via `@ApiMoney` (`com.finalysis.api`); JSON requests must send
  such a string, a number is a 400 naming the field. Multipart balance fields accept only plain
  decimal text (`-?\d{1,10}(\.\d{1,2})?`). The shared `JsonMapper` and `section_totals` storage
  are unchanged.
- **File bytes are discarded** after parsing until SEC-04 (Phase 8, encrypted storage). Kept: the
  hash, the cleaned file name (no path or control characters, ≤ 255), each row's
  `source_line_text`.
- Problem types `urn:finalysis:problem:*`: `unsupported-format` 415, `ambiguous-format` 422,
  `parse-error` 422, `account-mismatch` 422, `unsupported-currency` 422, `already-imported` 409,
  `payload-too-large` 413. None quotes file contents, descriptions or account numbers.
- One format per account: cross-format deduplication is not guaranteed; mixing formats warns.
  No test asserts OFX-vs-CSV deduplication (synthetic descriptions match, real ones don't).

### Schema
- V5 `statement_import` is **frozen** (applied to the dev database): `txn.external_id`,
  `txn.printed_amount`, `statement.parser`, `statement.import_warnings`, dropped
  `uq_statement_account_period`. Every further schema change goes in V6+.
- No `(account_id, fingerprint)` constraint: V1's global `uq_txn_fingerprint` is stricter.

### CSV (steps 5–7)
- **One mapping per account** (table `csv_mapping` in V6), not per institution.
- Mapping fields: delimiter, encoding (UTF-8 default, BOM stripped), header row / skip rows,
  `header_signature`, date column + date format, optional post-date column, ordered description
  columns (joined with one space), amount mode SINGLE or DEBIT_CREDIT with the relevant columns,
  sign AS_IS or INVERTED, optional balance column, optional check-number column, `used_at`.
  Columns by header name, or index when there is no header row.
- **CSV fingerprint uses the sign-normalized amount** (a documented exception to "raw printed
  values": debit/credit files have no single printed signed amount). Printed date = post date
  when mapped. Sections by sign: bank DEPOSIT/WITHDRAWAL, card PAYMENT/PURCHASE, CHECK when a
  check number is present.
- **Fingerprint-protected mapping fields.** The mapping records `used_at` when an import first
  uses it.
  - Locked once `used_at` is set: sign; `description_columns` (set or order); and the
    printed-date column (`post_date_column` when present, otherwise `date_column`).
  - Everything else stays editable: column names/indexes for the same roles, `date_format`,
    `delimiter`, `encoding`, `header_row`, `skip_rows`, balance and check-number columns,
    `header_signature`.
  - A `PUT` changing a locked field returns 409 `mapping-locked` naming the fields. With
    `?confirmFingerprintChange=true` it's accepted, and the response warns that future imports
    overlapping earlier statements will produce new fingerprints and land in NEEDS_REVIEW (via
    the overlap-with-inserted-rows rule).
- **Step 5 implementation details:**
  - `header_row` is a boolean; `skip_rows` (0–100) counts lines skipped before the header (or
    before the first row without one).
  - `header_signature` is `sha256:<hex>` of the stripped header names joined with U+001F, or
    `columns:<n>` without a header row. Header names are matched exactly (case-sensitive) after
    stripping; a referenced name that appears twice in the headers is a 400.
  - Index references are canonical (`0`, `12`, never `01`), so equal indexes compare equal.
  - `PUT` returns 201 on create and 200 on replace; `used_at` and `created_at` survive a replace.
  - SINGLE forbids debit/credit columns and DEBIT_CREDIT forbids `amount_column` (API and CHECK).
  - Locked values are compared exactly: renaming a locked column (or switching `header_row`,
    which turns names into indexes) needs confirmation. `amount_mode` is not locked. A changed
    printed-date column is reported as `postDateColumn` when either side maps one, else
    `dateColumn`.
  - `PUT` locks the account row like an import, so a mapping can't change while an import of the
    account is being written; step 7 should still check under that lock that the mapping it
    parsed with is the current one.
  - Validation errors name fields and positions (`descriptionColumns[1]`), never column names.
- No mapping yet → 422 `mapping-required` pointing to the preview; header mismatch → 422
  `mapping-mismatch` with the preview.
- Period: request values, else first and last transaction dates.
- Rows that can't be parsed fail the file; line numbers are the record's starting line,
  `source_line_text` the raw record.
- SEC-11: formula-leading cells (`=`, `+`, `-`, `@`, tab, CR) are stored unchanged; escaping
  applies to any future CSV export.

### Suggested mappings (step 7)
`POST /api/accounts/{id}/imports/preview` also returns `suggestedMapping` when the account has no
saved mapping or the saved one doesn't match the headers. Plain Java, no AI; nothing is saved
automatically (the user confirms with `PUT /api/accounts/{id}/csv-mapping`).
- Header matching: case-insensitive, trimmed, punctuation ignored, with synonyms:
  - date: date, transaction date, trans date, posted date, post date, posting date. With both a
    transaction date and a post date, the post date is the printed date and the transaction date
    is `txnDate`.
  - description: description, payee, merchant, name, memo, details, transaction, **merchant
    name, payee name** (adjustment 1: Summit's header is `Merchant Name`).
  - single amount: amount, transaction amount.
  - debit/credit: debit, withdrawal, withdrawals, money out / credit, deposit, deposits, money in.
  - balance: balance, running balance. Check number: check, check number, check no.
- Date format from a fixed list (MM/dd/yyyy, M/d/yyyy, yyyy-MM-dd, dd-MMM-yyyy, MM/dd/yy,
  dd/MM/yyyy): candidates are those that parse every preview row. **Adjustment 2:** formats are
  ambiguous only if they give different dates for some row (MM/dd vs M/d on zero-padded dates is
  not ambiguous); when ambiguous, pick the US format and add an "ambiguous date format" note.
- Sign: card accounts with a single amount column → INVERTED if most rows are positive, else
  AS_IS.
- Confidence per field: MATCHED, GUESSED, MISSING, plus notes.
- Record in requirements (ING-01 or a new ING ID) with a change-log entry in the step 7 commit.

### Other decisions
- Merchant cleaning moves to Phase 4; `txn.merchant` stays null in Phase 3 (ING-02).
- Non-USD statements are rejected with 422.
- SEC-10 (upload hardening): 10 MB (multipart and in code), `file-size-threshold` above the limit
  so uploads never touch disk, extension allow-list `.ofx .qfx .csv .txt`, client content type
  ignored, sniffing (NUL/controls, `%PDF`, ZIP, invalid UTF-8/windows-1252), no file contents or
  descriptions in logs or error responses.
- SEC-11: see CSV above.
- Transfer detection (ING-05) and the review-queue API (ING-07, ING-13) are their own step at the
  end of Phase 3 (3c), after the PDF parsers.

## Steps

| # | Step | Status |
|---|---|---|
| 1 | Parser contract, registry, ParsedStatement validation, typed section totals | Done: `17fabfe` |
| 2 | OFX tokenizer (SGML/XML, entities, charsets, hardening) | Done: `3aa8457`; fixes `371ac6c` (UNICODE, SGML recovery, source spans) |
| 3 | OFX parser + `sample-data/ofx/` and `edge-cases/` via the generator | Done: `02dac06` |
| 4 | Import endpoint, OFX end to end (V5, dedup, statuses, SEC-10 checks, docs) | Done: `3ef1196`; follow-ups `c1dd137` (stored warnings, out-of-period rows), `89b3d3b` (FITID out of fingerprint, overlap status rule, money as JSON strings) |
| 5 | CSV mapping: V6 `csv_mapping` (with `used_at`), entity, `GET`/`PUT /api/accounts/{id}/csv-mapping`, locked fields with 409 `mapping-locked` and the `?confirmFingerprintChange=true` override | Done: `11a4473` |
| 6 | CSV parser (Apache Commons CSV) for the three sample layouts + generated CSV edge cases (quoted commas, embedded newline, non-ASCII, `=SUM(` description, identical rows, refund, newest-first, overlapping 04-15..05-14 export); SEC-11 stored-unchanged test; full "every sample file is claimed by exactly one parser" registry test | To do |
| 7 | CSV import: preview endpoint with `suggestedMapping`, `mapping-required` / `mapping-mismatch`, full sample-data CSV import against the answer key; requirements entry for suggested mappings | To do |
| 8 | Reconciliation (ING-06, ING-07, ING-10): OFX LEDGERBAL continuity with the previous statement, CSV balance column and request balances, section totals when present; `GET /api/accounts/{id}/statements` list with status filter | To do |
| 9 | Cross-cutting SEC-10 tests: log capture during successful and failing imports, sniffing matrix, XXE end to end | To do |
| 10 | Docs: README ingestion and API, Postman, requirement statuses (ING-01, ING-03, SEC-10 to Done when complete), change log | To do |

Requirement statuses now: ING-04 Done; ING-01, ING-03, SEC-10 Planned until CSV (and step 9 for
SEC-10) land.
