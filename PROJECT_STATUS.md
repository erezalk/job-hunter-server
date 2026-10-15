# Job Hunter — Project Status

Current-state reference for developers (and AI coding sessions) returning to the project. It describes what the code does now; when this file and the code disagree, the code wins.

## Purpose

Job Hunter is a job-search tracker: a Kanban Board of job applications, interview stages and notes, an uploaded CV, AI job-fit evaluation (Anthropic Claude or Google Gemini, configured per user), and a local MCP server so Claude Desktop can add jobs to the Board.

## Repositories

Three sibling projects, normally cloned side by side:

| Project | Stack | Role |
|---|---|---|
| `job-hunter-server` | Java 25, Spring Boot 4, Spring Security, Spring Data JPA (Hibernate 7), Flyway, PostgreSQL 17, PDFBox | REST API, persistence, AI provider calls |
| `job-hunter-web` | React 19, TypeScript, Vite, MUI, TanStack Query, dnd-kit, axios | Browser UI |
| `job-hunter-mcp` | Node ≥ 20, TypeScript, `@modelcontextprotocol/server`, zod, esbuild, `@anthropic-ai/mcpb` | Local stdio MCP server exposing one tool, `add_job` |

## Backend structure

Packages by feature under `dev.erezalkalay.jobhunter`: `auth`, `config`, `user`, `company`, `job`, `application`, `board`, `cv`, `settings`, `jobmatch` (+ `jobmatch.ai`), `integration`, `common` (exceptions, crypto). Each holds its entity, repository, service, controller and `dto/` records. Controllers are thin; rules live in services; services are `@Transactional(readOnly = true)` at class level with write methods overriding it.

- **Schema**: owned by Flyway (`src/main/resources/db/migration`, starting from `V1__initial_schema.sql`). Hibernate runs with `ddl-auto: validate`. Applied migrations are never edited; changes are new migrations.
- **Errors**: `GlobalExceptionHandler` (extends `ResponseEntityExceptionHandler`) returns one `ApiError` JSON shape. Spring MVC client errors keep their 4xx status (404 unknown route, 405, 415, 400 for bad parameters/parts/bodies). Bean-validation failures return `fieldErrors`. Unique-constraint violations → 409, value-too-long/invalid-format database errors → 400, anything else → sanitized 500 (details only in the server log).
- **Request limits**: job description ≤ 100,000 chars, notes (general and stage) ≤ 50,000 chars, CV ≤ 10 MB (configurable), plus per-field sizes mirroring the columns.

## Authentication and security

- **Browser**: form login (`POST /api/auth/login`, `email`/`password`) creating an `HttpOnly` `JSESSIONID` session. CSRF via `CookieCsrfTokenRepository` (`XSRF-TOKEN` cookie readable by JS, echoed as `X-XSRF-TOKEN`) with the plain (non-masking) request handler, because the SPA echoes the raw cookie value. `GET /api/auth/csrf` primes the cookie; a fresh token is issued after login. CORS allows `CORS_ALLOWED_ORIGINS` (default `http://localhost:5173`) with credentials.
- **Accounts**: self-service sign-up and change password (see "Accounts" below). No account is created automatically. `InitialUserRunner` is an opt-in bootstrap: with an empty `users` table it creates one user only when `APP_INITIAL_USER_EMAIL` and `APP_INITIAL_USER_PASSWORD` are both set (sign-up email/password rules apply; only one set, or invalid values, fail startup). It never generates or logs a password and ignores the settings once any user exists. The test profile sets them to create `test@jobhunter.local`.
- **Sessions**: ordinary in-memory servlet sessions, plus a Spring Security `SessionRegistry` (`SessionConfig`, `sessionConcurrency` with unlimited sessions) that tracks every authenticated session per user; `HttpSessionEventPublisher` keeps it in step with destroyed sessions and session-id changes. A session the registry has expired answers 401 `Your session has ended` on its next request.
- **Ownership**: see "Ownership and multi-user isolation" below — every job-domain resource is resolved by id *and* owner; another user's id is a 404, exactly like a missing one.
- **MCP integration**: a second, stateless `SecurityFilterChain` (`@Order(1)`) matches only `/api/integrations/**`, authenticates `Authorization: Bearer jh_…` and grants the single authority `SCOPE_jobs:add` with the token owner as principal; CSRF is disabled for that chain only. A bearer token is rejected everywhere else, and a browser session is rejected on `/api/integrations/**`.
- **Network defaults**: the API binds to `127.0.0.1` (`SERVER_ADDRESS`), Docker publishes PostgreSQL on `127.0.0.1:5432` only.
- **Frontend session handling**: a central axios interceptor treats a 401 from any API call (except the login request) as an ended session: the app returns to the login screen and the TanStack Query cache is cleared. Logout and every successful login clear the cache too. The theme's settings query only runs while logged in.

