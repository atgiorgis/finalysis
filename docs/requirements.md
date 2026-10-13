# Finalysis Requirements

**Version:** 1.28 · **Date:** 2026-10-13 · **Roadmap:** Finalysis Roadmap V4

A private, on-demand personal accountant for regular households: local-first financial
analytics with Spring AI.

## How to use this file

- Each requirement has an ID (e.g. `ING-03`) for referencing in prompts, commits, and PRs.
- **Phase** = roadmap phase that delivers it. **POC** = part of the proof of concept (Phases 2–7).
- **Status:** `Done`, `Planned`, `Deferred` (planned, but moved out of its phase; the note says
  until when), or `Proposed` (idea not yet confirmed).
- When a requirement changes, update it here and bump the version.

---

## Global rules (apply to every phase)

| ID | Rule |
|---|---|
| GR-01 | SQL and Java do all math. The AI only categorizes, extracts, and explains numbers returned by tools. |
| GR-02 | Money is `BigDecimal` / `NUMERIC(12,2)`. Never `double` or `float`. |
| GR-03 | Sign convention: negative = money out, positive = money in. |
| GR-04 | Transfers between the user's own accounts are never counted as spending or income. |
| GR-05 | New statement formats are added as new `StatementParser` implementations; existing parsers are not modified. |
| GR-06 | Schema changes only through new Flyway migrations; applied migrations are never edited. |
| GR-07 | Every feature ships with tests; database tests use Testcontainers. |
| GR-08 | Only synthetic data in the repo. Never commit real statements, `.env` files, credentials, or model files. |

---

## 1. Accounts and data model

| ID | Requirement | Phase | Status |
|---|---|---|---|
| DAT-01 | Register accounts (checking, savings, credit card) with name, institution (each 1–100 characters), type, and last four digits. | 2 · POC | Done |
| DAT-02 | Seeded spending categories with kind (expense, income, transfer), matching the sample-data answer key. | 2 · POC | Done |
| DAT-03 | Store full transaction history locally in Postgres. | 2 · POC | Done |
| DAT-04 | Store each transaction's raw description unchanged, plus a cleaned merchant name. | 2 · POC | Done |
| DAT-05 | Record how each transaction was categorized (rule, AI, or user). | 2 · POC | Done |
| DAT-06 | Store one embedding per unique merchant (pgvector, 1024 dimensions) with the model name. | 2 · POC | Done |
| DAT-07 | Store each transaction's source statement line number and text (source tracing). | 2 · POC | Done |
| DAT-08 | Store each transaction's statement section (deposit, withdrawal, check, fee), optional check number, and optional counterparty (local only); store printed section totals per statement. | 2 · POC | Done |

## 2. Statement ingestion

Ingestion coverage has three layers:

1. **Standard formats:** OFX/QFX through one parser for all institutions, and CSV through a column
   mapping saved per account (ING-01).
2. **Dedicated PDF parsers:** one `StatementParser` per statement layout (ING-08, ING-12). Deferred
   until after the POC (Phase 7); Phase 3b is paused after step 5.
3. **Unknown PDF layouts:** the review queue for now (ING-13); later, local-model extraction
   validated by reconciliation (ING-14).

