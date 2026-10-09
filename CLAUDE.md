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

## Coding conventions
- Constructor injection, no field `@Autowired`.
- Java records for DTOs and AI structured-output types.
- New statement formats = a new `StatementParser` implementation; don't modify existing ones.
- Schema changes only through new Flyway migrations; never edit an applied migration.
- Every feature ships with tests. Use Testcontainers for database tests and synthetic data from `sample-data/`.
- Parsers pass descriptions to TransactionFingerprint exactly as extracted (wrapped lines included). Never trim, clean, or redact a description before fingerprinting; cleaning happens afterward for the merchant field only.

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
Phase 2: data model (requirements DAT-01 to DAT-08).
