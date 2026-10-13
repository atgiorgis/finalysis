# Phase 3b plan: digital PDF statements

Status: **approved**; steps 0–2 done, next: step 3. Branch `v1`. Requirements as of
`docs/requirements.md` v1.22.

Scope: ING-08, ING-11, ING-12, ING-13, SEC-09, SEC-10 extended to PDFs, ING-02 for PDFs, and
ING-10 run for the first time on printed section totals. First layout: the Bank of America
checking statement. Out of scope: scanned PDFs and OCR (ING-09, Phase 8), local-model extraction
of unknown layouts (ING-14, Phase 8), the review-queue API (3c), transfer detection (3c),
merchant cleaning and counterparty extraction (Phase 4), the upload UI (Phase 7). **No AI
anywhere in 3b.**

Working rules (unchanged from 3a): one step per commit (small follow-ups are fine), run
`cd backend && ./mvnw clean verify` and `cd frontend && npm test` before every commit, report
test counts, push `v1`, stop for review before the next step.

Ground rule for this phase: the BoA layout is known only from a structure-only review. Nothing
here is derived from a real statement, and no real statement is read into code, tests, docs or
commits. Where the plan guesses at the layout, the guess is called out and the debug tool (step 5)
is how it gets confirmed.

---

## 1. Architecture

### 1.1 Extraction library: Apache PDFBox 3.0.x

Chosen: **Apache PDFBox 3.0.x** (latest patch when step 1 starts), `org.apache.pdfbox:pdfbox`.

- **License:** Apache 2.0. iText is AGPL, which a portfolio project shouldn't carry.
- **Positioned text:** `PDFTextStripper` hands every glyph out as a `TextPosition` with x, y, width,
  font size and text direction, already adjusted for page rotation. Side-by-side check columns and
  continuation lines need exactly that.
- **Memory-only loading:** `Loader.loadPDF(byte[], …, streamCacheCreateFunction)` with
  `MemoryUsageSetting.setupMainMemoryOnly(maxBytes)` keeps everything on the heap under a cap. No
  scratch files.
- **Passive by design:** no JavaScript engine, no network fetches, no rendering unless asked for.
- **Pure Java, mature, widely used.** Tabula is built on PDFBox, and its table heuristics would
  sit between us and the glyphs we need to control. Poppler `pdftotext` is a native process that
  works from files on disk. OpenPDF's extraction is weak.

Cost: about 3 MB of jars (pdfbox, fontbox, pdfbox-io, commons-logging). Accepted.

### 1.2 Package layout

```
backend/src/main/java/com/finalysis/ingestion/pdf/
  PdfTextExtractor        loads with limits, rejects encrypted, extracts positioned text
  PdfLimits               the numbers in §2, one record, configurable for tests
  PdfDocumentText         pages → lines → words (records, no PDFBox types leak out)
  PdfPage, PdfLine, PdfWord
  PdfStatementParser      interface extending StatementParser (text-level detect/parse)
  PdfSkeleton             masked line dump used by the CLI (§6)
  PdfSkeletonCli          main(), no Spring
  boa/
    BankOfAmericaCheckingParser   the layout parser (id "boa-checking")
    BankOfAmericaLineClassifier   line → LineKind for this layout; shared by parser and CLI
    BankOfAmericaLayout           headings, summary labels, patterns: all bank-specific text lives here
```

Bank-specific knowledge stays in `boa/`. The extractor knows nothing about banks, and neither do
the import, reconciliation or fingerprint code.

### 1.3 The extraction model (fingerprint-critical)

`PdfTextExtractor.extract(ImportFile) → PdfDocumentText` collects glyphs with a `PDFTextStripper`
subclass, then builds lines and words from geometry. It never uses the stripper's own line
output, which depends on drawing order:

- **Glyphs:** only horizontal text (`getDir() == 0`) is kept. Rotated text, such as the vertical
  print codes some statements carry in the margin, is dropped and counted. A glyph drawn twice at
  the same position, the "fake bold" trick, is kept once.
- **Lines:** glyphs whose baselines are within 0.25 × font size of each other, per page. Lines run
  top to bottom and words left to right, **whatever order the PDF drew them in**.
- **Words:** a space glyph, or a horizontal gap of at least 0.3 × font size, ends a word.
- **`PdfLine.text()`:** the line's words joined by one space. Column gaps survive only in each
  word's x position.
- **`PdfLine.number`:** 1-based and global across pages, in this order. Every line counts, headers
  and footers included, so it matches the debug tool's output. Used as `source_line_number` (DAT-07).
- `PdfWord(text, x, xEnd, baselineY, fontSize, bold)`, `PdfLine(number, page, y, words)`,
  `PdfPage(number, width, height, lines)`. None of these `toString()` methods prints text (SEC-10).

**Why this is fingerprint-critical:** the description that feeds `TransactionFingerprint` is built
from these words. A different gap threshold can turn `AMAZON MKTPLACE` into `AMAZONMKTPLACE`,
which survives whitespace normalization and changes every fingerprint. So:

1. The word and line rules are locked by golden tests on the committed fixtures (exact line texts).
2. `TransactionFingerprint`'s "Changing the algorithm" Javadoc gains a bullet: PDF word and line
   rules, and PDFBox upgrades.
3. PDFBox version bumps are manual and never automatic. The README gives the upgrade check:
   re-import a real statement into a scratch database and confirm 0 new rows.

### 1.4 Parser contract for PDFs

`PdfStatementParser extends StatementParser` adds:

```java
FormatMatch detect(PdfDocumentText text);                       // DEFINITE or NONE, never FALLBACK
ParsedStatement parse(PdfDocumentText text, ParseContext context);
```

The default `detect(ImportFile)` and `parse(ImportFile, …)` extract and delegate, so the
`StatementParser` contract still holds. The import, though, **extracts once** and passes the
same `PdfDocumentText` to every PDF parser. That way the security checks (encryption, limits)
run once, before any layout code, and an encrypted file is reported as encrypted, not as
"no parser recognized it".

- One class per layout (ING-12). A new layout is a new `PdfStatementParser` bean, and existing
  ones are not touched (GR-05).
- PDF layouts have **no fallback level**: a layout is either recognized or it isn't.

### 1.5 Detection by layout markers

`BankOfAmericaCheckingParser.detect` returns DEFINITE only if page 1 has all of these, judged after
collapsing whitespace, never by institution name:

1. A period line: `for <Month D, YYYY> to <Month D, YYYY>`.
2. `Beginning balance on <Month D, YYYY>` followed by an amount on the same line.
3. Summary lines `Deposits and other additions` and `Withdrawals and other subtractions`, each
   ending in an amount, below (2).
4. `Ending balance on <Month D, YYYY>` followed by an amount, below (3).

Otherwise NONE. Section headings are not detection markers. A statement with no activity may
print none, and requiring them would turn it into an unparsed upload for no reason.

Because detection is structural, the synthetic fixtures (fictional bank name) are detected
exactly as a real statement would be. A test removes each marker in turn and asserts NONE.

### 1.6 Upload pipeline changes

