# Finalysis Requirements

**Version:** 1.15 · **Date:** 2026-10-12 · **Roadmap:** Finalysis Roadmap V4

A private, on-demand personal accountant for regular households: local-first financial
analytics with Spring AI.

## How to use this file

- Each requirement has an ID (e.g. `ING-03`) for referencing in prompts, commits, and PRs.
- **Phase** = roadmap phase that delivers it. **POC** = part of the proof of concept (Phases 2–7).
- **Status:** `Done`, `Planned`, or `Proposed` (idea not yet confirmed).
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
2. **Dedicated PDF parsers:** one `StatementParser` per statement layout (ING-08, ING-12).
3. **Unknown PDF layouts:** the review queue for now (ING-13); later, local-model extraction
   validated by reconciliation (ING-14).

| ID | Requirement | Phase | Status |
|---|---|---|---|
| ING-01 | Layer 1, standard formats: import OFX/QFX statements with one parser for all institutions, and CSV statements through a user-defined column mapping that is saved per account and reused on later imports.<br>Mapping API in place (`GET`/`PUT /api/accounts/{id}/csv-mapping`); once a mapping has been used, its fingerprint fields (sign, description columns, printed-date column) change only with explicit confirmation. | 3 · POC | Planned |
| ING-02 | Normalize signs and dates across institutions.<br>Merchant-name cleaning moves to Phase 4, with categorization; `txn.merchant` stays null until then. | 3 · POC | Planned |
| ING-03 | Deduplicate re-uploaded statements while keeping genuine duplicate charges: two purchases with the same amount at the same store on the same day are both kept, and a refund is its own row. The fingerprint is raw printed values plus an occurrence number within the statement; the OFX FITID is not hashed (some institutions reissue it) but kept in `txn.external_id` to flag possible duplicates.<br>CSV fingerprints use the sign-normalized amount: a documented exception to "raw printed values", because files with separate debit and credit columns have no single printed signed amount. Protected by the CSV mapping lock: once a mapping has been used, the fields that feed the fingerprint can't change.<br>Limitation: deduplication across formats (CSV and OFX for the same account) is not guaranteed, because descriptions and dates differ between formats. Use one format per account; an import that mixes formats on one account carries a warning. | 3 · POC | Planned |
| ING-04 | Block importing the same file twice (file hash, 409). Re-importing a period that already has a statement is allowed: existing rows are skipped by fingerprint (ING-03), and the import summary warns that the period overlaps statement {id}. | 3 · POC | Done |
| ING-05 | Detect transfers between the user's own accounts and link both sides.<br>Invariant: a transaction belongs to at most one transfer link, enforced in the database. | 3 · POC | Planned |
| ING-06 | Reconcile each statement: opening balance + transactions = closing balance. | 3 · POC | Planned |
| ING-07 | Statements that fail reconciliation go to a review queue instead of being silently saved. | 3 · POC | Planned |
| ING-10 | Reconcile each statement section against its printed total, in addition to the overall balance check. Validate section_totals on write: keys must be SourceSection values, amounts scale 2 and within NUMERIC(12,2) range. | 3 · POC | Planned |
| ING-11 | Generate synthetic PDF statements that mimic real layouts, for parser tests. | 3b · POC | Planned |
| ING-08 | Import digital PDF statements, starting with the Bank of America checking layout (sections, wrapped descriptions, page continuations, $0.00 rows, embedded purchase dates). | 3b · POC | Planned |
| ING-09 | Import scanned or photographed statements via OCR, with a vision-model fallback for low-confidence pages. | 8 | Planned |
| ING-12 | Layer 2, dedicated PDF parsers: each supported statement layout gets its own `StatementParser` class. A new layout is added as a new class, without modifying existing parsers. | 3b · POC | Planned |
| ING-13 | Layer 3, unknown PDF layouts: a PDF that no parser recognizes goes to the review queue instead of being imported. | 3b · POC | Planned |
| ING-14 | Layer 3, later: extract unknown PDF layouts with a local model (never Bedrock), validated by reconciliation against the statement's printed totals; mismatches are held for review. | 8 | Planned |

## 3. Categorization

| ID | Requirement | Phase | Status |
|---|---|---|---|
| CAT-01 | Rules engine (merchant pattern → category) runs before any AI. | 4 · POC | Planned |
| CAT-02 | AI categorizes only transactions the rules don't match, using structured output. | 4 · POC | Planned |
| CAT-03 | Retrieval-augmented categorization: include the most similar previously categorized merchants as examples. | 4 · POC | Planned |
| CAT-04 | User corrections are saved as new rules that outrank seeded rules. | 4 · POC | Planned |
| CAT-05 | Measure categorization accuracy against the sample-data answer key. | 4 · POC | Planned |
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
| SEC-09 | PDF statement text is never sent to Bedrock; any AI fallback for extraction runs on the local model only. | 3b · POC | Planned |
| SEC-10 | Statement uploads are hardened: file size and row limits; allowed types checked by content sniffing, never by the client's content type alone; OFX parsed without DTD or external-entity processing (XXE and entity-expansion safe); no file contents or descriptions in logs or error responses (only line numbers, column names, counts, sizes and hash prefixes). Covered by tests. | 3a · POC | Planned |
| SEC-11 | Text cells beginning with `=`, `+`, `-`, `@`, tab or carriage return are stored unchanged (DAT-04) and escaped on any CSV export, so spreadsheet apps don't run them as formulas. | 3a · POC | Planned |
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