| ID | Requirement | Phase | Status |
|---|---|---|---|
| ING-01 | Layer 1, standard formats: import OFX/QFX statements with one parser for all institutions, and CSV statements through a user-defined column mapping that is saved per account and reused on later imports.<br>Mapping API (`GET`/`PUT /api/accounts/{id}/csv-mapping`); once a mapping has been used, its fingerprint fields (sign, description columns, printed-date column) change only with explicit confirmation.<br>Preview (`POST /api/accounts/{id}/imports/preview`) reads a file without writing anything. For CSV it returns the detected delimiter, encoding and table start (tolerating a preamble: the longest run of records sharing one column count within the first 100 lines, ties to the later run), the headers, the first 10 rows, the mapping status and a **suggested mapping**: plain Java, no AI, never saved automatically. Headers are matched against synonyms (case, spaces and punctuation ignored); the date format is chosen from a fixed list by reading every row, with the US format and an "ambiguous date format" note when formats disagree; a card account with one amount column is INVERTED when most amounts are positive. Each field is MATCHED, GUESSED or MISSING.<br>With a balance column, a CSV row with a valid date and a balance but no amount is a balance-only row, never a transaction: the oldest one (first row, or last in a newest-first file) is the opening balance; elsewhere it is skipped with a warning. Judged by the cells alone, never by description text.<br>A CSV import without a mapping is 422 `mapping-required`; with a mapping saved for other columns, 422 `mapping-mismatch` with the preview. | 3 · POC | Done |
| ING-02 | Normalize signs and dates across institutions.<br>Merchant-name cleaning moves to Phase 4, with categorization; `txn.merchant` stays null until then.<br>*Done for OFX/QFX and CSV in Phase 3a (amounts in the app's sign convention, through the CSV mapping's `sign`; dates parsed strictly). PDF part deferred until after the POC (Phase 7), with the paused Phase 3b; stays Planned for that part.*<br>*Phase 3b (planned): PDF amounts are taken as printed (money out printed with a minus already matches the sign convention; a sign that contradicts its section is a warning). The printed date is the row's posting date (`MM/DD/YY`), which is also the post date; the transaction date is an embedded `MM/DD` purchase date when present, with the year inferred so it never falls after posting (December/January boundary), otherwise the posting date.* | 3 · POC (PDF part after Phase 7) | Planned |
| ING-03 | Deduplicate re-uploaded statements while keeping genuine duplicate charges: two purchases with the same amount at the same store on the same day are both kept, and a refund is its own row. The fingerprint is raw printed values plus an occurrence number within the statement; the OFX FITID is not hashed (some institutions reissue it) but kept in `txn.external_id` to flag possible duplicates.<br>CSV fingerprints use the sign-normalized amount: a documented exception to "raw printed values", because files with separate debit and credit columns have no single printed signed amount. Protected by the CSV mapping lock: once a mapping has been used, the fields that feed the fingerprint can't change.<br>Limitation: deduplication across formats (CSV and OFX for the same account) is not guaranteed, because descriptions and dates differ between formats. Use one format per account; an import that mixes formats on one account carries a warning. | 3 · POC | Done |
| ING-04 | Block importing the same file twice (file hash, 409). Re-importing a period that already has a statement is allowed: existing rows are skipped by fingerprint (ING-03), and the import summary warns that the period overlaps statement {id}. | 3 · POC | Done |
| ING-05 | Detect transfers between the user's own accounts and link both sides.<br>Invariant: a transaction belongs to at most one transfer link, enforced in the database.<br>*Phase 3c plan (`docs/plans/phase-3c.md`):* `txn.is_transfer` is true exactly when the txn is on a link, kept by a database trigger. Detection is bank-agnostic and never reads descriptions: equal and opposite amounts, different accounts, posted within 5 days either way (configurable), money out from checking or savings, money in to checking, savings or a card, fees and interest excluded. One candidate each way is linked; equal numbers between the same two accounts are paired in date order; anything else waits in the review queue and is not a transfer until the user decides. Runs after every import and statement delete, across all accounts, idempotently. User actions over the API (never chatbot tools): list links (filter by confirmed), confirm, unlink (remembered), link by hand, mark one-sided (UNMATCHED, either side), mark a txn not a transfer. | 3 · POC | Planned |
| ING-06 | Reconcile each statement: opening balance + transactions = closing balance.<br>Checks run on the rows parsed from the file (duplicates included), in Java `BigDecimal`: request balances (both sent), the CSV balance column row by row (newest-first files in reverse; opening and closing derived from it), OFX continuity (the adjacent earlier statement's closing + rows = `LEDGERBAL`, which then sets the opening; never derived from `LEDGERBAL` alone), and printed opening and closing balances (a CSV balance-only opening row must agree with the balance column). `statement.balance_check` records PASSED, FAILED or UNVERIFIED. RECONCILED when a check ran and all passed; NEEDS_REVIEW when any failed, with a warning giving the check, expected, actual and difference; IMPORTED with a "balance unverified" warning when none could run. Other review reasons win over RECONCILED. A statement unverified for want of its predecessor is re-checked when that one is imported. | 3 · POC | Done |
| ING-07 | Statements that fail reconciliation go to a review queue instead of being silently saved.<br>Failing statements are saved as NEEDS_REVIEW with their warnings, and `GET /api/accounts/{id}/statements?status=NEEDS_REVIEW` lists them per account; the review-queue API itself comes in Phase 3c. It lists NEEDS_REVIEW statements and unresolved unparsed uploads (ING-13) across accounts.<br>*Phase 3c plan:* `GET /api/review-queue` lists, across accounts, NEEDS_REVIEW statements not yet reviewed, open unparsed uploads, transfer candidate groups needing a decision, competing candidates (an unconfirmed automatic link with a txn that another txn, arriving later, would also match; the user confirms it or unlinks it so detection re-resolves it; other unconfirmed automatic links are not listed), and possible matches: two one-sided links, one money out and one money in, whose txns are equal and opposite, in different accounts and within the window, and not already rejected. The user can join a possible match into one confirmed link or dismiss it (the pair is remembered as rejected); detection never joins them. Actions: mark a statement reviewed (status and warnings kept; a later warning puts it back), dismiss an unparsed upload, delete a statement (its txns and their transfer links cascade, then detection re-runs). Phase 4 adds suggested one-sided transfers (CAT-08). | 3 · POC | Planned |
| ING-10 | Reconcile each statement section against its printed total, in addition to the overall balance check. Validate section_totals on write: keys must be SourceSection values, amounts scale 2 and within NUMERIC(12,2) range.<br>Section totals are in the app's sign convention; each must equal the sum of its section's parsed rows, and a total for a section without rows must be zero. Generic, ready for the Phase 3b PDF parsers. | 3 · POC | Done |
| ING-11 | Generate synthetic PDF statements that mimic real layouts, for parser tests.<br>A dependency-free generator (`tools/SyntheticPdfGenerator.java`) writes byte-stable PDFs that reproduce the layout's structure for the fictional Northfield checking account, with the same transactions, months and balances as the sample-data answer key, so PDF parsing is verified against the existing answer key. Every page is marked "SYNTHETIC TEST DATA"; no real bank names or logos. Edge cases (wraps, multi-page sections, $0.00 rows, embedded dates across a year boundary, side-by-side checks, a section-total mismatch, an unrecognized layout, a PDF without text) carry their own expected rows. Hostile PDFs are built in memory by tests, never committed. <br>*Deferred until after the POC (Phase 7): the generator and fixtures are built (Phase 3b step 2); the requirement stays open until the PDF parser is verified against them.* | 3b · after Phase 7 | Deferred |
| ING-08 | Import digital PDF statements, starting with the Bank of America checking layout (sections, wrapped descriptions, page continuations, $0.00 rows, embedded purchase dates).<br>Text is extracted locally with Apache PDFBox into positioned lines and words. Rules (Phase 3b plan): wrapped descriptions are the row's description text plus each continuation line as extracted, joined by a newline, never trimmed or cleaned; page headers, footers and "continued" headings are excluded; $0.00 rows are imported; section totals and the summary's beginning and ending balances are captured as printed and reconciled (ING-06, ING-10); check rows (including side-by-side columns) use the check-number cell exactly as printed as the description and its digits as `check_number`. An unknown section heading fails the import until it is mapped. Counterparty extraction is deferred to Phase 4.<br>*Deferred until after the POC (Phase 7). Phase 3b is paused after step 5: a recognized layout is refused with 422 "not implemented".* | 3b · after Phase 7 | Deferred |
| ING-09 | Import scanned or photographed statements via OCR, with a vision-model fallback for low-confidence pages. | 8 | Planned |
| ING-12 | Layer 2, dedicated PDF parsers: each supported statement layout gets its own `StatementParser` class. A new layout is added as a new class, without modifying existing parsers.<br>PDFs (by content sniffing; the `.pdf` extension must agree) are offered only to PDF parsers, after one shared extraction and the security checks. Detection is by layout markers (summary labels, period line), never by institution name; a layout is recognized (DEFINITE) or not, with no fallback. First layout: `BankOfAmericaCheckingParser` (id `boa-checking`).<br>*Deferred until after the POC (Phase 7). Routing and detection are built (Phase 3b steps 3 and 4); parsing is not.* | 3b · after Phase 7 | Deferred |
| ING-13 | Layer 3, unknown PDF layouts: a PDF that no parser recognizes goes to the review queue instead of being imported.<br>It is not a statement: it is recorded in `unparsed_upload` (account, file SHA-256, file name, reason `UNRECOGNIZED_LAYOUT` or `NO_TEXT_LAYER`, created, resolved timestamp and statement), with no rows and no file bytes, and the import answers 202 Accepted with its id and reason. Re-uploading the same file re-runs detection instead of returning 409: still unrecognized returns the existing upload (202); recognized imports normally and marks the upload resolved with the new statement's id.<br>*Recording works today (Phase 3b step 3). Dismissal and the read endpoint come with the Phase 3c review queue; marked Done at the end of 3c.* | 3b, 3c · POC | Planned |
| ING-14 | Layer 3, later: extract unknown PDF layouts with a local model (never Bedrock), validated by reconciliation against the statement's printed totals; mismatches are held for review. | 8 | Planned |

## 3. Categorization

| ID | Requirement | Phase | Status |
|---|---|---|---|
| CAT-01 | Rules engine (merchant pattern → category) runs before any AI. | 4 · POC | Planned |
| CAT-02 | AI categorizes only transactions the rules don't match, using structured output. | 4 · POC | Planned |
| CAT-03 | Retrieval-augmented categorization: include the most similar previously categorized merchants as examples. | 4 · POC | Planned |
| CAT-04 | User corrections are saved as new rules that outrank seeded rules. | 4 · POC | Planned |
| CAT-05 | Measure categorization accuracy against the sample-data answer key. | 4 · POC | Planned |
| CAT-08 | Suggested one-sided transfers: a transaction categorized into a Transfer-kind category that has no transfer link goes to the review queue (ING-07) as a "suggested one-sided transfer". Covers card payments to cards not in the app and transfers to accounts whose statements aren't imported. Categorization only suggests: the user marks it one-sided, links it by hand, or marks it not a transfer (ING-05 actions). Core transfer detection still never reads descriptions. | 4 · POC | Planned |
| CAT-06 | Optional small classifier trained on user corrections (no GPU), compared with prompting. | 12 | Planned |
| CAT-07 | Optional fine-tuned small categorizer (QLoRA), compared with the other approaches. | 12 | Planned |

## 4. Analytics and alerts

| ID | Requirement | Phase | Status |
|---|---|---|---|
| ANA-01 | Monthly totals by category, account, and merchant (by transaction date, transfers excluded). | 5 · POC | Planned |
| ANA-02 | Cash flow, savings rate, and fixed vs. discretionary spending. | 5 · POC | Planned |
| ANA-03 | Month-over-month and year-over-year comparisons. | 5 · POC | Planned |
| ANA-04 | Alerts: spending spikes, new subscriptions, subscription price increases, missing or changed bills, duplicate charges, unusual charges. | 5 · POC | Planned |
| ANA-05 | Monthly plain-English summary generated from computed numbers. | 5 · POC | Planned |
| ANA-06 | Verify totals against hand-computed results and report the match rate. | 5 · POC | Planned |

## 5. Chatbot

| ID | Requirement | Phase | Status |
|---|---|---|---|
| CHT-01 | Answer spending questions using read-only SQL tools. | 6 · POC | Planned |
| CHT-02 | Meaning-based search over merchants and transactions (RAG via pgvector). | 6 · POC | Planned |
| CHT-03 | Route numeric questions to SQL, fuzzy questions to vector search, mixed questions to both. | 6 · POC | Planned |
| CHT-04 | Every dollar figure in an answer must come from a tool result (number-grounding guard). | 6 · POC | Planned |
| CHT-05 | Answers cite the source transactions and statement lines. | 6 · POC | Planned |
| CHT-06 | Conversation memory, with older history summarized to keep context small. | 6 · POC | Planned |
| CHT-07 | Questions about statement documents (RAG over PDF text chunks). | 8 | Planned |

## 6. User interface

| ID | Requirement | Phase | Status |
|---|---|---|---|
| UI-01 | Dashboard: month picker, category chart, alert cards. | 7 · POC | Planned |
| UI-02 | Upload page with account picker and review queue. | 7 · POC | Planned |
| UI-03 | Chat panel. | 7 · POC | Planned |
| UI-04 | Click any total or transaction to see its source statement line. | 7 · POC | Planned |
| UI-05 | Visible banner whenever cloud AI (Bedrock) is active. | 7 · POC | Planned |
| UI-06 | Notice when running the standard model tier ("explanations may be simplified"). | 10 | Planned |

## 7. Investment education and scenario planning

| ID | Requirement | Phase | Status |
|---|---|---|---|
| INV-01 | Calculate investable surplus from actual cash flow. | 14 | Planned |
| INV-02 | Java scenario engine: projections and Monte Carlo simulations (e.g. invest vs. pay down debt). | 14 | Planned |
| INV-03 | Market data via an MCP client. | 14 | Planned |
| INV-04 | Curated knowledge base of public sources (Investor.gov, FINRA, IRS, Bogleheads wiki, fund-company research tagged by publisher), each with a publish date. | 14 | Planned |
| INV-05 | Repo contains only source URLs and the ingestion script, never article text. | 14 | Planned |
| INV-06 | Every answer includes the case for, case against, risks, dated sources, and flags publisher conflicts of interest. | 14 | Planned |
| INV-07 | UI labels: "education and scenario planning, not financial advice" and "past returns don't guarantee future results." | 14 | Planned |

## 8. Personal accountant

| ID | Requirement | Phase | Status |
|---|---|---|---|
| ACC-01 | Monthly close with a "books closed" status per month. | 15 | Planned |
| ACC-02 | Tax tags (deductible, business, rental, medical, charitable) and split transactions. | 15 | Planned |
| ACC-03 | Match receipts (photo or PDF) to transactions. | 15 | Planned |
| ACC-04 | Rental property ledger with Schedule E-style categories and per-property P&L. | 15 | Planned |
| ACC-05 | Depreciation schedule per property; owner-occupied vs. rented split. | 15 | Planned |
| ACC-06 | Net worth statement tracked over time. | 15 | Planned |
| ACC-07 | Encrypted document vault (W-2s, 1099s, property records) with SSNs redacted on import. | 15 | Planned |
| TAX-01 | Tax brackets, deductions, and limits stored as dated, versioned data from IRS publications; tax year shown beside every figure. | 16 | Planned |
| TAX-02 | Year-to-date tax snapshot (deterministic Java, tested). | 16 | Planned |
| TAX-03 | Year-end what-if scenarios (e.g. extra retirement contributions). | 16 | Planned |
| TAX-04 | Quarterly estimated tax calculation and reminders. | 16 | Planned |
| TAX-05 | Itemize vs. standard deduction tracker. | 16 | Planned |
| TAX-06 | Deadline calendar (estimated taxes, property tax, renewals, contribution deadlines). | 16 | Planned |
| TAX-07 | Monthly accountant note summarizing the month. | 16 | Planned |
| TAX-08 | "Ask my accountant" chat grounded in the user's books plus RAG over IRS publications, with sources and dates. | 16 | Planned |
| TAX-09 | Annual tax package export and lender-ready income/expense summaries. | 16 | Planned |
| TAX-10 | UI label: "bookkeeping and tax-prep assistant, not a CPA." Tax documents and SSNs are never sent to Bedrock. | 16 | Planned |

## 9. Integrations

| ID | Requirement | Phase | Status |
|---|---|---|---|
| INT-01 | Finalysis MCP server exposing read-only tools (spending, transactions, alerts, comparisons). | 13 | Planned |
| INT-02 | Document which tool results leave the app and to whom. | 13 | Planned |
| INT-03 | Optional "frontier assist" mode: send only anonymized summaries to a frontier model for the hardest tax and investment questions, opt-in with a visible banner. | — | Proposed |

## 10. AI models and runtime

| ID | Requirement | Phase | Status |
|---|---|---|---|
| AI-01 | Three separately configurable model roles: main model, embedding, vision. | 4, 8 | Planned |
| AI-02 | Standard tier main model: Qwen3 14B. Deep tier: Qwen3 30B MoE (or a dense 27B on 32GB+). Final choice decided by evals. | 10 | Planned |
| AI-03 | `local` profile (Ollama) is the default; `aws` profile (Bedrock) must be enabled explicitly. | 4, 10 | Planned |
| AI-04 | Embedding and main models stay loaded; vision model loads only when needed. | 10 | Planned |
| AI-05 | Context length (`num_ctx`) set explicitly per model role; 8-bit KV cache supported. | 6, 10 | Planned |
| AI-06 | Log token count for every model request. | 6 | Planned |
| AI-07 | Benchmark at least one US-origin model alternative (e.g. Gemma, gpt-oss, Granite). | 9, 10 | Planned |

## 11. Privacy and security

| ID | Requirement | Phase | Status |
|---|---|---|---|
| SEC-01 | Cloud mode sends only cleaned merchant text (location and store numbers removed) and amounts. Never account numbers, names, balances, documents, or SSNs. | 4 · POC | Planned |
| SEC-08 | Before any cloud call, strip bank identifiers from descriptions: INDN:, ID:, CO ID:, Conf#, account last-fours, and person-to-person counterparty names. Covered by tests. | 4 · POC | Planned |
| SEC-09 | PDF statement text is never sent to Bedrock; any AI fallback for extraction runs on the local model only.<br>Phase 3b uses no AI at all; an architecture test keeps ingestion code from depending on Spring AI, chat or categorization.<br>*Done (v1.26): enforced by the ArchUnit rule `NoAiInIngestionTest` (Phase 3b step 8, done early), which keeps ingestion from depending on Spring AI, the AWS SDK, chat or categorization (other than the Category entity and CategorySource that `Txn` maps); the app has no Spring AI dependency yet. The rule applies to the PDF parsing deferred until after the POC (Phase 7) as soon as it is written; ING-14's later local-model fallback (Phase 8) needs an explicit, reviewed exception to it.* | 3b · POC | Done |
| SEC-10 | Statement uploads are hardened: file size and row limits; allowed types checked by content sniffing, never by the client's content type alone; OFX parsed without DTD or external-entity processing (XXE and entity-expansion safe); no file contents or descriptions in logs or error responses (only line numbers, column names, counts, sizes and hash prefixes). Covered by tests.<br>*Done for OFX/QFX and CSV in Phase 3a.* Extended to PDFs in Phase 3b: at most 50 pages, 64 MB of heap for decoded streams, 8 MB of decoded content per page, glyph and form-nesting limits and a 10 s extraction deadline (422 `pdf-too-complex`); a PDF that needs a password to open is rejected with a clear error (422 `pdf-encrypted`), while one encrypted with an empty user password (restrictions only) is read; JavaScript, embedded files, forms, annotations and links are never read; loading is memory-only, with no temp files and no system font cache; PDF library logging is off.<br>*PDF part deferred until after the POC (Phase 7). The extraction limits, encrypted-PDF handling and memory-only loading are built and tested (Phase 3b steps 1 and 3) and apply to every PDF upload today; so are the cross-cutting PDF tests (Phase 3b step 8, done early): no PDF text in logs or responses on every PDF path reachable today, no upload, scratch file or font cache on disk, PDFBox logging off at every level.*<br>*Done (v1.26): every upload path reachable today is covered: OFX/QFX, CSV, and every PDF path (recognized layout refused while 3b is paused, unrecognized and no-text-layer uploads, password required, too many pages, unreadable and truncated files, preview included). A successful PDF import isn't reachable while ING-08 is deferred; its leak test is part of finishing ING-08 when Phase 3b resumes.* | 3a, 3b · POC | Done |
| SEC-11 | Text cells beginning with `=`, `+`, `-`, `@`, tab or carriage return are stored unchanged (DAT-04) and escaped on any CSV export, so spreadsheet apps don't run them as formulas. *Storing them unchanged is done and tested (parser and import); escaping applies when the first CSV export is built, so this stays Planned until then.* | 3a · POC | Planned |
| SEC-02 | Transaction text is treated as untrusted data and delimited in prompts, never followed as instructions. | 4, 6 · POC | Planned |
| SEC-03 | AI tools are read-only. | 6 · POC | Planned |
| SEC-04 | Encrypt stored statement files and document images (AES-GCM, key derived from a passphrase with Argon2id). | 8 | Planned |
| SEC-05 | Encrypt sensitive database fields that aren't needed for SQL math or search. | 8 | Planned |
| SEC-06 | Passphrase unlock (or OS keychain), recovery phrase, and encrypted backups. | 8 | Planned |
| SEC-07 | README recommends full-disk encryption (FileVault, BitLocker). | 8 | Planned |

## 12. Quality, evaluation, and portfolio

| ID | Requirement | Phase | Status |
|---|---|---|---|
| QA-01 | Synthetic sample data for four fictional accounts, with an answer key and statement manifest. | 1 | Done |
| QA-02 | CI runs backend and frontend tests on every push.<br>Deferred: GitHub Actions not yet running; revisit at end of POC. | 1 | Planned |
| QA-03 | Eval suites in CI for categorization, extraction, retrieval, and chat answers. | 9 | Planned |
| QA-04 | Benchmarks: 14B vs. 30B vs. Bedrock vs. a US-origin model, on the same question set. | 9, 10 | Planned |
| QA-05 | README: hardware tiers, data boundary, eval results, demo GIF, architecture diagram. | 11 | Planned |
| QA-06 | Setup script that detects available memory and pulls suitable models; demo mode with sample data. | 11 | Planned |
| QA-07 | Architecture decision records for key choices (SQL does math, rules before AI, local by default, hybrid SQL + RAG). | 11 | Planned |

---

## Change log

| Version | Date | Change |
|---|---|---|
| 1.0 | 2026-10-09 | Initial requirements, aligned with Finalysis Roadmap V4. |
| 1.1 | 2026-10-09 | Added DAT-08, ING-10, ING-11, SEC-08, SEC-09 after analyzing a Bank of America PDF statement; moved digital PDF import (ING-08) into the POC as Phase 3b. |
| 1.2 | 2026-10-09 | DAT-02: seeded the answer-key categories plus general-purpose ones. Merged "Internal Transfer" and "Credit Card Payment" into the answer key's "Transfer", which already labels both savings transfers and card payments; a separate name would duplicate it and break CAT-05 accuracy against the answer key. Card payments can be identified from `transfer_link` plus the counterpart account type. |
| 1.3 | 2026-10-09 | ING-05: added the invariant that a transaction belongs to at most one transfer link, on either side. V1's unique constraints only blocked repeats on the same side; V3 adds a trigger that also blocks a txn being the out side of one link and the in side of another. |
| 1.4 | 2026-10-09 | DAT-01: account name and institution limited to 1–100 characters (V4 CHECK constraints, mirrored in API validation); account REST API (create, list, get, rename). |
| 1.5 | 2026-10-09 | DAT-02: read-only category list API (`GET /api/categories`). Sorted by name ignoring case, in Java rather than SQL, so the order does not depend on the database collation. |
| 1.6 | 2026-10-09 | DAT-06: merchant embedding repository over JDBC (pgvector-java for vector binding): upsert, lookup, cosine nearest-neighbour search matching the HNSW index's `vector_cosine_ops`, and stale-model lookup for re-embedding. Keys are the exact cleaned `txn.merchant` value, with no normalization in the repository. |
| 1.7 | 2026-10-09 | DAT-01 to DAT-08 marked Done (Phase 2 complete). README documents the data model (ER diagram, design decisions, migrations), the API and its error format, and local development (tests, Postman, database client). Added `scripts/reset-dev-db.sh`, which recreates the dev database only after typed confirmation and refuses any non-local target. |
| 1.8 | 2026-10-09 | Phase 2 verification. ING-10: added the note to validate `statement.section_totals` on write (deferred to Phase 3; the JSONB column itself enforces neither the keys nor NUMERIC(12,2)). `scripts/reset-dev-db.sh` now removes only the `db` container and its own volume instead of `docker compose down --volumes`, so other services' volumes survive a reset. |
| 1.9 | 2026-10-09 | Ingestion described in three layers: ING-01 now covers OFX/QFX with one parser for all institutions and CSV with a column mapping saved per institution; added ING-12 (one `StatementParser` per PDF layout), ING-13 (unknown PDF layouts go to the review queue) and ING-14 (later: local-model extraction, never Bedrock, validated by reconciliation). QA-02: noted that CI is deferred because GitHub Actions is not yet running; revisit at end of POC. |
| 1.10 | 2026-10-12 | Phase 3 scope: CLAUDE.md's Phase 3 list now includes the PDF parser requirements ING-08, ING-12 and ING-13. QA-02 set to Planned while GitHub Actions is not running (deferred note kept). ING-14 assigned to Phase 8, with the local vision and extraction work. |
| 1.11 | 2026-10-12 | Phase 3a plan decisions. ING-01: CSV mappings are saved per account, not per institution. ING-02: merchant-name cleaning moves to Phase 4; `txn.merchant` stays null in Phase 3. ING-03: CSV fingerprints use the sign-normalized amount (documented exception, protected by the mapping lock); cross-format deduplication is not guaranteed, so use one format per account, with a warning on mixed formats. ING-04: an identical file is still blocked (409), but re-importing a period is allowed with an overlap warning; the `uq_statement_account_period` constraint is dropped by a new migration in Phase 3a step 4. Added SEC-10 (upload hardening; Phase 3a steps 2, 4 and 9) and SEC-11 (formula-leading cells stored unchanged and escaped on any CSV export; stored-unchanged test in step 6, escaping applies when the first CSV export is built). ING-10's section-total validation is implemented in step 1. |
| 1.12 | 2026-10-12 | ING-04 Done: `POST /api/accounts/{id}/imports` imports OFX/QFX end to end. ING-03 deduplication works for OFX and stays Planned until the CSV mapping lock (step 5). Fingerprints skip rows already imported for the account (in one transaction with the account row locked, so concurrent imports can't insert a row twice); an identical file is 409 `already-imported`; a re-imported or overlapping period inserts only new rows and warns about the overlap. V5 drops `uq_statement_account_period` and adds `statement.parser`, `txn.external_id` (FITID; a known FITID under a different fingerprint is inserted and flagged NEEDS_REVIEW) and `txn.printed_amount`. SEC-10 upload checks in place (10 MB, in-memory multipart, extension allow-list, content sniffing, typed problem responses without file contents); its cross-cutting log tests follow in step 9. ING-01 stays Planned until CSV import (steps 5 to 7). |
| 1.13 | 2026-10-12 | Import review rules tightened before step 5. Every import warning now sets NEEDS_REVIEW, including period overlaps and mixed formats, and warnings are stored on the statement (`statement.import_warnings`, added to V5 before it was applied anywhere but test databases) and returned by `GET /api/statements/{id}`. Rows dated outside the statement period (e.g. after a period override) are imported with a warning, never dropped. ING-03: the fingerprint includes the OFX FITID when present, so look-alike rows with different FITIDs are always distinct; rows without one keep their exact previous fingerprint (occurrence numbers). |
| 1.14 | 2026-10-12 | Import fixes after review. ING-03: the FITID is removed from the fingerprint again (raw printed values plus occurrence number, as approved); it stays in `external_id` for the possible-duplicate safety net. An overlapping import that inserts no rows is IMPORTED with an informational "no new rows" warning; one that inserts rows is NEEDS_REVIEW. API money is a JSON string with exactly two decimals in responses and must be one in requests (a JSON number is a 400); the multipart balance fields accept only plain decimal text. V5 is frozen: it is applied to the dev database, so further schema changes go in V6 or later. |
| 1.15 | 2026-10-12 | ING-01, ING-03: CSV mapping API (Phase 3a step 5). V6 adds `csv_mapping`, one per account. `PUT /api/accounts/{id}/csv-mapping` validates the mapping against the file's headers (or a column count without a header row), computes the header signature, and returns 201 or 200; `GET` returns it or 404. Once an import has used a mapping (`used_at`), a change to `sign`, `description_columns` or the printed-date column is 409 `mapping-locked` unless confirmed with `?confirmFingerprintChange=true`, which saves it with a warning about new fingerprints. Statuses unchanged: ING-01 and ING-03 stay Planned until CSV import (steps 6 and 7). |
| 1.16 | 2026-10-12 | ING-01, ING-02, SEC-10, SEC-11: CSV parser (Phase 3a step 6), not yet wired to the import endpoint. Reads a file with the account's saved mapping: encoding (UTF-8 BOM dropped), delimiter, skipped lines, header signature check, columns by name or index. Dates parse strictly; amounts accept a minus, `$`, thousands separators and parentheses, never more than two decimals; debit/credit files need exactly one non-zero side; `INVERTED` negates. Descriptions are the mapped cells as extracted, joined by one space; sections by sign (CHECK with a check number). Any unreadable row fails the file with its line and column position only. SEC-10 limits: 50,000 data rows and 64 KB per record. SEC-11: formula-leading descriptions are stored unchanged (tested). Known limitation: trailing footer or total rows fail the import. Statuses unchanged until CSV import (step 7). |
| 1.17 | 2026-10-12 | ING-01 and ING-03 Done: CSV import end to end (Phase 3a step 7), alongside OFX/QFX; ING-04 (file-hash block) was already Done. Added the import preview with suggested CSV mappings to ING-01 (synonym header matching, date format from a fixed list with an ambiguity note, card sign by majority; per-field confidence; nothing saved automatically). CSV detection now tolerates a preamble: the table is the first run of 5 consistent records (or all remaining, if fewer) within the first 100 lines, and its line is the suggested `skipRows`. New problem types: 422 `mapping-required`, 422 `mapping-mismatch` (with the preview), 409 `mapping-changed`. The parser reads an immutable snapshot of the mapping taken before parsing; the import checks under the account lock that the mapping's `updated_at` is unchanged, and the first import sets `used_at`. A CSV file without rows imports as an empty statement when the request gives both period ends. Reconciliation of balances stays in step 8 (ING-06). |
| 1.18 | 2026-10-12 | ING-06 and ING-10 Done: statement reconciliation (Phase 3a step 8). Checks on the parsed rows: request balances, CSV balance column, OFX continuity with the adjacent earlier statement, printed balances and section totals; late arrivals re-run the next statement's continuity check. V7 adds `statement.balance_check` (PASSED, FAILED, UNVERIFIED) and the parsed row count and amount total, which the late-arrival check needs because duplicates are not stored. A request balance that contradicts the file's is now a failed check (the file's value is kept). New `GET /api/accounts/{id}/statements` (newest period first, `?status=` filter); statement detail and import summary add `balanceCheck` and balances. ING-07: NEEDS_REVIEW statements are listable per account; the review-queue API stays in Phase 3c. SEC-10: the 422 `mapping-mismatch` response no longer includes data rows, only headers, mapping status and the suggested mapping. |
| 1.19 | 2026-10-12 | SEC-10 Done: cross-cutting upload security tests (Phase 3a step 9). Every endpoint runs with markers in descriptions, file names, header names, cells and account numbers, with the app's packages at DEBUG: none reaches the log or a problem response (a `mapping-mismatch` preview still shows header names, which SEC-10 allows). Sniffing, XXE (external, parameter and external-DTD entities, billion laughs), file names and the size, row and record limits are tested through the HTTP endpoints, and a real-server test shows uploads never touch disk. Fixes: saved web pages are refused (415); long file names keep their extension when cut to 255 characters, and Unicode format characters (bidirectional overrides) are removed; Postgres error details, which quote the failing row, are off on the connection; Actuator exposes `health` only (was `health,info`); devtools no longer adds error details and exception logging under `spring-boot:run`. SEC-11: formula-leading descriptions (including tab and carriage return) are also tested as stored unchanged through the import; it stays Planned until a CSV export escapes them. |
| 1.20 | 2026-10-12 | Phase 3a complete (OFX/QFX and CSV ingestion). Final statuses: ING-01, ING-03, ING-04, ING-06, ING-10 and SEC-10 Done. Still Planned: ING-02 (signs and dates are normalized for OFX/QFX and CSV; the Phase 3b PDF parsers must do the same), ING-07 (NEEDS_REVIEW statements are saved and listable; the review-queue API is Phase 3c) and SEC-11 (formula cells are stored unchanged; escaping comes with the first CSV export). README documents the import pipeline, every endpoint, an error catalogue with what the user should do, an import walkthrough and the known limitations; the Postman collection covers every endpoint and runs against a freshly reset dev database. No behaviour changes. |
| 1.21 | 2026-10-12 | Post-3a fixes from a manual test with a real bank CSV (ING-01, ING-06). CSV detection: the table is the run with the most records among runs of consistent column count (two or more columns) within the first 100 lines, ties to the later run; "the first run of 5" picked a five-record summary block above the table. `skipRows` counts blank lines. Balance-only rows (valid date and balance, empty amount or empty debit and credit; balance column required): the oldest row gives the opening balance, which reconciliation checks against the balance column; any other is skipped with a warning naming its line. Without a balance column an empty amount stays a `parse-error`. Suggested mappings also match `Running Bal.` and `Bal` as the balance column. New edge case `csv-summary-preamble-balance-row.csv`. |
| 1.22 | 2026-10-12 | Phase 3b plan decisions (`docs/plans/phase-3b.md`). ING-13: an unrecognized PDF is not a statement; it is recorded in a new `unparsed_upload` table (V8), answered with 202 Accepted, and a re-upload re-runs detection (still unrecognized: the existing upload; recognized: imported, upload resolved with the statement id). ING-07: the 3c review queue lists NEEDS_REVIEW statements and unresolved unparsed uploads. ING-12: PDFs are routed only to PDF parsers, detected by layout markers, never by institution name; first parser `BankOfAmericaCheckingParser` (`boa-checking`). ING-08: layout rules (wrapped descriptions joined by newlines as extracted, $0.00 rows imported, printed section totals and balances reconciled, checks described by the check-number cell as printed, unknown headings fail safely, counterparty deferred to Phase 4). ING-02: PDF sign and date rules, including embedded purchase dates across a year boundary. ING-11: synthetic PDFs for the fictional Northfield checking account built from the existing answer key, marked "SYNTHETIC TEST DATA". SEC-09: no AI in 3b, enforced by an architecture test. SEC-10 extended to PDFs (limits, encrypted PDFs, inert content, no temp files or font cache, library logging off), so it returns to Planned until the PDF part is done. |
| 1.23 | 2026-10-13 | Phase 3b paused after step 5 to finish the POC first; Phase 3c (transfer detection and review queue) starts. ING-08, ING-11 and ING-12 are Deferred until after the POC (Phase 7), and so is the PDF part of SEC-10 (it stays Planned for that part). Added the `Deferred` status. While paused, a PDF recognized as the BoA checking layout is refused with 422 "not implemented" and other PDFs become unparsed uploads; ING-13 is unchanged. `docs/plans/phase-3b.md` records the state, the remaining steps and a backlog note for a generic PDF parser (3d) and an AI-generated layout template (Phase 8). |
| 1.24 | 2026-10-13 | Phase 3c plan approved (`docs/plans/phase-3c.md`). ING-05 and ING-07: the plan's design recorded (is_transfer = on a link, kept by a trigger; bank-agnostic detection with a 5-day window either way; ambiguous groups wait for the user; transfer actions over the API; one review queue across accounts with review, dismiss and delete actions). ING-13: dismissal and the read endpoint come with 3c, which marks it Done. Added CAT-08 (Phase 4 hook): a Transfer-kind categorization without a link becomes a suggested one-sided transfer in the review queue; core detection never reads descriptions. ING-02 and SEC-09 treated like SEC-10: their PDF parts are deferred until after the POC (ING-02 is done for OFX/QFX and CSV; SEC-09 is satisfied today, its ArchUnit test still to be written); both stay Planned. |
| 1.25 | 2026-10-13 | Phase 3b step 8 done early, as a standalone task before Phase 3c, because PDF uploads are accepted today. SEC-10: the cross-cutting PDF tests exist (leak test on every PDF path reachable today, no upload, scratch file or font cache on a real server, PDFBox loggers off at every level); the successful-import path is added when 3b resumes. SEC-09: the ArchUnit test `NoAiInIngestionTest` keeps ingestion from depending on Spring AI, the AWS SDK, chat or categorization (other than the Category entity and CategorySource that `Txn` maps). Statuses unchanged. |
| 1.26 | 2026-10-13 | SEC-09 and SEC-10 Done. SEC-09 is enforced by the ArchUnit no-AI rule (`NoAiInIngestionTest`), which also covers the deferred PDF parsers once they are written. SEC-10 covers every upload path reachable today, PDFs included; the leak test for a successful PDF import is part of finishing ING-08 when Phase 3b resumes. |
| 1.27 | 2026-10-13 | ING-07: the Phase 3c review queue also lists possible matches between two one-sided (UNMATCHED) links whose txns are equal and opposite, in different accounts and within the detection window; the user can join them into one link, and detection still never joins them automatically (plan step 6). |
| 1.28 | 2026-10-13 | ING-07: the Phase 3c review queue also lists competing candidates, an unconfirmed automatic link with a txn that a later-arriving txn would also match (detection still never changes a link; the user confirms or unlinks). Possible matches: join (one confirmed user link replacing the two one-sided links) and dismiss (the pair is recorded as rejected) confirmed; a rejected pair is never listed. |