Today `UploadCheck.checkContent` refuses `%PDF-` with 415. New flow, with changes in **bold**:

1. Account lookup → size (10 MB, unchanged) → clean file name.
2. **Extension allow-list gains `.pdf`.**
3. **Content sniffing returns a `FileKind`:** `PDF` when the bytes start with `%PDF-` at offset 0,
   else `TEXT`. Text files get the existing checks: controls, encoding, HTML, ZIP.
4. **Extension and kind must agree:** `.pdf` if and only if `PDF`. A mismatch is 415, so a
   renamed file can't steer detection.
5. SHA-256 → a **statement** with this hash is 409 `already-imported` (unchanged). An unparsed
   upload with this hash does **not** block; detection runs again (§4.3).
6. **`PDF`: `PdfTextExtractor.extract`** (needs a password → 422 `pdf-encrypted`, limits → 422
   `pdf-too-complex`, unreadable → 422 `parse-error` without a line), then
   **`registry.selectPdf(text)`**, which asks only `PdfStatementParser`s.
   - Exactly one DEFINITE → parse and import. Any unresolved unparsed upload for this account and
     hash is marked resolved in the same transaction.
   - None → **unparsed upload (ING-13)**, 202 Accepted, never a 415 and never a statement.
   - Several → 422 `ambiguous-format`, as today.

   `TEXT`: `registry.select(file)` asks only non-PDF parsers, unchanged.
7. Hints check (last four, CHECKING, USD) → `StatementImportWriter`, unchanged. Reconciliation
   now has printed balances and section totals to work with.

The registry filters by kind, so the CSV parser is **never asked about a PDF**. That matters:
an uncompressed PDF is plain ASCII with commas and would otherwise pass CSV detection as
FALLBACK. The OFX and CSV parsers are not modified.

**Preview** (`POST …/imports/preview`):
- Recognized PDF: the existing statement preview (`format`, period, `transactionCount`).
- Unrecognized PDF: `format: "pdf"`, `recognized: false`, `reason`.
- Nothing is written in either case; a preview never creates an unparsed upload.

---

## 2. PDF security (SEC-09, SEC-10 extended)

### 2.1 Limits (`PdfLimits`, one record, overridable in tests)

| Limit | Value | Enforced where | Problem |
|---|---|---|---|---|
| File size | 10 MB (unchanged) | multipart + `UploadCheck` | 413 `payload-too-large` |
| Pages | 50 | after load, before extraction | 422 `pdf-too-complex` |
| Heap for decoded streams | 64 MB | `MemoryUsageSetting.setupMainMemoryOnly` | 422 `pdf-too-complex` |
| Decoded content per page | 8 MB | own counting stream over the page's content streams, read before extraction | 422 `pdf-too-complex` |
| Glyphs | 20,000 per page, 200,000 per document | stripper callback | 422 `pdf-too-complex` |
| Form XObject nesting | depth 8 | stripper override (`showForm`) | 422 `pdf-too-complex` |
| Extraction time | 10 s | deadline checked per page and every 1,000 glyphs | 422 `pdf-too-complex` |

Real checking statements run to a few pages and well under 1 MB, so these limits sit far above
real files and far below harm.

- **Decompression bombs:** a Flate stream expanding to gigabytes has to fail fast with a bounded
  heap. The per-page counting read stops at 8 MB without inflating the rest, and the 64 MB cache
  cap backs it up. Step 1 has a test with a roughly 10 KB stream inflating to 1 GB.
- **Time limit:** PDFBox can't be interrupted, so the deadline works only where callbacks run. A
  hard kill would need a separate process, which is out of scope for a local single-user app. The
  size and decode caps bound the worst case instead. This is a documented limitation.

### 2.2 Encrypted PDFs

- **A password is required to open it** → 422 `pdf-encrypted`: "This PDF is password-protected.
  Download an unprotected copy from your bank, or open it and save it without a password." No
  password field, ever.
- **Encrypted with an empty user password** (owner-only restrictions such as no printing or no
  copying, common on bank statements): **accepted**. Any viewer opens these without a secret, so
  rejecting them would only reject real statements. Permission flags are ignored: the text is
  read locally for the user's own records.

### 2.3 Inert content

- We never read the outline, `/OpenAction` or `/AA` actions (JavaScript), `/EmbeddedFiles`,
  AcroForm or XFA, annotations (links, widgets), or file specifications. PDFBox doesn't run
  JavaScript or fetch remote resources.
- Text extraction reads page content streams only. Annotation and form appearances are not
  extracted, and image XObjects are not decoded.
- Tests build hostile PDFs in memory with PDFBox (JavaScript `OpenAction`, embedded file, form
  field with a marker value, link annotation with a marker URI) and assert that each imports, or
  becomes an unparsed upload, normally, and that **the marker never shows up** in any parsed field, log line or response.

### 2.4 No temp files, and no font cache on disk

- Loading is main-memory only.
- PDFBox's default `FontMapper` scans system fonts and **writes a cache file to the user's home
  directory**. A `NoSystemFontsMapper` is installed at startup: bundled fallback fonts only, no
  scanning, no cache.
- Standard-14 fonts use PDFBox's bundled metrics.
- The existing real-server no-temp-file test (`MultipartDiskTest`) is extended to PDFs and also
  asserts that nothing new appears under `${user.home}/.pdfbox.cache` or `java.io.tmpdir`.

### 2.5 No PDF text in logs or errors

- `org.apache.pdfbox` and `org.apache.fontbox` loggers are **OFF** in `application.yaml`. PDFBox
  warns at glyph level ("No glyph for U+…"), which leaks text one character at a time.
- `ExposureTest` refuses any config that turns them back on.
- PDFBox exception messages are never passed on. Each failure maps to a fixed problem detail.
- `SensitiveDataLeakTest` gains PDF cases with marker text in descriptions, summary lines,
  headers, the account number, form fields and metadata (`/Title`, `/Author`), across success,
  unparsed, encrypted, too-complex and parse-error paths.

### 2.6 SEC-09: PDF text never leaves the machine

There is no AI call in 3b. An ArchUnit test (new test-scoped dependency) asserts that
`com.finalysis.ingestion..` doesn't depend on `org.springframework.ai..`, `com.finalysis.chat..` or
`com.finalysis.categorize..`. ING-14's later local-model fallback will need an explicit,
reviewed exception to that rule.

---

## 3. BoA checking layout parser rules

Every rule below is locked by tests on synthetic fixtures. **Guess** marks a point inferred from
the structure-only review that the debug tool must confirm.

### 3.1 Line classification (`BankOfAmericaLineClassifier`)

Each line gets exactly one `LineKind`. The parser and the debug tool use the same classifier.

