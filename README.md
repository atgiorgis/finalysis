# Finalysis

Spring Boot backend + React (Vite, TypeScript) frontend.

```
backend/    Spring Boot 3 REST API (Java 21, Maven) — runs on :8080
frontend/   React 19 + Vite app — runs on :5173, proxies /api to :8080
```

## Prerequisites

- JDK 21+
- Maven 3.9+
- Node.js 20.19+ (or 22+)

## Running locally

```sh
# Terminal 1 — backend
cd backend
mvn spring-boot:run

# Terminal 2 — frontend
cd frontend
npm install
npm run dev
```

Open http://localhost:5173. The page calls `GET /api/status` and shows the backend status.

## Tests

```sh
cd backend && mvn test
```

## License

[MIT](LICENSE)
