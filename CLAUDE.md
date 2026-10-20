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
- `com.finalysis.ai`: shared AI wiring (mode, model roles, token log), not a feature, since `categorize` uses it now and `chat` will in Phase 6.
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
- **Data minimization in cloud mode:** send only merchant text and amount to Bedrock.
  Never send account numbers, names, or full statements. Redact before every call.
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
Phase 5: analytics, plan approved (docs/plans/phase-5.md, decisions in §13); steps 1 to 4 (fixture and buckets; monthly totals, coverage, data quality; merchants, trends, drill-down, closed accounts; ANA-06 on the sample data, report docs/eval/phase-5-totals.md) done, step 5 (alerts: framework, spike, large, duplicate) next. Alerts use V20 (V19 is `account.closed_on`). No AI anywhere in Phase 5. Phase 4 complete: docs/plans/phase-4.md (accuracy report docs/eval/phase-4-accuracy.md; the holdout has been evaluated once and is never run again; open items in its Backlog). Phase 3c complete: docs/plans/phase-3c.md. Phase 3b (PDF) paused after step 5.

## Notes
- CI (GitHub Actions) is not active yet; run ./mvnw clean verify and npm test locally before every commit.