| Kind | Rule |
|---|---|
| `PERIOD` | `for <Month D, YYYY> to <Month D, YYYY>` (first match on page 1) |
| `ACCOUNT` | `Account number:` followed by digit groups. Only the last four leave the parser, as `AccountHints.lastFour` |
| `SUMMARY` | a known summary label followed by an amount, on page 1, in the account summary block |
| `SECTION_START` | a known section heading, exact text after whitespace collapse |
| `SECTION_CONTINUED` | `<known heading> - continued` (**guess** at the exact suffix) |
| `COLUMN_HEADER` | the header row under a section heading (`Date`, `Description`, `Amount`; for checks `Date`, `Check #`, `Amount`, possibly twice). Records the column x positions |
| `TXN` | inside a section: first word a date at the date column, last word an amount right-aligned to the amount column |
| `CHECK_ROW` | inside the checks section: one or more `date checkno[*] amount` groups |
| `CONTINUATION` | see §3.3 |
| `TOTAL` | `Total <section heading in lower case>` followed by an amount; closes the open section |
| `PAGE_HEADER` | on pages after the first, lines above the page's first structural line |
| `PAGE_FOOTER` | `Page N of M`, `continued on the next page`, and lines below the page's last structural line |
| `OTHER` | anything else outside a section (address block, marketing text, legal notes). Ignored |

### 3.2 Sections → `SourceSection` (fingerprint-relevant, locked)

| Printed heading | SourceSection | Expected sign |
|---|---|---|
| Deposits and other additions | DEPOSIT | ≥ 0 |
| Withdrawals and other subtractions | WITHDRAWAL | ≤ 0 |
| Checks | CHECK | ≤ 0 |
| Service fees | FEE | ≤ 0 |

- **Unknown heading** (a line styled like a heading inside the transaction area that matches none
  of the above): **parse error naming the line**, not a guess.
  - Possible real variants such as "ATM and debit card subtractions" or "Other subtractions", and
    any per-card subheadings or subtotals, are decided after you run the skeleton tool on a real
    statement in step 5. Until then they fail safely.
  - Two headings must never share a SourceSection without a decision, because section totals are
    keyed by SourceSection.
- **Section state machine:**
  - `SECTION_START` opens a section. A `SECTION_CONTINUED` for that same section on a later page
    reopens it, and its rows carry on in the same section.
  - `TOTAL` closes it.
  - Each of these is a parse error with the line number:
    - a row outside a section;
    - a `TOTAL` for a section that isn't open;
    - a continued heading for a different section;
    - a second `SECTION_START` for a closed section;
    - a section still open at the end of the document.
- **Section totals as printed:**
  - `sectionTotals[s]` is the amount on the section's `TOTAL` line, signed as printed. The layout
    prints money out with a minus, which is already the app's sign convention (GR-03).
  - A section in the summary block but missing from the body gets the summary amount as its
    total. That amount is expected to be 0.00; a non-zero amount with no rows fails the existing
    ING-10 check.
  - The parser also checks each summary amount against its section's `TOTAL`. A difference is a
    parser warning (`summary line N and total line M disagree for DEPOSIT`) → NEEDS_REVIEW.
- **Signs:** amounts are taken as printed. A row whose sign contradicts its section is imported as
  printed with a warning (`line N: positive amount in WITHDRAWAL`) → NEEDS_REVIEW, the same
  approach as the OFX TRNTYPE warning. Zero is valid in every section.

### 3.3 Wrapped descriptions (locked; feeds the fingerprint)

**A row's raw description** is:
- the text of the TXN line's words between the date word and the amount word, joined by one
  space;
- then, for each continuation line, `"\n"` followed by that line's full text, as extracted.

**Nothing is trimmed, de-hyphenated, merged or redacted.** A word broken across lines stays broken
(`MKTPL\nACE`). Merchant cleaning in Phase 4 deals with it, as CLAUDE.md requires.

Why `"\n"`:
- It keeps the printed line structure in `raw_description`, which is closest to "as extracted".
- `TransactionFingerprint` collapses all whitespace, so `"\n"` and `" "` fingerprint the same.
  Choosing between them can't break dedup, while `raw_description` stays faithful for display
  and source tracing.

**A line is a `CONTINUATION` only if all of these hold:**
1. It is inside an open section, on the same page as the previous TXN or CONTINUATION, and
   directly below it with nothing in between.
2. It has no date word in the date column and no amount word at the amount column.
3. Its first word starts at the description column's x, within ±3 pt.
4. Its vertical gap from the previous line is at most 1.6 × that section's median row pitch, so a
   spaced-out subheading or footer is not glued on.

Edge cases:
- **More than 3 continuation lines on one row:** the row imports, with a warning
  (`line N: description spans K lines`) → NEEDS_REVIEW. This catches a footer or subheading
  swallowed into a description, which reconciliation can't see because amounts are unaffected.
- **Wrap across a page boundary:** not supported. A non-row line straight after a continued
  heading is a parse error, so a skeleton gets shared and the rule extended. Silently attaching
  text across pages would put an unverifiable string into the fingerprint.

`source_line_number` is the TXN line's number. `source_line_text` is the TXN line's full text
plus `"\n"` and each continuation line's text.

### 3.4 Page continuations, headers, footers

- Page headers and footers are classified as in §3.1. They are never rows or continuations, and
  never part of a section.
- They still count in line numbering (§1.3).
- The account number and customer name live in page headers. They never reach `source_line_text`,
  and only the last four digits leave the parser.

### 3.5 $0.00 rows: imported

Decided: **import** them as rows with amount 0.00 and the printed section.
- Every other format imports zero rows. 3a's principle is that printed rows are never silently
  dropped (DAT-04, DAT-07).
- They don't change any total, so reconciliation is unaffected.
- They carry signal for later phases: a $0.00 card verification or a free trial converting is
  exactly what ANA-04's new-subscription alert looks for.
- `printed_amount` keeps the printed text (`0.00` or `-0.00`). `amount` is 0.00, since BigDecimal
  has no negative zero, so the fingerprint is stable.

### 3.6 Dates (ING-02)

- **`printedDate`** is the row's date column, the posting date, in `MM/DD/YY` → 20YY (**guess**).
  It goes into the fingerprint. If a skeleton shows `MM/DD` with no year, the year comes from the
  statement period: the period-start year when that month falls inside the start year's part of
  the period, else the end year.
- **`postDate`** = `printedDate` for every row: the column is the posting date. This matches the
  OFX parser, where `DTPOSTED` sets both.
- **`txnDate`** = the embedded purchase date when present, else `printedDate`. It is derived and
  never fingerprinted. The embedded date is:
  - the first standalone `MM/DD` token (`\b(0[1-9]|1[0-2])/(0[1-9]|[12]\d|3[01])\b`) on the TXN
    line's description part, never on continuation lines;
  - only in DEPOSIT and WITHDRAWAL rows;
  - kept unchanged in `raw_description`.
- **Year inference:**
  - Take the posting date's year Y. If `MM/DD` in year Y is after the posting date (a purchase
    can't post before it happens), use Y−1. That covers December/January: a 01/02/27 post with
    embedded 12/30 gives 2026-12-30.
  - 02/29 tries Y, then Y−1, keeping the first valid date that isn't after posting.
  - If no candidate is valid, or the result is more than 45 days before posting (probably a store
    number that looks like a date), the token is ignored and `txnDate = printedDate`. No warning,
    because the value is derived.
