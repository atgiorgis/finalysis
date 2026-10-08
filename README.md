# Finalysis

Spring Boot backend + React (Vite, TypeScript) frontend.

```
backend/      Spring Boot 4 REST API (Java 21, Maven wrapper) — runs on :8080
frontend/     React 19 + Vite app — runs on :5173, proxies /api to :8080
sample-data/  Synthetic bank and card statements (see "Sample data")
tools/        Sample data generator
compose.yaml  Postgres 17 + pgvector
```

## Prerequisites

- JDK 21+
- Node.js 22+
- Docker (for the database and for Testcontainers-based tests)

## Running locally

```sh
cp .env.example .env          # then set POSTGRES_PASSWORD
docker compose up -d db

# Terminal 1 — backend
cd backend
./mvnw spring-boot:run

# Terminal 2 — frontend
cd frontend
npm install
npm run dev
```

Open http://localhost:5173. The page calls `GET /api/status` and shows the backend status.

## Tests

```sh
cd backend && ./mvnw verify
cd frontend && npm test
```

## Sample data

`sample-data/` holds fully synthetic statements for six months (April 1 – September 30, 2026).
Every institution, account number, and employer is fictional. Merchant strings imitate real
card descriptors (`SQ *BLUE BEAN CAFE 4412`, `AMZN MKTP US*2K4L91`) so categorization is realistic.

| Account | Format | Statement period |
|---|---|---|
| Northfield Bank Checking (…1001) | `Date,Description,Debit,Credit,Balance`, `MM/DD/YYYY` | calendar month |
| Northfield Bank Savings (…1002) | same as checking | calendar month |
| Apex Rewards Visa (…2001) | `Transaction Date,Post Date,Description,Category,Amount`, `YYYY-MM-DD`, purchases positive | 15th – 14th, named by closing month |
| Summit Cash Card (…3001) | `Trans. Date,Merchant Name,Amount`, `DD-Mon-YYYY`, purchases negative | calendar month |

- `statements/` — one CSV per account per statement period, e.g. `apex-visa_2026-04.csv`.
  The first and last Apex cycles extend past the data range, so they are only partly filled.
- `answer-key.csv` — every transaction with its normalized amount (negative = money out),
  expected category, `is_transfer`, and anomaly tag. Savings transfers and card payments are
  transfers on both sides; card payments appear in checking and on the card 1–3 days apart,
  with different descriptions.
- `statements-manifest.csv` — period and opening/closing balance per statement, in the same
  sign convention (card balances owed are negative), so `opening + sum(amounts) = closing`.
- `broken/summit-card_2026-06.csv` — the June Summit statement with one transaction missing.
  Its manifest balances are correct, so it does not reconcile; use it to test the review queue.

**Planted anomalies**

| Anomaly | Where | Answer-key tag |
|---|---|---|
| Music subscription price increase | `SPOTIFY USA` $11.99 → $12.99 from July | `price_increase` |
| Duplicate charge | `SAFEWAY #1234` $84.12 twice on June 13 (Apex) | `duplicate` |
| Unusually large purchase | `BEST BUY` $899.99 on August 22 (Apex), ~10x a normal shopping trip | `large_purchase` |
| Rising dining spend | Summit dining totals increase every month | — (trend) |
| Missing bill | No `SKYWAVE INTERNET` payment in September | — (absent row) |

**Regenerating.** Output is byte-identical on every run (fixed seed and dates). From the repo root:

```sh
java tools/SampleDataGenerator.java
```

CI regenerates the data and fails if it differs from what is committed. `SampleDataTest` checks
that every statement reconciles with the manifest, the broken one does not, every card payment
has a matching checking transfer, and the answer key matches the statements.

## License

[MIT](LICENSE)
