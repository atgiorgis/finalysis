# Phase 3a plan: standard-format ingestion (OFX/QFX + CSV)

Status: **complete** (all ten steps; tag `phase-3a-done`). Branch `v1`. Requirements as of
`docs/requirements.md` v1.20.

Scope: ING-01 to ING-07, ING-10, SEC-10, SEC-11 for OFX/QFX and CSV. Out of scope for 3a: PDF
parsers (3b), transfer detection and the review-queue API (3c), categorization and merchant
cleaning (Phase 4), the upload UI (Phase 7).

Working rules: one step per commit (small follow-up commits are fine), run
`cd backend && ./mvnw clean verify` and `cd frontend && npm test` before every commit (CI is not
active), report test counts, push `v1`, and stop for review before the next step.

## Phase 3a complete

OFX/QFX and CSV statements import end to end: detection by content, one parser per format, CSV
mappings saved per account with suggestions and a fingerprint lock, row-level deduplication,
reconciliation with late arrivals, a statement list, and upload hardening tested across the API.

| Step | Commits | Backend tests after the step |
|---|---|---|
| Start (Phase 2 + scope) | `b51b52f` | 159 |
| 1 Parser contract | `17fabfe` | 182 |
| 2 OFX tokenizer | `3aa8457`, `371ac6c` | 261 |
| 3 OFX parser + samples | `02dac06` | 347 |
| 4 Import endpoint (OFX) | `3ef1196`, `c1dd137`, `89b3d3b` | 432 |
| 5 CSV mapping API | `11a4473` | 590 |
| 6 CSV parser | `56ae04c` | 801 |
| 7 CSV import + preview | `be02568` | 844 |
| 8 Reconciliation + statement list | `0ebda1b` | 871 |
| 9 Security tests | `991bfda` | 940 |
| 10 Docs, Postman, requirements | "Phase 3a complete: docs, Postman, requirements" | 940 |

Counts are JUnit test cases (parameterized and `@Nested` cases each count), measured by running
`./mvnw clean verify` at each step's last commit; every run passed. Plan-only docs commits are
left out. Frontend: 1 test throughout (no frontend work in 3a). The Postman collection runs 51
requests with 145 assertions against a freshly reset dev database, all passing.

**Requirements:** ING-01, ING-03, ING-04, ING-06, ING-10 and SEC-10 Done. Planned: ING-02
(done for OFX/QFX and CSV; the PDF parsers must normalize too), ING-07 (queue API in 3c), SEC-11
(escaping with the first CSV export).

### Known limitations (final)

- One format per account: deduplication across CSV and OFX for the same account isn't
  guaranteed; mixing formats is flagged for review.
- Footer or total rows at the end of a CSV fail the import (`parse-error`); a `skip_footer_rows`
  mapping option needs a migration and waits for a real export that needs it.
- CSV fingerprints use the sign-normalized amount (debit/credit files have no printed signed
  amount); a confirmed change to a locked mapping field re-adds rows on overlapping re-imports.
- Institutions that reissue FITIDs: the FITID isn't hashed, so reissued ids don't duplicate rows,
  but the possible-duplicate flag only works where FITIDs are stable.
- Raw descriptions may contain identifiers (account numbers in OFX `NAME`/`MEMO`, masked
  numbers, Zelle names); they are never logged or returned in errors, and SEC-08 redacts them
  before cloud calls in Phase 4.
- File bytes are discarded after parsing until SEC-04 (Phase 8); only the hash, file name and
  source lines are kept, so statements can't be re-parsed from storage.
- Non-USD statements are rejected.
- Postman can't reach `payload-too-large`, `unsupported-currency`, `ambiguous-format` or
  `mapping-changed` from sample files; the backend tests cover them.

### Carry-overs

**3b (digital PDF parsers):**
- One `StatementParser` per PDF layout (ING-08, ING-12), starting with the Bank of America
  checking layout, plus the synthetic PDF generator (ING-11); unknown layouts go to the review
  queue (ING-13). The upload checks refuse `%PDF` today (415); 3b must allow it for PDFs only.
- Section totals in use: the ING-10 check (`Reconciliation`, sign-normalized
  `Statement.sectionTotals`) is built and tested but no 3a parser prints totals, so it has never
  run on real input.