- Rows outside the statement period get the existing out-of-period warning.
- Assumed for now: `MM/DD` embedded dates and `MM/DD/YY` row dates. The step 5 skeleton confirms
  them before step 6 locks anything.

### 3.7 Checks

- **Row shape:** one or more groups per line, each `date checkno[*] amount`, with x ranges taken
  from the checks section's `COLUMN_HEADER`, which repeats `Date Check # Amount` per column group.
  - Groups are read left to right; lines top to bottom.
  - The last line may hold only one group.
  - Every word on the line must belong to a group. Leftovers are a parse error.
- **Each group becomes one row:**
  - `section` CHECK;
  - `checkNumber` = the digits only (`"1236"`);
  - `rawDescription` = the check-number cell **exactly as printed**, including a gap marker `*` if
    present (`"1236*"`);
  - `printedAmount` as printed;
  - `printedDate` = `postDate` = `txnDate` = the group's date.
- **Why the check-number cell is the description:** a check row has no description. A made-up
  text such as `"CHECK 1236"` would not be "as extracted". The cell is printed text, and it
  identifies the row in the fingerprint.
- `source_line_number` is the line. `source_line_text` is **the group's text only**, not the
  neighbouring check.
- The `*` marker means a gap in the check sequence. It stays in the raw description and is not
  interpreted.

### 3.8 Balances and reconciliation

- **Opening balance** = `Beginning balance on …`. **Closing balance** = `Ending balance on …`.
  Period = the PERIOD line.
- The existing `Reconciliation` runs unchanged:
  - printed balances (opening + rows = closing);
  - request balances, if sent (they must agree with the printed ones);
  - section totals: each printed total equals the sum of its section's rows, and a total with no
    rows must be zero.
- **This is the first real input for both the printed-balance check and the section-total
  check.** A clean statement → RECONCILED. A total mismatch → NEEDS_REVIEW with the existing
  warning format.
- Continuity doesn't apply, because both balances are printed.
- **Account hints:** last four from `ACCOUNT`, type CHECKING, currency USD. A BoA savings layout
  is out of scope: uploading this layout to a savings account is 422 `account-mismatch`.

### 3.9 Counterparty (DAT-08): deferred to Phase 4

Deferred (approved with the plan).
- Pulling the counterparty out of Zelle or transfer descriptions uses the same patterns SEC-08
  needs in order to *redact* those names before cloud calls.
- Building both in Phase 4, in one place (categorization rules, which may hold bank-specific
  patterns), avoids two pattern sets drifting apart.
- `txn.counterparty` stays null in 3b, like `merchant`.

---

## 4. Unrecognized PDFs → unparsed uploads (ING-13)

An unrecognized PDF is **not a statement**. It has no period, balances or rows, so a statement row
would break "every statement has a period" or need nullable columns. It is recorded in its own
table instead, and the `statement` table is unchanged.

### 4.1 What is stored

**V8** (`V8__unparsed_upload.sql`):

```sql
CREATE TABLE unparsed_upload (
    id                    BIGINT GENERATED BY DEFAULT AS IDENTITY,
    account_id            BIGINT      NOT NULL REFERENCES account (id),
    file_sha256           TEXT        NOT NULL,   -- lowercase hex, CHECK ~ '^[0-9a-f]{64}$'
    file_name             TEXT        NOT NULL,   -- cleaned like statement.source_file_name
    reason                TEXT        NOT NULL,   -- CHECK IN ('UNRECOGNIZED_LAYOUT', 'NO_TEXT_LAYER')
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at           TIMESTAMPTZ,
    resolved_statement_id BIGINT      REFERENCES statement (id) ON DELETE SET NULL,
    -- a resolving statement implies resolved_at; resolved_at alone means dismissed (3c)
    CHECK (resolved_statement_id IS NULL OR resolved_at IS NOT NULL)
);
-- at most one open upload per account and file
CREATE UNIQUE INDEX uq_unparsed_upload_open ON unparsed_upload (account_id, file_sha256)
    WHERE resolved_at IS NULL;
```

Constraint names follow V1's conventions (`pk_`, `fk_`, `ck_`, `uq_`).

- **Reasons:**
  - `UNRECOGNIZED_LAYOUT`: the PDF has text, but no parser claims it.
  - `NO_TEXT_LAYER`: fewer than 20 non-whitespace characters in the whole document. Almost
    certainly a scan, so it belongs to the Phase 8 OCR work.
- **Not stored:** file bytes (SEC-04 is Phase 8), extracted text, page count, skeleton.
- **Uniqueness is per account.** The same file held on two accounts gives two independent rows.
  Nothing was imported, so there is nothing to collide. If one of them is later recognized and
  imported, `uq_statement_file_hash` still keeps the statement unique.
- **Writes:** inserting and resolving both happen under the account row lock the import writer
  already takes. The partial unique index is the backstop.
- Entity `UnparsedUpload` and repository in `com.finalysis.ingestion`. `toString()` gives id,
  reason and a hash prefix only (SEC-10).

### 4.2 Response

**202 Accepted**, with body `UnparsedUploadResponse`:
`id`, `accountId`, `reason`, `fileName`, `createdAt`, plus a fixed `detail`: "No supported
statement layout was recognized; nothing was imported. The file is held for review."

- 202 because nothing was imported, and the item waits for a later decision (a new parser, OCR in
  Phase 8, or dismissal in 3c).
- No `Location` header in 3b. The review-queue API in 3c adds the read endpoints.
- `ImportSummary`, `StatementResponse` and `StatementListItem` are unchanged.

### 4.3 Re-uploading the same file (same SHA-256)

A statement with this hash is still 409 `already-imported`. Otherwise detection **runs again**:

| Open unparsed upload for this account and hash | Detection now | Result |
|---|---|---|
| yes | still unrecognized | **202 with the existing row** (same id, original `createdAt`). Nothing is inserted |
| yes | a parser claims it | **Normal import** (201 `ImportSummary`). In the writer's transaction, the upload gets `resolved_at = now()` and `resolved_statement_id` = the new statement |
| no | unrecognized | a new row, 202 |
| no | a parser claims it | normal import, as for any file |

- A failed import doesn't touch the upload: a parse error, account mismatch or ambiguous format
  is reported as usual and the upload stays open.
- Resolved rows are history and never reopen. If a dismissed file (3c) is re-uploaded and is still
  unrecognized, it gets a new open row.

### 4.4 What the 3c review-queue API will need (not built here)

- **One list of two kinds of item:** NEEDS_REVIEW statements, and unparsed uploads with
  `resolved_at IS NULL`, across accounts. Each shows its account, file name, timestamp, and reason
  or warnings.
- **Actions:**
  - dismiss an unparsed upload (`resolved_at` set, no statement);
  - accept a reconciliation failure ("reviewed", keeping the warnings);
  - delete a parsed statement (its txns cascade; a resolving upload's `resolved_statement_id`
    becomes null and its `resolved_at` stays).
- An unparsed upload can't be re-processed from storage: there are no bytes. It is resolved by
  re-uploading the file once a parser exists (§4.3). ING-14 (Phase 8) needs stored file bytes,
  which needs SEC-04 encrypted storage first.

