# Backend architecture and API v1

## Modular structure

The backend is a feature-oriented modular monolith under `com.example.backend`:

```text
auth/{api,application,infrastructure}
user/{api,application,domain,infrastructure}
job/{api,application,infrastructure}
application/{api,application,domain,infrastructure}
messaging/{api,application,infrastructure,security}
integration/adzuna
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

Aggregation administration is exposed only below `/api/v1/admin/ingestion`: summaries and manual provider runs are joined by paginated conflict listing and explicit conflict resolution. Reconciliation requests name both the retained canonical job ID and duplicate job ID; the backend preserves and rewrites application/conversation references before any duplicate removal.

The STOMP/SockJS handshake remains at `/ws`, with messages published to `/app/chat.send`. Its request property
is `conversationId`.

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
`DUPLICATE_RESOURCE`, `RATE_LIMITED`, `BAD_REQUEST`, and `INTERNAL_ERROR`. Unexpected details are logged only as
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

## OpenAPI

OpenAPI JSON is at `/v3/api-docs`; Swagger UI is at `/swagger-ui.html`. Schemas describe API DTOs and document
bearer and refresh-cookie security. Set `SWAGGER_ENABLED=false` in production to disable both endpoints. Never put
tokens or backend secrets in Swagger examples or `VITE_*` variables.

## Commands

```bash
cd backend/backend && ./mvnw clean verify
cd frontend && npm ci && npm run lint && npm test && npm run build
docker compose config
docker compose build backend frontend
docker compose up -d --wait
```

The frontend container selects HTTPS when the configured Let’s Encrypt certificate is mounted and otherwise
uses its local HTTP configuration. Production must mount the certificate or terminate TLS at a trusted reverse
proxy; the HTTP fallback is for local Compose use.

Adzuna scheduling can be disabled for isolated environments with `app.scheduling.enabled=false`. Phase 3 owns
retry/backoff, ingestion observability, and broader query-index optimization.