## Accounts

- **Sign-up** (`POST /api/auth/register`, JSON `email`/`password`/`confirmPassword`; public, CSRF-protected like every POST; `AccountController`/`AccountService`): all field problems are reported together (400 `fieldErrors`). Creates the user and its default `settings` row in one transaction — nothing else (no CV, AI key, MCP token or jobs) — then logs the account in on the same request exactly like form login (`SessionAuthentication.logIn`: new session id, registered in the session registry, credentials erased, fresh CSRF cookie) and answers 201 with the `/me` body. A request from an already logged-in session is a 400. `full_name` is stored empty (no name is asked for); `/me` then returns `fullName: null` and the UI shows the email.
- **Email**: surrounding whitespace removed, otherwise stored as entered (case kept; dots, `+tags` and providers untouched). At most 254 characters (local part ≤ 64), pragmatic format check (`EmailAddresses`). Uniqueness ignores case and is enforced by `uq_users_email_ignore_case` (upper(email)); an existence pre-check gives the common case a friendly 409 (`An account with this email already exists`, also as an `email` field error) and the unique index decides concurrent sign-ups (exactly one wins, the rest get the same 409).
- **Password policy** (`PasswordPolicy`, sign-up and change password; mirrored in the frontend's `accountRules.ts`): at least 8 characters, at most 72 bytes UTF-8 (BCrypt's input limit), not only whitespace, no composition rules, never trimmed or altered. Hashing: the existing BCrypt encoder (strength 10, `$2a$`), as `BcryptLimitAwarePasswordEncoder`, which only makes a > 72-byte submitted password a non-match instead of a 500.
- **Change password** (`POST /api/auth/change-password`, JSON `currentPassword`/`newPassword`/`confirmNewPassword`; session + CSRF; a bearer token gets 401): wrong current password → 400 field error `currentPassword` (not 401, so the UI keeps the session); new password identical to the current one → 400. Only `users.password_hash` changes — jobs, CV, settings, API keys and the MCP token stay as they are (the token is a separate, narrow credential; revoke it in Settings → Claude / MCP if needed). Then every **other** session of the user is expired through the registry, and this session continues under a new session id (a copied cookie of it stops working). 204.
- **Rate limits** (`auth.ratelimit`): token buckets, configured under `app.auth.rate-limit.*` — login 30 per 5 min per client IP and 10 per 15 min per email, sign-up 10 per hour per IP (only well-formed requests count), change password 5 per 15 min per user (counted when the current password is checked). Exceeded → 429 `RATE_LIMITED` with `Retry-After`; buckets refill continuously, so nobody is locked out for longer than one interval. The login check runs inside authentication (`RateLimitedAuthenticationProvider`, the only provider of the login's parentless AuthenticationManager), before the account lookup, so it covers every login request and answers identically for existing and unknown emails. The email key folds case the way PostgreSQL's `upper()` does (e.g. `ı`/`i`), so no spelling of an address gets a fresh bucket. Client IP = servlet remote address; behind a reverse proxy configure `server.forward-headers-strategy` for the trusted proxy (X-Forwarded-For is never read directly).
- **Scaling limitation**: sessions, the session registry and the rate-limit counters (`InMemoryRateLimitStore`) are in this JVM's memory — correct for the current single instance, but a restart logs everyone out and resets the counters, and several instances would each count separately. Moving to shared storage means Spring Session (with its `SessionRegistry` adapter) and a `RateLimitStore` implementation over e.g. Redis; nothing else changes. Per-IP limits treat each IPv6 address separately.

## Ownership and multi-user isolation

Guarantee: data of one user is never readable, changeable or deletable through another user, whatever ids a request names, and one user's activity never has a side effect on another user's data. The backend is authoritative; the frontend filters nothing for security.

| Table | Classification | Owner |
|---|---|---|
| `jobs` | user-owned | `user_id` |
| `companies` | user-owned | `user_id` |
| `applications` | user-owned | `user_id`, equal to its job's owner (composite FK) |
| `application_status_history`, `application_stages` | user-owned through their application | via `application_id` |
| `job_matches` | user-owned | `user_id`, equal to its job's (and CV's) owner (composite FKs) |
| `cvs`, `settings`, `integration_tokens` | user-owned | `user_id` (settings and token: one per user) |
| `application_statuses` | global immutable reference data | — |
| `users`, `flyway_schema_history` | authentication / system | — |