---

## 5. Synthetic PDF generator (ING-11)

### 5.1 Approach: a dependency-free PDF writer in `tools/`

`tools/SyntheticPdfGenerator.java` is a single-file Java 21 program like `SampleDataGenerator`
(`java tools/SyntheticPdfGenerator.java [output-dir]`). It contains a small PDF writer: objects,
xref, standard-14 Helvetica and Helvetica-Bold, and `BT/Tf/Td/Tj/TJ/ET` text operators.

- **Input is the committed answer key.** The generator reads `sample-data/answer-key.csv` and
  `sample-data/statements-manifest.csv` for the Northfield checking account, and never re-derives
  transactions itself. The answer key stays the single source of truth, and the PDFs can't drift
  from it: a changed answer key changes the regenerated PDFs, which the byte test catches.
- It is separate from `SampleDataGenerator` because that file is already about 800 lines and
  Java 21's single-file launch can't share code between files.
- **Output goes under `sample-data/pdf/` only**, edge cases in `sample-data/pdf/edge-cases/`.
  `SampleDataTest` compares every file under `sample-data/` with `SampleDataGenerator`'s output,
  so step 2 makes it skip `pdf/`. `SyntheticPdfTest` owns that directory.

**Byte-stable, verified by bytes *and* content:**
- **Bytes:** no `/CreationDate`, no `/ID`, no `/Producer`, fixed object order, uncompressed
  content streams, coordinates written with `Locale.ROOT` and two decimals.
  `SyntheticPdfTest` runs the generator into a temp directory and compares bytes with the
  committed files, exactly like `SampleDataTest`. Any change to the generator shows up in review
  as a fixture diff.
- **Content, monthly statements:** checked against the **existing answer key**. Each parsed row
  must match its answer-key row: date, amount, and description with `"\n"` read as a space.
  Sections must follow the sign, and balances must match `statements-manifest.csv`.
- **Content, edge cases:** they aren't in the answer key, so the generator writes
  `<name>.expected.csv` beside each one: every expected row (printed date, txn date, section,
  amount, check number, raw description with `\n` escaped) plus the period, balances and section
  totals.
- A golden test also pins the extractor's line texts for each fixture (§1.3).

Why not generate with PDFBox:
- It would need the backend classpath, so it couldn't run as a single-file program.
- Its output bytes can change between PDFBox versions.
- PDFBox stays test-scoped for **hostile** PDFs (encrypted, JavaScript, embedded files, forms,
  bombs, 51 pages). Those are built in memory per test and never committed.

**Realism the writer adds on purpose**, because real PDFs aren't drawn line by line:
- some pages draw the amount column before the description column;
- some strings are split into `TJ` arrays with kerning offsets;
- one fixture places every glyph individually;
- headings are drawn twice at the same spot (fake bold).

A test-time variant Flate-compresses every content stream in memory and asserts identical
extraction, so compressed input is covered without committing non-reproducible bytes.

**Limitation:** real statements use embedded subset fonts with ToUnicode maps, which the writer
doesn't reproduce. The debug tool and real-file testing cover that gap.

### 5.2 Content rules

- **Institution:** the existing fictional **Northfield** bank from `sample-data`. Account number
  `0047391001`, the same as the Northfield checking OFX files, so the last four (`1001`) match the
  account the other sample formats import into. Customer "SAMPLE CUSTOMER". Fictional address.
- Every page carries **"SYNTHETIC TEST DATA"** in the header and footer. No logos, no real bank
  names, no real addresses or routing numbers.
- The layout reproduces the structure from §3: the summary block, sections, totals, column
  headers, page headers and footers, `- continued` headings. Only the institution is Northfield.
  Detection therefore proves it relies on structure.
- File names follow the existing pattern and never contain a real bank name:
  `sample-data/pdf/northfield-checking_2026-04.pdf`.
- `sample-data/pdf/pdf-manifest.csv` lists file, expected parser (`boa-checking` or none), unparsed
  reason, period, balances and expected status.

### 5.3 Fixtures

**Monthly statements:** six files, `pdf/northfield-checking_2026-04.pdf` to
`…_2026-09.pdf`. Same transactions, months and balances as the answer key (60 rows), all
expected RECONCILED.

- Northfield checking has only deposits and withdrawals. The Checks and Service fees sections
  are absent from the body, and their summary lines print 0.00. That covers "summary line without
  a section" (§3.2) on every statement.
