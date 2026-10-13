# Backend architecture and API v1

## Modular structure

The backend is a feature-oriented modular monolith under `com.example.backend`:

```text
auth/{api,application,infrastructure}
user/{api,application,domain,infrastructure}
candidate/{api,application,domain,infrastructure}
matching/{api,application,config,domain,extraction,infrastructure}
copilot/{api,application,domain,infrastructure}
job/{api,application,domain,infrastructure}
application/{api,application,domain,infrastructure}
messaging/{api,application,infrastructure,security}
integration/{adzuna,greenhouse,lever,ashby,smartrecruiters,workable,aggregation,reliability}
shared/{api,configuration,error,pagination,security}
```

Controllers handle HTTP translation and delegate to application services. Services own identity-derived
authorization, ownership, state transitions, token rules, resume storage, and messaging participation.
Repositories and MongoDB documents remain inside feature infrastructure packages. API records are dedicated
request/response types, and manual mappers are the only path from persistence or provider data to public data.
ArchUnit tests prevent controllers from depending on infrastructure, feature code from depending on
controllers, shared code from depending on feature APIs, and field injection.

## API migration

Phase 2 is a coordinated breaking migration. The React application is the only known in-repository consumer,
and it migrated in the same change. Legacy routes are not retained because duplicate security matchers and
cookie paths would increase risk. Requests to old paths are unsupported.

| Old route | Canonical route |
| --- | --- |
| `POST /auth/register` | `POST /api/v1/auth/registrations` |
| `POST /auth/register/recruiter` | `POST /api/v1/auth/recruiter-registrations` |
| `POST /auth/login` | `POST /api/v1/auth/sessions` |
| `POST /auth/refresh` | `POST /api/v1/auth/sessions/refresh` |
| `POST /auth/logout` | `DELETE /api/v1/auth/sessions/current` |
| `GET, PUT /users/me` | `GET, PUT /api/v1/users/me` |
| `POST /jobs/create` | `POST /api/v1/jobs` |
| `GET /jobs` | `GET /api/v1/jobs` |
| `GET /jobs/myjobs` | `GET /api/v1/jobs/mine` |
| `GET, PUT, DELETE /jobs/{id}` | `GET, PUT, DELETE /api/v1/jobs/{id}` |
| `POST /applications/apply` | `POST /api/v1/jobs/{jobId}/applications` |
| `GET /applications/{jobId}` | `GET /api/v1/jobs/{jobId}/applications` |
| `GET /applications/status/{jobId}` | `GET /api/v1/jobs/{jobId}/application-status` |
| `GET /applications/my` | `GET /api/v1/applications` |
| `GET /applications/item/{id}` | `GET /api/v1/applications/{id}` |
| `PATCH /applications/{id}/status` | `PATCH /api/v1/applications/{id}/status` |
| `POST /applications/{id}/withdraw` | `POST /api/v1/applications/{id}/withdrawal` |
| `GET /applications/download/{id}` | `GET /api/v1/applications/{id}/resume` |
| `GET /chat/rooms` | `GET /api/v1/conversations` |
| `GET /chat/rooms/{id}` | `GET /api/v1/conversations/{id}` |
| `GET /chat/rooms/{id}/messages` | `GET /api/v1/conversations/{id}/messages` |
| `GET /chat/unread` | `GET /api/v1/conversations/unread-count` |

Aggregation administration is exposed only below `/api/v1/admin/ingestion`: summaries and manual provider runs are joined by bounded sync history/detail/latest-status queries, provider/company counts, paginated conflict listing, and explicit conflict resolution. Reconciliation requests name both the retained canonical job ID and duplicate job ID; the backend preserves and rewrites application/conversation references before any duplicate removal.

Supported providers are listed once in `shared/provider/ProviderCatalog` (name, `ATS_BOARD` or `SEARCH_API` kind, display name, registry source, schedule property, and base URL). Provider validation, history filters, metric tags, ATS schedules, base URLs, `JobSource` resolution, and the ADMIN provider list (`GET /api/v1/admin/ingestion/providers`) all read it, so adding an ATS provider needs a `JobSource` bean, a catalog entry with its registry source constant, and configuration. The catalog lives in `shared` because both `integration` and `matching` read it.

Ashby (`ashby`) is an ATS-board provider on the shared reliability stack: it reads the public job-posting API, skips unlisted postings, applies the Greenhouse empty/malformed rules (only a `jobs` array is a valid empty board), and maps compensation only for annual INR salaries. Its terms and usage limits are recorded in [`provider-terms.md`](provider-terms.md).

