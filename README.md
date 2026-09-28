# codebase-ai

Chat with your codebase: upload a ZIP, the service extracts it, chunks and embeds
the code, and answers questions about it (RAG) with file/line citations. It also
exposes agentic tools (investigate, explain, debug, generate docs/README, review).

## Architecture

```
React SPA (5173)  ->  Spring Boot API (8080)  ->  FastAPI AI service (8000)
                        |                            |
                        +--> PostgreSQL 16 + pgvector (5432)
                             (users, projects, files, chunks, chat history)
```

* `frontend/` – React 19 + TypeScript + Vite SPA. Talks only to the backend; the
  dev server proxies `/api` to `http://localhost:8080` (see `vite.config.ts`).
* `backend/` – Spring Boot 3.4 (Java 17). Owns authentication (JWT access token +
  rotating refresh token in an httpOnly cookie, plus email verification: no token is
  ever issued for an unverified account), project/file ownership checks, ZIP ingest,
  chunking, embedding calls, vector search and chat persistence.
* `ai-service/` – FastAPI. Owns the embedding model (`sentence-transformers`),
  LLM calls (Groq), the SSE chat stream, the tool-calling investigation agent and
  the deterministic code-review pipeline. Called server-to-server only.

Data flow for a question: `POST /api/sessions/{id}/messages/stream` → auth +
ownership + RAG context retrieval in the backend → `POST /chat/stream` on the AI
service → SSE events (`meta`, `token`, `done`/`error`) relayed to the browser →
answer + citations persisted in `chat_messages`.

## Prerequisites

* JDK 17+, Docker, Node 20+, Python 3.10+
* A Groq API key (`GROQ_API_KEY`)

## Running locally

```bash
# 1. Database (Postgres 16 with pgvector) + MailHog for verification emails.
#    MailHog web UI: http://localhost:8025
docker compose up -d

# 2. AI service  (http://localhost:8000)
cd ai-service
python -m venv venv
venv/Scripts/pip install -r requirements.txt      # Windows; use venv/bin/... on Unix
venv/Scripts/python main.py                        # or: venv/Scripts/uvicorn main:app --port 8000

# 3. Backend     (http://localhost:8080)
cd backend
./mvnw spring-boot:run                             # Windows: .\mvnw.cmd spring-boot:run

# 4. Frontend    (http://localhost:5173)
cd frontend
npm install
npm run dev
```

## Configuration

Both config files below are **git-ignored** (they hold secrets), so a fresh clone
must create them. Environment variables passed to the process override the
corresponding property (e.g. `SPRING_DATASOURCE_URL`).

### `backend/src/main/resources/application.properties`

| Key | Default | Notes |
| --- | --- | --- |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/codebase_ai` | must match `docker-compose.yml` |
| `spring.datasource.username` / `.password` | `admin` / dev password | use env vars in production |
| `spring.jpa.hibernate.ddl-auto` | `validate` | schema is owned by Flyway, keep as `validate` |
| `spring.flyway.enabled` / `locations` | `true` / `classpath:db/migration` | |
| `server.port` | `8080` | |
| `app.jwt.secret` | dev string | **must** be a private, random 32+ byte value in production |
| `app.jwt.access-token-validity-ms` | `900000` (15 min) | |
| `app.jwt.refresh-token-validity-ms` | `604800000` (7 days) | also drives the refresh cookie lifetime |
| `app.upload.directory` | `./uploads` | must be writable; the AI service needs to read it |
| `app.ai-service.url` | `http://localhost:8000` | |
| `spring.servlet.multipart.max-file-size` / `.max-request-size` | `50MB` | keep in sync with the frontend upload check |
| `app.cookie.secure` | `false` | **set `true`** whenever the app is served over HTTPS |
| `spring.mail.host` / `.port` | `localhost` / `1025` | MailHog locally (`localhost` because the backend usually runs on the host); `mailhog:1025` inside Compose; a real SMTP host in production via `SPRING_MAIL_*` |
| `spring.mail.properties.mail.smtp.auth` / `.starttls.enable` | `false` / `false` | MailHog accepts plain SMTP; **set both `true`** for a production provider |
| `app.public-url` | `http://localhost:5173` | public SPA URL used to build verification links; `http://localhost:3000` in Compose |
| `app.mail.from` | `no-reply@codebase-ai.local` | sender address; providers such as SES require a verified `from` |
| `app.verification.token-ttl-hours` | `24` | lifetime of a verification link |
| `app.verification.resend-cooldown-seconds` | `60` | minimum gap between two verification emails for one address |

### `ai-service/.env`

| Key | Notes |
| --- | --- |
| `GROQ_API_KEY` | required |
| `LLM_MODEL` | required, e.g. `openai/gpt-oss-20b` |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | the agent reads chunks directly from pgvector |
| `UPLOAD_DIR` | absolute path to the backend upload directory (`backend/uploads`) used by the file tools |
| `EMBEDDING_MODEL` | optional, default `all-MiniLM-L6-v2`; must stay 384-dimensional to match `chunks.embedding vector(384)` |

## Email verification

Registration creates the account **unverified** and mails a single-use link; no access
or refresh token is issued until that link is opened, so an unverified account cannot
reach any endpoint. The gate is re-checked on every data-creating or AI endpoint by
`VerifiedUserFilter` (project creation, ZIP upload, semantic search, docs export, chat
and all agent tools).

| Endpoint | Behaviour |
| --- | --- |
| `POST /api/auth/register` | creates the account, mails the link, returns `{ message, email }` (no session) |
| `GET /api/auth/verify-email?token=...` | consumes the token (single use, 24 h TTL), marks the account verified |
| `POST /api/auth/resend-verification` | mails a fresh link (invalidating the old one) and always answers identically, so it cannot be used to probe which addresses exist |

Locally every mail shows up in MailHog at http://localhost:8025. For production set
`SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`,
`SPRING_MAIL_PASSWORD`, `APP_MAIL_FROM` and `APP_PUBLIC_URL`, turn on
`spring.mail.properties.mail.smtp.auth` + `.starttls.enable`, and serve the app over
HTTPS. Only the SHA-256 hash of a token is stored, tokens are never logged, and the
verification link points at the SPA route `/auth/verify-email` rather than at the API
so mail scanners that prefetch links cannot consume the token.

## Tests

`backend/src/test/java/.../BackendApplicationTests.java` is a smoke test that the
application class is present. The unit tests — `AuthServiceTest`,
`EmailVerificationServiceTest`, `MailServiceTest` and `VerifiedUserFilterTest` — cover
the registration/verification rules and need neither a database nor an SMTP server:
`cd backend && ./mvnw test`. There are no frontend or AI-service test suites;
`npm run lint` and `npm run build` (`tsc -b`) are the frontend checks, and
`ai-service` files are checked with `python -m py_compile`.
 