```
users ─┬─< companies ─────────────┐ (company_id, user_id)
       ├─< jobs >─────────────────┘
       │     ├── applications (exactly one per job)
       │     │     ├─< application_status_history
       │     │     └─< application_stages
       │     └── job_matches (at most one per job) ── cvs (same user, optional)
       └─< cvs, settings (1), integration_tokens (1)
```

- **No shared mutable rows.** Two users adding the same company + title get two independent jobs, companies, tickets and matches. Every field (title, company, description, URL, salary, source, status, star, order, notes, stages, match, LOW_MATCH discard, delete) only ever changes the owner's rows.
- **Database enforcement.** Owner columns are `NOT NULL`. Composite foreign keys `(job_id, user_id) → jobs(id, user_id)` (applications, job_matches), `(company_id, user_id) → companies(id, user_id)` (jobs) and `(cv_id, user_id) → cvs(id, user_id)` (job_matches) make a cross-user reference impossible even for buggy code. `users.email` is unique ignoring case (`upper(email)`, the same comparison every principal lookup uses), so a session or token resolves to exactly one user.
- **Authorization pattern.** The job-domain repositories (`JobRepository`, `CompanyRepository`, `ApplicationRepository`, `ApplicationStageRepository`, `ApplicationStatusHistoryRepository`, `JobMatchRepository`) are plain Spring Data `Repository` interfaces that expose only owner-scoped finders (`findByIdAndUserId`, `findByUserIdAnd…`) and writes — no `findById`/`findAll` exists to misuse. Services resolve the root resource for the current user first (`JobService.getJobForCurrentUser`, `ApplicationService.getApplication`); nested resources are only reached through that verified parent (a stage id resolves only inside the given application *and* user). Entities derive ownership from their parent (`new JobMatch(job)` takes the job's user; a ticket can only be created by the job's owner). Controllers stay thin.
- **Not-found, not forbidden.** Another user's id answers 404 with the same message as a missing id; nothing reveals that it exists.
- **Deduplication is per user**: normalized company + title (`uq_jobs_user_company_title`), `(user_id, source, external_id)` when an external id is set, company names per user (`uq_companies_user_name_key`). No unique constraint spans users.
- **Deletion graph.** Deleting a ticket (`DELETE /api/applications/{id}`) removes, all owned by the caller: its stages, status history, AI match, the Application, the Job, and the company when no other job of the same user uses it. The database cascades the same way (job → application → history/stages, job → match) as a safety net; `jobs.company_id` has no cascade, so a company in use cannot be deleted. Deleting a user cascades to exactly that user's rows (jobs, companies, applications and their children, matches, CVs, settings, token) — never anyone else's, since no row is shared. CV files on disk are not removed by a database cascade (account deletion is not implemented yet).
- **MCP.** The bearer token resolves to exactly one user; `add_job` runs the same owner-scoped add as the Board, so it only finds or creates that user's job and match. The request cannot name an owner, and the token is accepted on `/api/integrations/**` only.
- **Session caches.** The frontend clears the TanStack Query cache on login, logout and session expiry (any 401).

## Domain model