SmartRecruiters (`smartrecruiters`) pages through the public Posting API with bounded pages and items, and reads each posting's detail (description and public link) once per release through a TTL-bounded Mongo cache with a per-run detail budget. A source may return `FetchResult.complete=false` (page cap, a failed later page, or deferred details); the ingestion service then stores what it received, records the board as failed for that run (`PARTIAL`/`FAILED`), and does not advance lifecycle missing state.

Search APIs (`SEARCH_API` providers) run on `integration/search`: a provider supplies its query plan (keyword × location × page groups), client, retry classification, and circuit; `SearchProviderIngestionService` owns the loop, a per-provider monthly request budget (`ProviderRequestBudget`, Mongo collection `provider_request_budgets`, one counter per provider and UTC month, reserved atomically before every request attempt), the shared fingerprint/source-listing store, and the rule that lifecycle advances only after a complete, lease-valid, unbudgeted run. `SearchIngestionCoordinator` gives each provider the `provider-ingestion:<name>` lease and durable history, `SearchIngestionCoordinators` routes ADMIN manual runs, and `SearchIngestionScheduler` schedules them from the catalog. A budget-stopped run is recorded as `PARTIAL` with history `reason` `BUDGET_EXHAUSTED`. When a plan exceeds a provider's per-run allowance, `MongoQueryRotation` (`search_query_rotation`) hands each run the next slice and accumulates the identities seen during the cycle; lifecycle missing state then advances once per complete, failure-free cycle with that union, never per partial run. Adzuna rotates 12 keywords × (country-wide + 10 Indian cities) × 2 pages within its monthly budget. Adzuna runs on this framework with its existing client, retry, circuit, configuration, lock key, and history; `AdzunaService` and `AdzunaIngestionCoordinator` remain as thin compatibility wrappers for the legacy recruiter trigger.

Jooble (`jooble`) is the first search provider built only on the framework. `JoobleSearchProvider` posts keyword/location queries through the shared `ProviderHttpClient`, circuit, limiter, and payload limits, maps `Retry-After` into the engine's backoff, and is registered (and scheduled) only when `JOOBLE_API_KEY` is set. The ADMIN provider list reports such search providers as `available: false`. Aggregator listings keep the provider's link and show a "Jobs by …" credit on job cards and details (`ProviderAttribution`).

Workable (`workable`) reads one account's public jobs-widget JSON with the Greenhouse empty/malformed rules; telecommuting jobs map to `Remote`. Its third-party terms are unverified (see [`provider-terms.md`](provider-terms.md)), so production boards need an owner check first.

Provider-wide and optional Greenhouse/Lever employer-specific manual runs enter the exact coordinator used by scheduling. Employer scope affects fetching and history, but not the provider lease key; this prevents a narrow run from overlapping a full-provider run on another instance. Adzuna uses its own equivalent Mongo-backed coordinator and accepts provider-wide scope only.

The STOMP/SockJS handshake remains at `/ws`, with messages published to `/app/chat.send`. Its request property
is `conversationId`.

Candidate-only matching is exposed by `GET /api/v1/jobs/{jobId}/match` and `GET /api/v1/jobs/matched`.
Authentication supplies the candidate identity; match routes accept no candidate identifier. Versioned invariant
job features are persisted on canonical jobs, while candidate-specific scores are calculated on demand over a
bounded Mongo-prefiltered window. See [Job Matching Engine v1.2](job-matching.md) for the scoring contract.

Candidate-only Application Copilot routes extend that result into a distinct readiness analysis and candidate-owned,
versioned preparation package. Tailored resumes, cover letters, and personal workspace state live outside the canonical
candidate profile and recruiter-controlled application record. See [Application Copilot v1.3](application-copilot.md).

## Contracts and mapping

Request DTOs contain client-editable values only. User, recruiter, candidate, status, audit, token, and storage
identifiers are derived server-side. Unknown JSON properties are rejected. Response DTOs omit password hashes,
refresh sessions, ownership internals, MongoDB details, resume paths, and provider-only fields. `JobMapper`,
`ApplicationMapper`, `MessagingMapper`, `UserMapper`, and `AdzunaJobMapper` use explicit field assignment.
Timestamps are serialized as ISO-8601 local date-times using the application's configured JVM timezone.

## Problem Details

