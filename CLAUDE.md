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
  `account`, `ingestion`, `categorize`, `analytics`, `chat`, `config`, plus `vault` (the encrypted volume, its native
  Postgres, unlock and lock, the 423 gate) and `access` (phone login, the HTTPS LAN listener, its local CA)
- `scripts/vault/` macOS scripts for the vault image and its Postgres (hdiutil, Postgres.app); the backend calls them
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
Phase 9: Corgi on three paths (Cloud = Bedrock, Self-hosted = an EC2 GPU instance through Tailscale, Local = Ollama), chosen in Settings without restart. Plan approved: docs/plans/phase-9.md (decisions in §16; steps in §15: 9a, 9b-1, 9b-2, 9c, 9d, 9e). "Path" means where Corgi runs (Cloud / Self-hosted / Local); "tier" means a model's size. I make no live AI call and no EC2 or Tailscale action: the owner runs every eval, create/destroy script, Start and Stop. Never read, print or copy a Tailscale auth key or any AWS key. Phase 8 complete (tag `phase-8-done`): encryption at rest (an encrypted volume), unlock and auto-lock, login and HTTPS for phone access (tested on a real Android phone), the Corgi prompt (`chat-10`); requirements v1.80. Plan: docs/plans/phase-8.md (decisions in §16; step notes after §15; known limitations §12). Vault tests that need native Postgres run with `./mvnw verify -Pvault-it`; they make throwaway images in temp folders and never touch the owner's vault. PDF parsers, OCR, local PDF extraction and the vision role are the roadmap's "Later enhancements" (aspirational, outside the layers); CSV and OFX/QFX are the POC's formats. Phase 7 complete (tag `phase-7-done`): docs/plans/phase-7.md (decisions in §13; step notes, phase summary, known limitations and Backlog after the step 8 notes). Corgi is the UI's display name for all AI (one constant, `AI_NAME`); chat runs prompt `chat-10` from Phase 8 step 0 (`chat-9` before; the chat holdout was measured on `chat-9`). Every number on screen comes from the API; the frontend never computes a total, average or percent (`formatCost` rounds an estimated cost to the cent for display, nothing else rounds). If a 7e-style frontend test failure recurs, keep `frontend/test-reports/vitest.json` and record the names (phase-7.md step 8 notes). Phase 6 complete (tag `phase-6-done`): docs/plans/phase-6.md (decisions in §18; step notes 1–9b; the Phase 7 backlog: h-u10, h-x06, the holdout guard per set, Haiku's search fallback, the conservative cost estimator). Chat runs on Sonnet 4.6 at `max-turn-cost` 0.15, Haiku 4.5 at 0.05 the budget option; the chat holdout has been evaluated once (56/58, docs/eval/README.md "Holdout") and is never run again. Phase 5 complete: docs/plans/phase-5.md (phase summary, known limitations and Backlog; totals report docs/eval/phase-5-totals.md; no AI anywhere in analytics). Phase 4 complete: docs/plans/phase-4.md (accuracy report docs/eval/phase-4-accuracy.md; the holdout has been evaluated once and is never run again; open items in its Backlog). Phase 3c complete: docs/plans/phase-3c.md.

## Notes
- CI (GitHub Actions) is not active yet; run ./mvnw clean verify and npm test locally before every commit.