- Opening + closing printed-balance check: likewise built for files that print both balances and
  never exercised by OFX or CSV; the PDF parsers are its first users.
- ING-02 sign and date normalization for the PDF layouts; SEC-09 (PDF text never sent to
  Bedrock).

**3c (transfers and review):**
- Transfer detection between the user's own accounts with `transfer_link` (ING-05), including
  card payments that appear 1–3 days apart with different descriptions.
- Review-queue API (ING-07, ING-13): list and resolve `NEEDS_REVIEW` statements across accounts.

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

### CSV parser (step 6)
- `CsvStatementParser` (id `csv`, Apache Commons CSV): cells never trimmed, no
  `ignoreSurroundingSpaces`, no escape character (quotes are doubled), no comment marker; empty
  and whitespace-only lines are skipped. A Spring bean since step 7.
- Detection (FALLBACK): text (UTF-8, else windows-1252; NUL = binary = NONE) with one of
  `, ; TAB |`. OFX stays DEFINITE and wins. *(Step 7 replaced the "first 20 records" rule with
  the preamble-tolerant one below.)*
- `ParseContext` carries the account's mapping (nullable; an immutable `CsvMappingSpec` since
  step 7). No mapping →
  `CsvMappingRequiredException`; header signature (or `columns:<n>`) differs →
  `CsvMappingMismatchException` with both signatures (hashes or counts, never header text).