Errors use RFC 9457 media type `application/problem+json`:

```json
{
  "type": "https://jobportal.example/problems/validation-error",
  "title": "Validation failed",
  "status": 400,
  "detail": "One or more fields are invalid",
  "instance": "/api/v1/jobs",
  "code": "VALIDATION_ERROR",
  "fieldErrors": { "title": "must not be blank" }
}
```

Stable codes are `VALIDATION_ERROR`, `MALFORMED_REQUEST`, `MISSING_PARAMETER`, `METHOD_NOT_ALLOWED`,
`UNSUPPORTED_MEDIA_TYPE`, `RESUME_TOO_LARGE`, `INVALID_RESUME_TYPE`, `UNAUTHORIZED`, `INVALID_CREDENTIALS`, `FORBIDDEN`,
`RESOURCE_NOT_FOUND`, `DUPLICATE_EMAIL`, `DUPLICATE_APPLICATION`, `INVALID_STATUS_TRANSITION`, `CONFLICT`,
`DUPLICATE_RESOURCE`, `RATE_LIMITED`, `BAD_REQUEST`, `RESUME_PARSE_FAILED`, and `INTERNAL_ERROR`. Unexpected details are logged only as
exception types and the client receives a generic message.

## Pagination, filters, and sorting

Collections return `{ content, page, size, totalElements, totalPages, first, last, sort }`. Page defaults to `0`,
size defaults to `20` (`50` for message history), and maximum size is `100`. Negative pages and sizes outside
`1..100` are rejected with `400`. Every database query receives `Pageable`; `id` is appended as a deterministic
tie-breaker.

| Collection | Filters | Sort allowlist |
| --- | --- | --- |
| Jobs | `q`, `location`, `source` | `createdAt`, `title`, `location`, `company`, `salary`, `experience` |
| Owned jobs | none | same as jobs |
| Applications/applicants | none | `appliedAt`, `status` |
| Conversations | none | `lastMessageAt`, `createdAt`, `jobTitle` |
| Messages | none | `sentAt` |

Text filters are trimmed, length-bounded, and regex-quoted. Arbitrary sort properties never reach MongoDB.

## Authentication and security

Access tokens remain in React module memory. The rotating refresh token remains hashed at rest and travels only
in an `HttpOnly` cookie scoped to `/api/v1/auth`. Refresh remains single-flight in the browser. Login rate
limiting, restricted CORS, job/application ownership, privacy-preserving protected reads, message participation,
PDF signature/type/size validation, path containment, and symlink-safe resume access remain enforced in services.

## Candidate intelligence flow

```text
Authenticated candidate
        ↓
Secure PDF/DOCX upload → private persisted resume namespace
        ↓
PDFBox / Apache POI text extraction
        ↓
Section and contact detection → focused education/experience/project/certification parsers
        ↓
Skill alias normalization and de-duplication
        ↓
One MongoDB candidate profile per user
        ↓
Editable React profile and deterministic Resume Quality Score
```

Candidate routes never accept a user identifier. `CurrentUserProvider` and the authenticated user record derive ownership,
and Spring Security restricts `/api/v1/candidate-profile/**` to `USER`. Parser output is explicitly reviewable: status and
warnings remain visible, account email is never overwritten, and manual edits are normalized again server-side.

## OpenAPI

OpenAPI JSON is at `/v3/api-docs`; Swagger UI is at `/swagger-ui.html`. Schemas describe API DTOs and document
bearer and refresh-cookie security. Set `SWAGGER_ENABLED=false` in production to disable both endpoints. Never put
tokens or backend secrets in Swagger examples or `VITE_*` variables.

## Commands

```bash
cd backend/backend && ./mvnw clean verify
cd frontend && npm ci && npm run lint && npm test -- --run && npm run build && npm audit --omit=dev
docker compose config
docker compose build backend frontend
docker compose up -d --wait
```

The frontend container selects HTTPS when the configured Let’s Encrypt certificate is mounted and otherwise
uses its local HTTP configuration. Production must mount the certificate or terminate TLS at a trusted reverse
proxy; the HTTP fallback is for local Compose use.

All provider and retention scheduling can be disabled for isolated environments with
`JOB_AGGREGATION_SCHEDULING_ENABLED=false`. Adzuna, Greenhouse, Lever, Ashby, SmartRecruiters, and Workable otherwise use their configured
fixed delays and shared Mongo-backed coordinator/lease paths.
