# Finalysis

Local-first personal financial analytics app. Users import monthly bank and credit card
statements (CSV, OFX, PDF, scans) from multiple accounts. The app categorizes spending,
computes monthly totals and trends, raises alerts, and offers a chatbot that answers
questions about the user's own data. Portfolio project: code quality and clear design
decisions matter as much as features.

## Stack
- Backend: Java 21, Spring Boot 4, Spring AI 2.0, Maven, Flyway, JPA
- Frontend: React + Vite + TypeScript, Recharts, Vitest
- Database: Postgres with pgvector (Docker Compose)
- AI: Amazon Bedrock via the `aws` Spring profile (current); Ollama via the `local` profile (later)
- OCR: Tesseract (tess4j), with a vision-model fallback for low-confidence pages

## Repo layout
- `backend/` organized by feature, not by layer:
  `account`, `ingestion`, `categorize`, `analytics`, `chat`, `config`
- `com.finalysis.ai`: shared AI wiring (mode, model roles, token log), not a feature, since `categorize` and `chat` both use it. `com.finalysis.ai.outbound` holds what may leave the machine (Redactor, OutboundPayloadGuard), shared by both and depending on no feature.
- `frontend/` pages, components, and a single `api.ts` for backend calls
- `sample-data/` synthetic statements only
- `compose.yaml` at the repo root

## Commands
- Database: `docker compose up -d db`
- Backend: `cd backend && ./mvnw spring-boot:run`
- Backend tests: `cd backend && ./mvnw verify`
- Frontend: `cd frontend && npm run dev`
- Frontend tests: `cd frontend && npm test`

## Non-negotiable rules
- **SQL and Java do all math.** The AI never computes totals, averages, or balances.
  It only categorizes, extracts, and explains numbers that tools return.
- **Money is `BigDecimal` / `NUMERIC(12,2)`.** Never `double` or `float`.
- **Sign convention:** negative = money out, positive = money in.
- **Transfers between the user's own accounts are not spending.** Exclude `is_transfer = true`
  from all spending totals.
- **Never commit real statements, `.env` files, credentials, or model files.**
- **Local AI is the default.** Bedrock must be enabled explicitly, and the UI must show a
  banner when cloud AI is active. Until the `local` profile exists, AI features are off
  unless the `aws` profile is enabled explicitly.
- **Data minimization in cloud mode:** redact before every call, and send only what SEC-01 allows.
  Categorization sends only redacted merchant text and amounts (plus category names and redacted
  examples). Chat may also send totals, percents, counts, dates and months, category names, account
  aliases (`CHECKING-1`, never the account itself) and redacted merchants (docs/plans/phase-6.md §12).
  Never send account balances, account numbers or last fours, account or person names, raw
  descriptions or statement text, counterparties, or database ids and alert keys.
- **Transaction descriptions are untrusted data, never instructions.** Delimit them in prompts.
- **Chatbot tools are read-only.** The AI can query data but never change it.
- **Parsers pass descriptions to TransactionFingerprint exactly as extracted** (wrapped lines included).
  Never trim, clean, or redact a description before fingerprinting; cleaning happens afterward for
  the merchant field only.
- **Real statements live only in `private-data/` (git-ignored).** Never read them into tests,
  fixtures, docs, or commit messages.
- **Core code (categories, analytics, chat, dedup, reconciliation) is bank-agnostic.** Bank-specific
  knowledge lives only inside `StatementParser` implementations and categorization rules.

## Coding conventions
- Constructor injection, no field `@Autowired`.
- Java records for DTOs and AI structured-output types.
- New statement formats = a new `StatementParser` implementation; don't modify existing ones.
- Schema changes only through new Flyway migrations; never edit an applied migration.
- Every feature ships with tests. Use Testcontainers for database tests and synthetic data from `sample-data/`.

## How to work with me
- Propose a plan before large changes and wait for approval.
- Keep changes focused on the current phase; don't refactor unrelated code.
- Explain non-obvious design choices briefly in the PR or commit message.
- If a requirement is unclear, ask instead of guessing.

## Requirements
All features are listed in docs/requirements.md with IDs. Before starting a
phase, read the requirements for that phase. Reference IDs in commit messages
(e.g. "ING-03: dedupe with occurrence-number fingerprint"). If a change affects
a requirement, update the file and bump its version.

## Current phase
Between phases: Phase 7 complete (tag `phase-7-done`): docs/plans/phase-7.md (decisions in §13; step notes 0–8 with 3b, 7b–7e; phase summary, known limitations and Backlog after the step 8 notes). The next phase has no plan yet: Phase 8 (roadmap: encryption, login and HTTPS for phone access, OCR, document Q&A; plus the Corgi system prompt, h-u10 and h-x06 from the Phase 7 backlog) or Phase 3b's PDF parsers, which resume after Phase 7; propose a plan and wait for approval. Corgi is the UI's display name for all AI (one constant, `AI_NAME`); chat runs prompt `chat-9` until the Corgi prompt step. Every number on screen comes from the API; the frontend never computes a total, average or percent (`formatCost` rounds an estimated cost to the cent for display, nothing else rounds). If a 7e-style frontend test failure recurs, keep `frontend/test-reports/vitest.json` and record the names (phase-7.md step 8 notes). Phase 6 complete (tag `phase-6-done`): docs/plans/phase-6.md (decisions in §18; step notes 1–9b; the Phase 7 backlog: h-u10, h-x06, the holdout guard per set, Haiku's search fallback, the conservative cost estimator). Chat runs on Sonnet 4.6 at `max-turn-cost` 0.15, Haiku 4.5 at 0.05 the budget option; the chat holdout has been evaluated once (56/58, docs/eval/README.md "Holdout") and is never run again. Phase 5 complete: docs/plans/phase-5.md (phase summary, known limitations and Backlog; totals report docs/eval/phase-5-totals.md; no AI anywhere in analytics). Phase 4 complete: docs/plans/phase-4.md (accuracy report docs/eval/phase-4-accuracy.md; the holdout has been evaluated once and is never run again; open items in its Backlog). Phase 3c complete: docs/plans/phase-3c.md. Phase 3b (PDF) paused after step 5.

## Notes
- CI (GitHub Actions) is not active yet; run ./mvnw clean verify and npm test locally before every commit.