- **Job** — one user's tracked posting: title, company, location, work model, URL, salary min/max/currency, description, `source` (free text: `"Manual"` for browser Add Job, `"External AI"` for MCP, editable, null when unknown), `discoveredAt`. Always created together with its Application and deleted with it.
- **Company** — the user's company of that name (normalized `name_key`), with optional website and notes (no endpoint edits these yet; they come from import). Exists only while one of the user's jobs uses it.
- **Application** — the Board "ticket" of a job, exactly one per job: status, notes, starred flag, Board position, `appliedAt` (set the first time it enters `CV_SENT`).
- **ApplicationStatus** — lookup table, not an enum: `NEW`, `CV_SENT`, `INTERVIEW_PROCESS` (label "Interview"), `OFFER`, `REJECTED`, `ARCHIVED`, `LOW_MATCH`. Any transition is allowed; `is_terminal` is informational only.
- **ApplicationStatusHistory** — one row per status change (automatic changes have notes starting with "Automatic:").
- **ApplicationStage** — manually entered recruitment events (name, optional `scheduledAt`, interviewers, notes, outcome). Listed scheduled-first by date, then by creation.
- **Cv** — one row per upload; exactly one primary ("active") per user.
- **JobMatch** — the owner's AI evaluation of a job, at most one per job.
- **Settings** — per user: theme, timezone, visible Board columns, minimum fit score, AI provider/model, encrypted provider keys.
- **IntegrationToken** — one MCP token per user (hash only).

## Board

- One card per Application, columns in the fixed order above. Which columns are shown is a per-user setting (`visibleBoardStatuses`, stored as a comma-separated list of validated status codes; at least one; `LOW_MATCH` hidden by default). Hiding a column never changes data.
- **Order**: `board_position` per (user, status), ties by id descending. New tickets and status changes without a drop position go to the top. `PATCH /api/applications/{id}/move` (`statusCode` + optional `beforeApplicationId`/`afterApplicationId`) locks and resequences the destination column; same column = reorder only, no history row.
- **Drag and drop** is optimistic in the TanStack Query cache, with rollback on failure; moves and star toggles share one mutation scope so they reach the server in order.
- **Filters** (frontend only, not persisted): company (case/whitespace-insensitive), date presets (Today / Last 7 / Last 30 days / custom range, by local calendar date of `discoveredAt`), starred only. Clicking the company in the ticket drawer sets the company filter.
- **Ticket drawer**: job information (editable), AI match, status selector (same endpoint as drag and drop; closes the drawer if the new status is hidden), general notes, stages, delete. Its content is keyed by application id, so local state and in-flight mutations always belong to the ticket they started on.
- **Upcoming stages bell**: `GET /api/stages/upcoming` lists the user's future scheduled stages (any status, nearest first); a red dot shows while any exist. Dates are entered and shown in browser-local time.

## Adding, deduplicating, editing and deleting jobs