- `skip_rows` skips physical lines before CSV parsing (so quotes in a preamble can't break it);
  line numbers still count them. The UTF-8 BOM is dropped only for UTF-8; decoding is strict.
- Row rules: dates and amount/balance cells are stripped for parsing only (the stored printed
  amount and descriptions are untouched). DEBIT_CREDIT takes the absolute value of the non-empty
  side (some banks print debits with a minus). Whitespace-only description cells count as empty.
  A blank balance cell means no running balance on that row. A check-number cell is stripped.
- Period = earliest to latest printed date (not first and last row, so newest-first files work).
  A file with no rows is a parse error unless the request gives both period ends (step 7).
- Errors: `line N: column C (role) …` with 1-based column positions; never cell values or header
  names. Every row must have the header's column count.
- `ParsedTxn.runningBalance` (nullable, NUMERIC(12,2)) carries the balance column for step 8; not
  stored.
- Sample data: `edge-cases/csv-*` (7 files) are written last and without the random generator,
  so the existing files are unchanged.
- **Known limitation:** trailing footer or total rows (e.g. "Total,,1234.56") fail the import. A
  `skip_footer_rows` mapping option would need V7; add it if a real bank export needs it.

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

### CSV import (step 7)
- **Detection tolerates a preamble** (review change A). Candidate starts are the non-blank lines
  among the first 100; a start qualifies when 5 records from it (or all remaining, if fewer, but
  at least 2 unless the whole file is one record) share one column count >= 2. The earliest start
  wins across `, ; TAB |` (earlier delimiter on a tie); its line number is the suggested
  `skip_rows`. Detection reads at most 1 MB. Rows after the run are the parser's to check. Edge
  case `csv-preamble.csv`: the April checking statement below 4 summary lines (3 with a comma, so
  they form a short 2-column run that must not win) and a blank line; resolves to `skip_rows` 5.
  *(Replaced after 3a by the longest-run rule: see "Post-3a fixes from real-file test".)*
- **Mapping snapshot** (review change B). `CsvMappingSpec(CsvMappingValues values, updatedAt)` is
  built by `CsvMappingService.snapshot` in a read-only transaction; `ParseContext` carries it and
  the parser never touches JPA. `CsvMappingValues` became public for this.
- `StatementParser.usesCsvMapping()` (default false, CSV true) tells the import to take the
  snapshot and the writer to check it; OFX imports never see a CSV mapping.
- **Version check:** under the account lock, the writer compares the saved `updated_at` with the
  snapshot's; any difference is 409 `mapping-changed` (retry), nothing written. A `PUT` takes the
  same lock, so nothing can change between the check and the commit. `updated_at` instead of a
  version column: no migration, and two mapping saves would have to start in the same
  microsecond to collide; a V7 `version` column would make it exact if that ever matters.
- **`used_at`** is set by the first successful import in the writer's transaction with a native
  `UPDATE ... WHERE used_at IS NULL`, so `updated_at` doesn't move: marking a mapping used doesn't
  change how it reads files and must not make concurrent imports look stale.
- Problems: 422 `mapping-required` (detail names the preview and PUT endpoints), 422
  `mapping-mismatch` with `preview`, 409 `mapping-changed`. *(Step 8, review change A: the
  mismatch preview carries headers, mapping status and suggested mapping only, no data rows.)*
- **Preview** (`POST /api/accounts/{id}/imports/preview`): same upload checks, detection and
  parsing as an import, writes nothing. CSV: `format`, `delimiter`, `encoding`, `skipRows`,
  `headers` (stripped), first 10 `rows`, `mappingStatus` (NONE / MATCHES / MISMATCH, where
  MATCHES means the saved mapping's own settings read this file's signature), `suggestedMapping`
  unless MATCHES. Other formats: `format`, `periodStart`, `periodEnd`, `transactionCount` (after
  the currency and account checks). Logged: format and account id only.
- **Suggestions** read every data row, not just the 10 shown (more evidence for the date format
  and sign). One description column (earliest synonym), with a note naming other description-like
  columns. A single amount column wins over debit/credit. Duplicate header names are never
  suggested. Sign confidence: MATCHED for debit/credit, GUESSED otherwise; MISSING only for a
  card with no amount column. Optional columns that aren't found are left out of `confidence`.
  `mapping` is a complete `PUT` body.
- Request values: an inverted period with both ends given is a 400 before parsing (a parser may
  use it for an empty CSV), with all other request errors; everything else is still checked
  after parsing.
- Tests: `CsvImportTest` (whole sample set with unedited suggestions + OFX into separate
  accounts, overlap file, preamble, mapping lifecycle, mapping changed between snapshot and lock
  via a spy that updates the row from another thread, preview writes nothing and logs no
  contents), `CsvMappingSuggesterTest`, detection tests in `CsvStatementParserTest`.

### Reconciliation (step 8)
- `Reconciliation` (pure Java, `BigDecimal`) runs in the writer under the account lock, on the
  parsed rows (duplicates included). Checks: printed balances (file prints both; for 3b PDFs),
  request balances (both sent), balance column, continuity, section totals.
- **Balance column** is pairwise: each printed balance against the previous printed balance plus
  the amounts since (blank cells carry over). A one-cell typo flags that line and the next; a
  missing row flags one line. Newest-first = first printed date after the last. Opening = first
  balance minus the amounts up to it, closing = last balance plus amounts after it. Up to 10
  failing lines listed, then counted.
- **Continuity** applies to a file with a closing but no opening balance (OFX). Previous = the
  account's statement with the latest `period_end` <= this start (ties: newest id), adjacent when
  it ends on the day before or the same day, and only with a closing balance. On success the
  opening is the previous closing (a request opening is compared with it instead).
- Request balances: no longer merged in the service. A request balance contradicting a printed
  one is a failed check ("the file's is kept"), replacing the step-4 merge warning.
- Warning format: `reconciliation failed: {check}: expected {computed}, actual {stated},
  difference {actual − expected}`; amounts with two decimals. Unverified:
  `Reconciliation.UNVERIFIED_WARNING`.
- **Status:** review reasons or FAILED → NEEDS_REVIEW; else PASSED → RECONCILED; else IMPORTED
  with the unverified warning. "No new rows" stays informational.
- **V7** (`V7__statement_reconciliation.sql`): `statement.balance_check` (PASSED / FAILED /
  UNVERIFIED, default UNVERIFIED), `parsed_count`, `parsed_amount_total NUMERIC(14,2)` (a sum of
  many amounts). The status enum was enough; the separate column is needed because a
  NEEDS_REVIEW statement can still be unverified, and the late-arrival check needs the file's
  row total, which duplicates make impossible to recompute from `txn`. Pre-V7 statements have
  null totals and are never re-checked.
- **Late arrival:** after saving, the statement starting on this one's last day or the next day
  (earliest start, then oldest id) is re-checked when it is UNVERIFIED, has a closing balance, no
  opening and stored totals. The unverified warning is replaced by the outcome; IMPORTED becomes
  RECONCILED or NEEDS_REVIEW; NEEDS_REVIEW stays. No chaining needed: a later statement already
  had its own predecessor.
- `GET /api/accounts/{id}/statements` (`StatementListItem`): newest `period_start` first (then
  `period_end`, id), `?status=` filter, owned-row counts in one grouped query. The timestamp is
  `importedAt` (the column is `imported_at`), matching the detail.
- Section totals are documented as sign-normalized (`ParsedStatement`).

### Security tests (step 9)
- **Test classes:** `SensitiveDataLeakTest` (every endpoint, success and every failure type, with
  markers in descriptions, file names, header names, cells and account numbers; app packages at
  DEBUG; logs and problem bodies checked), `UploadHardeningTest` (sniffing matrix, XXE, file
  names, row and record limits, SEC-11 through the import), `MultipartDiskTest` and its control
  `MultipartDiskControlTest` (real Tomcat: a spy lists the multipart location while the
  controller runs; exactly 10 MB passes the multipart limit, one byte more is 413),
  `ExposureTest` and `ExposureWithProfilesTest` (Actuator, CORS, leak-prone settings, every
  config file and profile section).
- **`mapping-mismatch` keeps header names** in its `preview` (step 8 decision; SEC-10 allows
  column names). The leak test exempts only that node and still checks it for rows, descriptions,
  cells, file names and account numbers.
- **Ambiguous format and mapping-changed** are reached in the leak test with a test-only parser
  that claims marked OFX files and a spy that returns a stale mapping snapshot.
- **Fixes:**
  - saved web pages (`<!DOCTYPE html` / `<html` after an optional BOM and whitespace) are a 415:
    commas in their styles and scripts could pass CSV detection;
  - a file name over 255 characters is cut in its stem, keeping an extension of up to 15
    characters (it was cut at the end, so a long `.ofx` name failed the extension check); a
    surrogate pair is never split; Unicode format characters (bidirectional overrides,
    zero-width characters, a BOM) are removed with the control characters;
  - `logServerErrorDetail=false` on the JDBC connection: Postgres details ("Failing row contains
    (...)", "Key (x)=(y)") quote row values, and the exception handler logs constraint violations
    with their stack traces;
  - Actuator exposes `health` only (was `health,info`);
  - `spring.devtools.add-properties: false` and `spring.web.error.include-*: never`: under
    `spring-boot:run`, devtools turned on stack traces, messages and binding errors in Boot's
    error responses and logged every resolved exception (a malformed JSON body's message quotes
    the input).
- File names are not redacted beyond path, control and format characters: a person's name in a
  file name is the user's own data, stored for display, never logged or returned.
- Invalid UTF-8 that is valid windows-1252 (e.g. `C3 28`) is read as windows-1252 by design; only
  bytes invalid in both (e.g. `0x81`) are a 415.
- Framework DEBUG logging (`org.springframework.web`, `org.hibernate`) prints request bodies and
  bind values; the README says not to enable it with real statements, and `ExposureTest` refuses
  it in any config file.

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
| 6 | CSV parser (Apache Commons CSV) for the three sample layouts + generated CSV edge cases (quoted commas, embedded newline, non-ASCII, `=SUM(` description, identical rows, refund, newest-first, windows-1252, overlapping 04-15..05-14 export); SEC-11 stored-unchanged test; full "every sample file is claimed by exactly one parser" registry test | Done: `56ae04c` |
| 7 | CSV import: register `CsvStatementParser` as a bean; preview endpoint with `suggestedMapping`, `mapping-required` / `mapping-mismatch`, full sample-data CSV import against the answer key; requirements entry for suggested mappings; preamble-tolerant detection; mapping snapshot with version check | Done: `be02568` |
| 8 | Reconciliation (ING-06, ING-07, ING-10): OFX LEDGERBAL continuity with the previous statement, CSV balance column and request balances, section totals when present; `GET /api/accounts/{id}/statements` list with status filter | Done: `0ebda1b` |
| 9 | Cross-cutting SEC-10 tests: log capture across the whole API, problem bodies, sniffing matrix, XXE end to end, file names, limits at the boundary, no temp files, exposure checks; SEC-11 through the import | Done: `991bfda` |
| 10 | Docs: README ingestion and API, Postman, requirement statuses (ING-01, ING-03 already Done; SEC-10 Done in step 9), change log | Done: "Phase 3a complete: docs, Postman, requirements", tag `phase-3a-done` |

Final requirement statuses: see "Phase 3a complete" above.

## Post-3a fixes from real-file test

A manual import of a real bank CSV (kept outside the repo, never read into code, tests or docs)
found three bugs. Fixed with synthetic data only, in one commit ("CSV: longest-run preamble
detection, balance-only rows, balance synonyms"), requirements v1.21 (ING-01, ING-06).

The file's structure, without values: a 3-column summary block (header `Description,,Summary Amt.`
with an empty middle header, then beginning balance, total credits, total debits and ending
balance rows with quoted amounts), a blank line, the 4-column header
`Date,Description,Amount,Running Bal.`, a first row with a date, a "beginning balance" text, an
empty amount and a running balance, then signed transactions with quoted thousands.

1. **Preamble detection picked the summary block.** It is five consistent records, so "the first
   run of 5" chose it. New rule (`CsvStatementParser.detectTable`): among runs of consecutive
   records sharing one column count (two or more columns, blank lines skipped) that start within
   the first 100 lines, the run with the most records wins; a tie goes to the later run (a block
   above a table of the same size is the preamble); the same run under two delimiters goes to the
   earlier delimiter. A run needs at least two records, unless it is the whole file. Each run
   counts at most 100 records, so detection stays cheap; a start inside a run already read is
   skipped (its run is a shorter suffix). `skipRows` is the run's physical line, blank lines
   included, so the saved mapping lands on the real header (line 7 of the edge case, `skipRows` 6).
   Trade-off, documented in the Javadoc and README: a table shorter than the block above it, or
   one split by a row with another column count early on, is found at the longer part; the preview
   shows `skipRows` for the user to correct. A malformed quote ends a run instead of discarding it.
2. **Balance-only rows failed the import** (`amount is empty`). Now, with a mapped balance column,
   a row with a valid date and a balance but a blank amount (blank debit and credit in
   DEBIT_CREDIT mode; zeros are still amounts) is not a transaction. The oldest row (the first, or
   the last in a newest-first file, judged from the transactions' dates as reconciliation does)
   becomes `ParsedStatement.openingBalance`; any other is skipped with the warning
   `line N: skipped a row with a balance but no amount (not a transaction)`, which makes the
   statement NEEDS_REVIEW like every warning. Judged by cell shape only, never description text,
   so it stays bank-agnostic. Without a balance column an empty amount is still a parse error.
   The period spans transaction dates only. Reconciliation gains one check: a printed opening
   balance must equal the opening derived from the balance column ("printed openingBalance
   against the balance column"), so the row's balance is verified, not just stored.
3. **`Running Bal.` was not suggested as the balance column.** Balance synonyms are now
   `balance`, `running balance`, `running bal`, `bal` (punctuation is already ignored).
4. **Edge case** `sample-data/edge-cases/csv-summary-preamble-balance-row.csv`, built by
   `tools/SampleDataGenerator.java` (fictional institution, made-up merchants, no names or account
   numbers), reproduces the structure: 5-record summary block, blank line, header, balance-only
   opening row, 10 transactions whose running balance ends at the summary's ending balance.

Tests: detection (longest run over the summary block, tie to the later run, updated earlier
cases), suggester (the edge case's preview: `skipRows` 6, date, description, amount and balance
MATCHED, `MM/dd/yyyy`; short balance headers), parser (first, newest-first last, middle,
DEBIT_CREDIT, no balance column, bad date, balance-only file), reconciliation (printed opening
agrees / disagrees), and import end to end (unedited suggested mapping imports 10 rows,
RECONCILED with opening 2415.30 and closing 4293.52; a middle balance-only row warns with its line
and goes to NEEDS_REVIEW). The existing `csv-preamble.csv` tests pass unchanged.

| | Backend tests | Frontend tests |
|---|---|---|
| Before (Phase 3a complete) | 940 | 1 |
| After the fixes | 956 | 1 |