- The description column is narrow enough to wrap the longer answer-key descriptions at a word
  boundary (e.g. the payroll row's `… PPD` / `ID: …`), so wrapped descriptions are checked against
  the answer key.
- A small rows-per-page setting pushes withdrawals onto a second page in some months, with a
  `- continued` heading.
- PDF and CSV fingerprints for these rows may coincide (same date, amount, description and
  section). As decided in 3a, no test asserts cross-format dedup.

**Edge cases** (hand-built Northfield statements outside the answer key, each with
`.expected.csv`):

| File (`pdf/edge-cases/`) | Exercises |
|---|---|
| `pdf-wrapped-descriptions.pdf` | 2- and 3-line wraps, a mid-word break, a continuation containing digits and `/`, a 4-line wrap (warning), a footer just below a row (must not be glued on) |
| `pdf-multipage-continued.pdf` | Withdrawals across 3 pages with `- continued` headings, repeated column headers, page headers and footers with `Page N of M`, out-of-order drawing |
| `pdf-zero-rows.pdf` | `0.00` in deposits, `-0.00` in withdrawals, two identical zero rows (occurrence numbers) |
| `pdf-card-purchases.pdf` | Card purchases with embedded `MM/DD` dates, a `MM/DD`-looking store number (ignored), a Service fees section |
| `pdf-year-boundary.pdf` | Period 2026-12-10 → 2027-01-09; post 01/02/27 with embedded 12/30; embedded 02/29 edge |
| `pdf-checks-side-by-side.pdf` | Two check column groups, odd count (last line has one group), a gap marker `*` |
| `pdf-section-total-mismatch.pdf` | Withdrawals total printed 10.00 off the rows → NEEDS_REVIEW with an ING-10 warning |
| `pdf-summary-disagrees.pdf` | Summary deposits amount differs from the section total → parser warning |
| `pdf-no-activity.pdf` | Summary only, all section amounts 0.00, no sections → RECONCILED, 0 rows |
| `pdf-unrecognized-layout.pdf` | A text PDF with a different structure (a fictional card statement) → unparsed upload, `UNRECOGNIZED_LAYOUT` |
| `pdf-no-text.pdf` | Vector shapes only → unparsed upload, `NO_TEXT_LAYER` |
| `pdf-glyph-by-glyph.pdf` | The April statement drawn one glyph at a time → same rows as April |

Built in memory by tests only: encrypted (with and without a user password), JavaScript
`OpenAction`, embedded file, form field, link annotation, Flate bomb, 51 pages, too many glyphs,
self-referencing form XObject, truncated and corrupt files, `.pdf` with CSV content, `%PDF` named
`.csv`.

---

## 6. Debug tool: masked layout skeleton

**Purpose:** when a real PDF fails, you run this locally and share its output instead of the file.

**Who runs it:** only you, on your own machine. Claude never runs this tool, or any other command,
on files outside the repo or in `private-data/`. During development Claude runs it only on the
synthetic fixtures in `sample-data/pdf/`.

**Run:**
```
cd backend && ./mvnw -q compile exec:java -Dexec.mainClass=com.finalysis.ingestion.pdf.PdfSkeletonCli \
  -Dexec.args="../private-data/stmt.pdf [--show-layout-words]"
```
This uses `exec-maven-plugin` (build plugin only). `scripts/pdf-skeleton.sh` wraps it.

**Plain Java, no Spring, no database, no network.** It reads one file, prints to stdout, writes
nothing, and logs nothing.

**Output**, one line per extracted line:
```
detect boa-checking: DEFINITE (markers: period ✓ beginning ✓ deposits ✓ withdrawals ✓ ending ✓)
p1 L0007 PERIOD            @ 36 xxx xxxxxxx 9, 9999 xx xxxxxxx 99, 9999
p1 L0012 SUMMARY           @ 36 xxxxxxxxx xxxxxxx xx xxxxxxx 9, 9999 | @498 $9,999.99
p2 L0041 SECTION_START     @ 36 xxxxxxxx xxx xxxxx xxxxxxxxx
p2 L0042 COLUMN_HEADER     @ 36 xxxx | @ 90 xxxxxxxxxxx | @520 xxxxxx
p2 L0043 TXN               @ 36 99/99/99 | @ 90 xxxxxxxxx 99/99 xxxxxx xxxxx | @512 -99.99
p2 L0044 CONTINUATION      @ 90 xxx 9999999999 xx
p2 L0061 TOTAL             @ 36 xxxxx xxxxxxxx xxx xxxxx xxxxxxxxx | @505 $9,999.99
p3 L0080 PAGE_FOOTER       @290 xxxx 9 xx 9
parse: FAILED at line 52: unknown section heading
reconciliation: printed balances PASSED, section totals FAILED (WITHDRAWAL)   ← pass/fail only, no amounts
```

- **Masking:** digits → `9`, letters → `x`. Spaces and punctuation (`$ , . - / * # :`) are kept,
  because they carry the structure. `@x` is the word's x position; `|` marks column gaps
  (over 12 pt).
- **Classification** uses the classifier of the PDF parser that detects the file. If none does,
  every line is `UNCLASSIFIED`, and each detection marker is reported ✓ or ✗, which shows why it
  wasn't recognized.
- **`--show-layout-words`** unmasks only words from a fixed list of layout vocabulary taken from
  `BankOfAmericaLayout` (`Deposits`, `Withdrawals`, `Total`, `continued`, `Page`, `Beginning`, `balance`,
  …). That shows a renamed heading without exposing descriptions. It's opt-in, because a merchant
  could share a word with the list ("Total Wine").
- **Never printed:** amounts (masked), reconciliation differences, PDFBox messages, metadata, file
  name.
- A test runs the CLI on every fixture and asserts that the output contains no letter other than
  `x` outside labels and the allow-list, and no digit other than `9`.

---

## 7. Tests and requirement mapping

| Requirement | Tests (new unless noted) |
|---|---|
| ING-08 | `BankOfAmericaCheckingParserTest` (the six monthly PDFs against the existing answer key and statements manifest; every edge case against its `.expected.csv`: rows, sections, totals, balances, period, hints, warnings); `PdfImportTest` (end to end through the HTTP endpoint: baseline RECONCILED, totals mismatch NEEDS_REVIEW, re-upload 409, overlapping period dedup, preview writes nothing, account-mismatch for a savings account) |
| ING-11 | `SyntheticPdfTest` (byte-identical regeneration from the answer key; every page carries SYNTHETIC TEST DATA; no real bank name in any fixture; manifest consistent: opening + rows = closing, section totals = row sums except the two deliberate edge cases); `SampleDataTest` (changed: skips `pdf/`) |
| ING-12 | `StatementParserRegistryTest` (extended: PDF kind asks only PDF parsers; two DEFINITE PDF parsers → ambiguous; CSV never sees a PDF); `SampleDataParserSelectionTest` (extended: every sample file claimed by exactly the parser in its manifest) |
| ING-13 | `UnparsedUploadImportTest` (202 body for both reasons; no statement or txn written; every row of the §4.3 table, including resolution with the new statement id when a test-only parser claims the file; a failed import leaves the upload open; per-account uniqueness; preview writes nothing); `UnparsedUploadRepositoryTest` (V8 CHECKs, partial unique index, `ON DELETE SET NULL`) |
| ING-02 | `BankOfAmericaDatesTest` (printed and post date, embedded date, year boundary, 02/29, 45-day guard, `MM/DD` without year via the period); `BankOfAmericaAmountTest` (minus, `$`, thousands, `-0.00`, signs against sections) |
| ING-10, ING-06 | `PdfImportTest` (first real use of printed balances and section totals); existing `ReconciliationTest` unchanged |
| ING-03 | `BankOfAmericaFingerprintTest`: golden fingerprints for wrapped, zero, check and duplicate rows; `"\n"` vs `" "` joins give the same fingerprint; re-import inserts 0 rows |
| DAT-07 | source line number and text for TXN, continuation and check groups |
| SEC-09 | ArchUnit `NoAiInIngestionTest` |
| SEC-10 (PDF) | `PdfTextExtractorTest` (line and word rules, out-of-order drawing, rotated text dropped, duplicate glyphs, golden line texts, compressed variant); `PdfSecurityTest` (each limit at and above its boundary, encrypted with and without a user password, bomb with a bounded heap, form nesting, inert content markers, corrupt and truncated files); `UploadHardeningTest` (extended: `.pdf` and kind agreement); `SensitiveDataLeakTest` (extended: PDF markers on every path); `MultipartDiskTest` (extended: PDFs, no font cache, no temp files); `ExposureTest` (PDFBox loggers stay off) |
| Debug tool | `PdfSkeletonTest` (labels per fixture, masking guarantee, allow-list flag, no output for metadata) |

Database tests use Testcontainers. Every fixture is synthetic, from `sample-data/`.

---

## 8. Steps

| # | Step | Commit content | Main tests | Status |
|---|---|---|---|---|
| 0 | Plan and scope | This file; requirements v1.22 recording the approved decisions; CLAUDE.md current phase. Commit "Phase 3b plan and scope" | — | Done: `1dee291` |
| 1 | Extraction layer | PDFBox dependency; `PdfLimits`, `PdfTextExtractor`, `PdfDocumentText` records; memory-only loading, encryption handling (empty user password accepted, password required → rejected), limits, `NoSystemFontsMapper`, PDFBox loggers off; `pdf-encrypted` and `pdf-too-complex` problem types (not yet reachable through HTTP) | `PdfTextExtractorTest`, `PdfSecurityTest` on PDFs built in memory | Done: "Phase 3b step 1: PDF text extraction layer" |
| 2 | Synthetic generator | `tools/SyntheticPdfGenerator.java` reading the answer key; six Northfield monthly PDFs, edge cases with `.expected.csv`, `pdf-manifest.csv`; `SampleDataTest` skips `pdf/` | `SyntheticPdfTest`; golden extractor line texts per fixture; compressed variant | Done: "Phase 3b step 2: synthetic PDF generator" |
| 3 | PDF routing + unparsed uploads (ING-13) | `.pdf` allowed, `FileKind` sniffing with extension agreement, `PdfStatementParser`, kind-filtered registry; **V8 `unparsed_upload`**, entity, repository; 202 response; re-upload rules; preview for PDFs. With no layout parser yet, every PDF becomes an unparsed upload, so ING-13 is testable end to end before any layout code exists | `UnparsedUploadImportTest`, `UnparsedUploadRepositoryTest`, registry and upload-hardening extensions; `SampleDataParserSelectionTest` without the `pdf/` skip (see step 3 requirements) | Planned |
| 4 | Classifier + detection | `BankOfAmericaLayout`, `BankOfAmericaLineClassifier`, `BankOfAmericaCheckingParser.detect` (`parse` throws "not implemented" for now, so a detected file fails with a clear 422 instead of becoming an unparsed upload) | Classifier labels for every fixture line; marker-removal tests | Planned |
| 5 | Debug CLI | `PdfSkeleton`, `PdfSkeletonCli`, exec plugin, `scripts/pdf-skeleton.sh`; run by Claude on synthetic fixtures only. **Checkpoint:** you run it on a real statement and share the masked skeleton. Section mappings (unknown headings, subtotals) and the date formats are decided from it before step 6 | `PdfSkeletonTest` | Planned |
| 6 | Parser | Sections, totals, rows, continuations, $0.00 rows, dates, checks, summary cross-check, hints → `ParsedStatement` | `BankOfAmericaCheckingParserTest`, `BankOfAmericaDatesTest`, `BankOfAmericaAmountTest`, `BankOfAmericaFingerprintTest` | Planned |
| 7 | Import end to end | Registered bean; open unparsed upload resolved once recognized; reconciliation on printed balances and totals; the six Northfield PDFs imported against the answer key; Postman PDF requests (synthetic fixtures, plus the real-file folder pointing at `private-data/` through your local environment, nothing committed, run by you) | `PdfImportTest`, selection test | Planned |
| 8 | Cross-cutting security | Leak test PDF cases, no-temp and no-font-cache on a real server, ArchUnit no-AI rule, exposure checks | `SensitiveDataLeakTest`, `MultipartDiskTest`, `NoAiInIngestionTest`, `ExposureTest` | Planned |
| 9 | Docs | README (PDF import, limits, unparsed uploads, debug tool, PDFBox upgrade rule, limitations, **note that the project is not affiliated with any bank**), requirement statuses (ING-02, ING-08, ING-11, ING-12, ING-13, SEC-09 → Done), change log, `TransactionFingerprint` Javadoc, phase summary in this file | Postman run against a reset database | Planned |

### Step 1 notes (decisions beyond the plan)

- **PDFBox 3.0.8**, the latest 3.0.x release, pinned in `pom.xml` with a note that upgrades are
  manual (§1.3).
- **Decoded-size limits use an own pre-scan.** PDFBox's `Filter.decode` inflates a whole stream into
  an uncapped heap buffer, and the `MemoryUsageSetting` cap doesn't apply to decoding. So before
  extraction every non-image stream is decoded through PDFBox's own filter classes into a sink that
  throws at the limit. The 64 MB limit is the total across all such streams (content, forms, fonts,
  object streams) and also caps the stream cache. Plain objects are measured first, then objects
  inside object streams, so an object-stream bomb is caught before PDFBox opens it. The 8 MB page
  limit is then checked per page from the measured sizes. A stream marked as an image is skipped by
  the document pass, but still measured if a page uses it as content.
  **Residual risk:** a bomb in the cross-reference stream is decoded by PDFBox while loading, before
  any check can run. Documented for step 9's known limitations.
- **One more limit, `NESTING`:** a `StackOverflowError` from deeply nested objects is reported as
  `pdf-too-complex`, not a crash.
- **Limit exceptions are unchecked.** PDFBox logs and swallows `IOException`s thrown inside a form
  (`Do`) operator, which would silently skip a limit.
- **One exception type**, `PdfExtractionException`, with a `Reason` (password required, too complex,
  unreadable) and the exceeded `Limit`. PDFBox's exception is never chained as the cause, so a logged
  stack trace can't print its message.
- **`NoSystemFontsMapper`** maps every non-embedded font to PDFBox's bundled Liberation Sans.
  PDFBox's own mapper class is package-private, so swapping just its font provider isn't possible.
  The mapper is installed when `PdfTextExtractor` loads; the test helper installs it before building
  fonts.
- **Pages without a content stream are still recorded** (the stripper skips them), so page numbers
  match the document.
- **Word gap** is measured against the font size of the glyph before the gap; **line tolerance**
  against the line's topmost glyph.
- **Fonts with zero glyph widths** (a malformed font) put every glyph at one x, so the fake-bold rule
  collapses repeated letters. Found with a broken test font; acceptable for malformed input.
- **A truncated PDF** may still load, because PDFBox recovers what it can. It is either read or
  refused as unreadable, never a crash.
- **`PdfTextExtractor` is not a Spring bean yet**; step 3 wires it into the import.
- **`ExposureTest`** already checks the PDFBox and FontBox loggers (planned for step 8). The check
  goes through Commons Logging, the path PDFBox uses, and refuses any config that sets them above
  `OFF`. The YAML value is the quoted string `"OFF"`, because bare `OFF` is a YAML boolean.
- Tests: `PdfTextExtractorTest` (20) and `PdfSecurityTest` (27), all PDFs built in memory. Backend
  tests 956 → 1003; frontend 1.

### Step 2 notes (decisions beyond the plan)

- **Golden line texts come from the generator.** Next to every fixture it writes `<name>.lines.txt`:
  the lines it drew, grouped by baseline, left to right, joined by one space (`p1 L0018 …`). This
  is an oracle independent of the extractor; `SyntheticPdfTest` compares the extraction with it.
  The generator refuses to draw two strings on a line less than 6 pt apart or two lines less than
  6 pt apart, so its model can't silently disagree with the extractor's word and line rules.
- **`.expected.csv` format.** One CSV per recognized edge case, with a `record` column:
  `period_start`, `period_end`, `opening_balance`, `closing_balance`, `summary` (one per section),
  `section_total` (one per section; no line and no printed amount when the section isn't in the
  body, amount = the summary amount, §3.2), `row`, and `warning`. Columns: `line`, `section`,
  `printed_date`, `txn_date`, `printed_amount`, `amount`, `check_number`, `raw_description`
  (`\n` escaped), `warning`. `line` is the extracted line number (DAT-07): the TXN line for a row,
  the check line for a check group. Fields with commas (`"2,451.08"`) are quoted (RFC 4180).
  Expected `txn_date`s are written by hand in the generator, not computed with §3.6's rule.
- **Warning kinds are names, not messages:** `DESCRIPTION_SPANS_LINES`,
  `SUMMARY_DISAGREES_WITH_TOTAL`, `SECTION_TOTAL_MISMATCH` (the last comes from reconciliation,
  not the parser). Step 6 decides the message text and maps the kinds onto it.
- **`pdf-manifest.csv` columns:** `file, parser (boa-checking | none), unparsed_reason,
  answer_key_file, period_start, period_end, opening_balance, closing_balance, status`. The
  glyph-by-glyph fixture points at April's answer-key file. Only recognized edge cases have an
  `.expected.csv`; the six monthly files are checked against the answer key instead.
- **"4-line wrap (warning)" was ambiguous** with §3.3 (warn on more than 3 continuation lines).
  The wrapped fixture has descriptions of 2, 3, 4 and 5 lines. Only the 5-line one (4 continuation
  lines) expects the warning, so both sides of the boundary are covered and §3.3 stands.
- **Layout guesses, all fictional and to be checked against the step 5 skeleton:**
  - row amounts `-1,850.00` / `2,451.08`; summary section amounts the same, with `0.00` for an
    empty section; balances and section totals `$3,200.00` / `-$5,583.14`;
  - `Account number: 0047 3910 01` on page 1; later pages have a header line
    (`SAMPLE CUSTOMER  Account # 0047 3910 01  April 1, 2026 to April 30, 2026`) under the mark;
  - `continued on the next page` above `Page N of M` when a section carries on;
  - `<heading> - continued` and a repeated column header at the top of the next page;
  - the checks column header is `Date Check # Amount` twice, and check groups are laid out by row.
- **Institution text is `NORTHFIELD` alone**, never "Northfield Bank", because a real bank uses
  that name. The customer, address (`100 EXAMPLE WAY`, `SAMPLETOWN, XX 00000`) and account number
  are fictional; no routing number is printed.
- **Wrapping:** a 175 pt description column wraps the three longest answer-key descriptions
  (payroll after `PPD`, rent, savings transfer) at a word boundary. With 9 rows per page, April to
  August continue withdrawals on page 2 and September fits on one page.
- **Drawing realism:** every bold string (mark, headings, column headers, totals) is drawn twice
  at the same spot; odd months draw descriptions as `TJ` arrays with kerning; page 2 of each monthly
  statement draws amounts first; the multi-page fixture draws page 2 bottom to top and page 3
  amounts first; the glyph-by-glyph fixture draws space glyphs too, since a 0.278 em space is
  below the 0.3 em word gap and leaving it out would merge words.
- **`pdf-no-text.pdf` carries the mark once,** in the header: 17 characters, under the 20 that
  make a document count as having text (§4.1). A footer mark would push it over.
- **The unrecognized layout** is a statement for the fictional APEX VISA card from `sample-data`.
- **`SampleDataParserSelectionTest` also skips `pdf/`** (approved during step 2): it asserts every
  sample file resolves to the OFX or CSV parser. The skip is temporary: step 3 removes it (see
  step 3 requirements).
- Tests: `SyntheticPdfTest` (77: byte-identical regeneration, manifest completeness, and per
  fixture golden lines, Flate-compressed variant, mark on the first and last line of every page,
  no real bank name in bytes, text or expectations; answer-key rows and balances for the six
  monthly PDFs and the glyph copy; edge-case expectations consistent and pointing at their printed
  lines). Backend tests 1003 → 1080; frontend 1.

### Step 3 requirements (recorded at step 2 review)

- **`SampleDataParserSelectionTest` drops the temporary `pdf/` skip** added in step 2.
- It then checks **every PDF under `sample-data/pdf/` against `pdf-manifest.csv`**: a file whose
  `parser` is a parser id is claimed by exactly that parser; a file whose `parser` is `none` becomes
  an unparsed upload with the manifest's `unparsed_reason` (`UNRECOGNIZED_LAYOUT` or
  `NO_TEXT_LAYER`). Non-PDF sample files keep their current OFX/CSV expectations, and no PDF is
  ever offered to the CSV or OFX parser.