- **Deduplication** (within the current user's jobs only): a job is identified by normalized company + normalized title (whitespace collapsed, trimmed, lowercased; no fuzzy matching), enforced by `uq_jobs_user_company_title`. Location and URL are not part of the identity. A partial unique index on `(user_id, source, external_id)` exists for future external sources; no current flow sets `external_id`.
- **Add Job** (`POST /api/board/jobs`, and the MCP endpoint): find the user's job or create it together with its Application in `NEW`. Returns 201 when a ticket was created, 200 when it already existed (any status, including hidden ones). A concurrent duplicate insert by the same user is retried once in a fresh transaction. There is no endpoint that creates a job without its ticket or attaches a user to an existing job.
- **Edit** (`PATCH /api/jobs/{id}`): full replace of the editable fields; identity keys recomputed; collision with another of the user's jobs → 409. If title/company/location/work model/description changed, the job's JobMatch is deleted (no AI call); URL/salary/source edits keep it. A non-blank description clears the "description discarded" flag. A company no longer used by any of the user's jobs is removed.
- **Delete** (`DELETE /api/applications/{id}`): see the deletion graph above.

## CV

PDF only (content type or `.pdf` name), size limit `CV_MAX_FILE_SIZE_BYTES`, file name ≤ 255 chars. Text is extracted with PDFBox (no OCR); under 40 characters is reported as `EMPTY`, a parse failure as `FAILED` (both with a warning). Each upload creates a new primary row; earlier rows and files are kept (match fingerprints reference the CV id). Files are stored under `CV_STORAGE_DIR` as `{userId}-{uuid}.pdf`, never using the client file name; reads are confined to that directory. `GET /api/cv/file` serves only the caller's active CV; the frontend fetches it as a blob and shows it through an object URL (the API keeps `X-Frame-Options: DENY`). The extracted text is used for AI matching; `GET /api/cv` currently also returns it, but the UI does not display it.

## AI matching

- **Providers**: `ANTHROPIC` and `GEMINI` (`AiJobMatchProvider` implementations looked up through `AiJobMatchProviderRegistry`), called directly over REST with `RestClient` — no vendor SDK, so request/response handling stays explicit and structured output can be requested per provider (Anthropic `output_config.format` JSON schema, Gemini `responseSchema`). The model is a free-text setting (the UI offers suggestions; Gemini is a fixed list in the UI). `JobMatchPromptBuilder` holds the shared prompt.
- **Parsing**: `AiResponseParser` requires `fitScore` to be a JSON integer 0–100; anything else (missing, null, string, boolean, fraction, out of range) is `AI_INVALID_RESPONSE` — never defaulted or clamped. Skill/concern lists default to empty.
- **Errors and retries**: `AiCallExecutor` maps failures to `AiErrorCode` (each with a user-safe message and HTTP status: 429 rate limit/quota, 503 provider unavailable, 504 timeout, 502 otherwise; provider 401/403/404 are not echoed as our own 401/403/404). Only 502/503/504/529 are retried, at most `AI_MAX_RETRIES` (default 1) times with jittered backoff, never waiting longer than `AI_RETRY_MAX_WAIT_MS`. 429 is never retried; Test Connection never retries. Gemini 429s are classified as rate limit vs. long-term quota from structured error details; `Retry-After` is passed to the client. Logs contain provider, model, status and quota identifiers — never keys, prompts, CV/job text or provider bodies.
- **Evaluation flow** (`JobMatchService.evaluateForCurrentUser`, `POST /api/jobs/{id}/match/evaluate?force=`):
  1. Short transaction: ownership, active CV, provider/model, decrypted key, fingerprint; returns the stored match when `force` is false and its fingerprint matches; refuses (400) when the description was discarded.
  2. Provider call with **no database transaction open**.
  3. Short transaction: inputs re-resolved; if the fingerprint changed during the call (CV, provider/model or AI-relevant job content), nothing is stored and the response is 409. Otherwise the match is saved and the LOW_MATCH rule applied, together.
  A provider or parsing failure changes nothing.
- **Fingerprint**: SHA-256 of CV id, job id (both user-owned, so a fingerprint can never match another user's inputs), AI-relevant job content (title, company, location, work model, description), provider, model and a prompt version (`v1`, bump when the prompt changes meaningfully). In the UI, "Evaluate fit" (shown when no match exists) is non-forced; "Re-evaluate" is forced.
- **LOW_MATCH rule** (after a successful provider evaluation, using the user's minimum fit score, default 70): below the threshold, the job's `NEW` application becomes `LOW_MATCH`, the (owner's) job description is discarded (`description_discarded = true`) and the match's skill/concern lists are cleared; an already `LOW_MATCH` ticket stays and is cleaned the same way. At or above, `LOW_MATCH` returns to `NEW`. Other statuses are never changed and nothing is discarded for them. A discarded job can be re-evaluated after a description is added (Edit, or re-adding the job with a description).
- **Settings and keys**: provider/model/threshold via `PUT /api/settings`; keys via `PUT`/`DELETE /api/settings/ai/api-key`, stored per provider, AES-256-GCM encrypted with a key derived (SHA-256) from `APP_ENCRYPTION_SECRET`; never returned (only `…ApiKeyConfigured` booleans). Without the secret, saving a key is a 503; a stored key that can no longer be decrypted (e.g. the secret changed) is a 400 asking to re-enter it, and the stored value is left untouched. `DELETE /api/settings/ai` removes both keys, provider, model, resets the threshold and deletes the user's JobMatch rows. `POST /api/settings/ai/test-connection` makes one request (Gemini: `GET models/{model}`; Anthropic: a minimal generation).

## MCP integration and imported fit

- `job-hunter-mcp` is a stdio MCP server started by Claude Desktop from a `.mcpb` extension. One tool, `add_job`: the Add Job fields (title required) plus an optional evaluation — `fitScore` (integer 0–100), `matchingSkills`, `missingSkills` (shown as "Gaps"), `concerns`, `evaluationModel`. It calls `POST /api/integrations/jobs` with the bearer token and maps the result to text (added / already exists / validation problems / auth / backend unavailable). It holds no validation or dedup rules of its own (deduplication is within the token owner's jobs); stdout is protocol-only, logs go to stderr; the token is never logged or returned.
- **Token**: `POST /api/settings/integrations/mcp-token` (browser session + CSRF) returns `jh_` + 32 random bytes (base64url) once; only its SHA-256 hex digest and last 4 characters are stored. One per user; generating replaces the previous token, `DELETE` revokes; no expiry. The owner is always the token's user (the request has no user field).
- **Imported fit**: stored as a normal JobMatch with provider `EXTERNAL_AI` in the same transaction as the job, only when the call created the ticket and the user has no match yet (duplicates never create or overwrite one). Validated before any write: lists without a `fitScore` are rejected; lists are trimmed, de-duplicated, ≤ 50 items × 1000 chars; model ≤ 100 chars. No provider is called and no threshold/LOW_MATCH rule is applied. "Re-evaluate" replaces it with the configured provider's result and applies the normal rule; an AI-relevant job edit deletes it like any match.

## Job data export / import

Purpose: get all of a user's job and Board data out of (and back into) the application independently of the current schema.

- **Endpoints** (browser session + CSRF; package `jobdata`; a bearer token gets 401): `GET /api/job-data/export` returns `job-hunter-export-YYYY-MM-DD.json` as an attachment; `POST /api/job-data/import` (`application/json`, body ≤ `app.job-data.import-max-bytes`, default 50 MB) answers 201 with counts. UI: Settings → Data.
- **Format** (`JobDataV1`, a stable contract independent of the database schema): `{format: "job-hunter-jobs", formatVersion: 1, exportedAt, jobs: [...]}`. Each job holds its fields, company (name, website, notes), its application (status, Board position, starred, notes, applied/created/updated timestamps, full status history, stages) and its AI match (score, skill/gap/concern lists, provider incl. `EXTERNAL_AI`, model, evaluated/created timestamps). Relationships are expressed by nesting; each job has a file-local `ref`. No database ids, no owner, no user identity.
- **Scope**: every job of the current user (every query is owner-scoped). Not included: CV (file, text, metadata), account/password, sessions, settings (theme, timezone, Board columns, AI provider/model/threshold), API keys, MCP tokens, other users' data.
- **Derived values** are not exported and are recomputed on import with the same code as normal job creation: `company_key`, `title_key`, `normalized_url`, `companies.name_key`. **Not restorable by design**: a match's CV link and reuse fingerprint (both tied to rows of the exporting database), and company row timestamps (internal, never shown). A restored match is shown as stored but never reused as a cache hit, so the next non-forced evaluation calls the provider.
- **Import semantics (v1)**: restore into an **empty** job domain of the current user only (no jobs, companies, applications or matches of their own); other users' data is never read or changed and never blocks the restore. The whole file is parsed strictly (no unknown properties, no type coercion) and validated (format/version, field limits mirroring the columns and request limits, known status codes and providers, unique refs, unique company + title within the file, consistent company data for jobs naming the same company, http(s)-only URLs) before anything is written; otherwise 409/400 and nothing changes. Every row is created for the session user (the file cannot name an owner). Records are written directly — no status transitions, automatic history rows or AI calls — and the exported timestamps are written back. One transaction with the user's row locked (imports of the same user cannot interleave): any failure rolls back everything.
- **Completeness guard**: a test compares the columns of every job/Board table with an explicit exported/recomputed/excluded list, so a new column fails the build until the format decides how to treat it. The round-trip test (rich Board → export → empty Board → import → export) requires identical files apart from `exportedAt`. `RealBackupRehearsalTest` (opt-in, see README) restores a real export into a disposable database and requires an identical re-export.

## Backups

- **Job and Board data**: the export above, restorable into an empty job domain of any account (also on a new database).
- **Everything else** (account, settings, encrypted API keys, CV rows, MCP token): a full `pg_dump` of the database, plus the `CV_STORAGE_DIR` directory for the CV files. Stored API keys are only readable with the same `APP_ENCRYPTION_SECRET`.

## Frontend structure

`src/api/` (one thin module per backend area, shared axios client with CSRF and the 401 interceptor), `src/auth/` (AuthProvider/RequireAuth, sign-up/password rules), `src/pages/` (Login, Sign up, Board, CV, Settings with Appearance / Board / AI / Claude-MCP / Data / Account panels), `src/board/` (columns, cards, drawer, dialogs, filters, ordering), `src/upcoming/` (bell), `src/theme/`. Server state lives in TanStack Query; forms keep local state. Passwords are sent with plain async calls from form state (never `useMutation`, the query cache, storage or URLs).

## Tests and verification

- **Backend** (`./mvnw verify`): integration tests over real HTTP (session cookies + CSRF) against a singleton Testcontainers PostgreSQL (Docker required), plus unit tests for the AI executor/parser, Gemini wire format (`MockRestServiceServer`) and encryption. Real providers are disabled under the `test` profile (`@Profile("!test")`) and replaced by fakes, so tests never call a paid API. Coverage includes the multi-user isolation matrix (`isolation/MultiUserIsolationIntegrationTest`: two users per test for jobs, tickets, stages, history, AI matches and cache, Board order, companies, export/import, MCP tokens, CV, settings and a guessed-id sweep, each checking the victim's full data is unchanged; `RegisteredAccountsIsolationIntegrationTest` repeats the key checks with two accounts created through sign-up), sign-up (`RegistrationIntegrationTest`: auto-login, hashing, duplicates in any case, a concurrent sign-up race, validation, CSRF, mass assignment), change password (`ChangePasswordIntegrationTest`: other sessions ended, a copied old cookie rejected, nothing else changed), rate limits (`AuthRateLimitIntegrationTest` in its own context with low limits; deterministic unit tests of the store), database ownership constraints and user-deletion cascade (`OwnershipConstraintsIntegrationTest`), the migrated schema (`DatabaseSchemaIntegrationTest`: an empty database migrates to the schema the application runs on; every owned table's owner column and cascade, and the composite same-owner foreign keys), error mapping, validation, deduplication races, LOW_MATCH, imported fit, that the provider runs outside a transaction, and the job data export/import round trip, rejections and rollback.
- **Frontend** (`npm test`, `npm run lint`, `npm run build`): Vitest + Testing Library for sign-up / sign-in switching and validation, Settings → Account change password, filters, Board/drawer behavior, ticket isolation, upcoming stages, Add Job errors, session expiry, MCP settings, data export/import.
- **MCP** (`npm run typecheck`, `npm run lint`, `npm test`): tool schema, request mapping, result text, no token leak, a real stdio run of the bundled server.

## Known limitations / future work

- Accounts: no forgot-password/reset, email verification, account deletion, user administration or MFA. Deleting an account would also have to remove that user's CV files from disk (the database cascade follows ownership). Changing the password does not revoke the MCP token. Sessions and rate-limit counters are per instance and in memory (see "Accounts").
- Company website/notes are stored and exported but no endpoint edits them yet.
- Job data import (v1) only restores into an empty job domain; there is no merge.
- CV files are stored on the local filesystem under the user-owned CV rows (no object storage).
- Automatic job discovery is not implemented; jobs are added manually or through the MCP tool. LinkedIn scraping/automation of a personal account is intentionally not part of the project.
- A stored AI match is not marked as outdated after the CV, provider or model changes; it stays visible until re-evaluated.
- `settings.timezone` is stored and validated but not yet used: dates are shown in browser-local time.
- The Board and application lists are not paginated. The defaults target a local machine — exposing the API beyond localhost needs at least TLS, a trusted-proxy setup for client IPs, and the items above.