- **Step 3 isn't done until that skip is gone.**

Steps 4 and 5 come before the parser on purpose. The skeleton of a real statement is the cheapest
way to correct the guesses in §3 before they are locked into fingerprints.

### Known limitations (expected at the end of 3b)

- One layout (BoA checking). Savings, other banks and card statements become unparsed uploads.
- Scanned PDFs become unparsed uploads (`NO_TEXT_LAYER`) until Phase 8.
- Descriptions wrapping across a page boundary fail the import, by design, until a real skeleton
  shows the pattern.
- The extraction deadline is cooperative. The memory and decode caps bound the worst case.
- A decompression bomb in the cross-reference stream is decoded by PDFBox while loading, before
  the decoded-size checks can run (step 1 notes).
- Unparsed uploads can only be resolved by re-uploading the file (no stored bytes until SEC-04);
  dismissing them comes with the 3c review queue.
- PDFBox upgrades can change extracted text and so fingerprints. They are manual, with a real-file
  re-import check.

---

## Decisions at approval

Approved with one design change and these answers. They override anything older in this file or
the conversation.

- **Design change:** unrecognized PDFs are not statements. They go to a new `unparsed_upload`
  table (V8), not to a statement row with a nullable period. The response is 202 Accepted with the
  upload's id and reason. Re-uploading re-runs detection instead of returning 409: still
  unrecognized → the existing upload (202); now recognized → normal import, upload resolved with
  the new statement id (§4).
- **3c note:** the review-queue API lists NEEDS_REVIEW statements and unresolved unparsed uploads.

| # | Question | Answer |
|---|---|---|
| 1 | Encrypted PDFs | Accept PDFs encrypted with an empty user password (restrictions only); reject any PDF that needs a password to open, with a clear error |
| 2 | $0.00 rows | Imported |
| 3 | Unrecognized PDF response | 202 Accepted (design change above) |
| 4 | Unknown section headings, subtotals | Fail safely for now; mappings decided after the step 5 skeleton |
| 5 | Date formats | Assume `MM/DD` embedded purchase dates and `MM/DD/YY` row dates; the step 5 skeleton confirms before the parser locks anything |
| 6 | Limits | Approved: 50 pages, 64 MB memory, 8 MB decoded content per page, 10 s |
| 7 | Checks | Description = the check-number cell exactly as printed; `checkNumber` = digits only |
| 8 | Naming | The parser may name the real bank (`BankOfAmericaCheckingParser`, id `boa-checking`); the README notes that the project is not affiliated with any bank; fixtures stay fictional |
| 9 | Dependencies | Approved: PDFBox 3.0.x, ArchUnit (test), `exec-maven-plugin` |
| 10 | Fixture bank | The existing fictional Northfield institution, with the same transactions and months as its answer key, so PDF parsing is verified against the existing answer key; every page marked "SYNTHETIC TEST DATA" |

**Real files:** the debug skeleton tool is for you to run locally on real statements. Claude never
runs it, or anything else, against files outside the repo or in `private-data/`.
