# Aggregation completion plan

This document is the durable implementation and acceptance source of truth for the Phase 2/3 job-aggregation work. Work only the first unchecked milestone. Update its evidence and checkbox before committing.

## Frozen foundations

The following behavior is frozen: preserve it and avoid unrelated rewrites. Minimal extensions needed by a milestone remain allowed.

- Existing Adzuna cached ingestion.
- Mongo-only public job search.
- Typed Greenhouse/Lever adapters and normalization.
- Fingerprints, source identities, and application-URL preservation.
- Existing Mongo indexes and duplicate audit.
- Employer registry and independent schedules.
- Distributed leases, heartbeats, and cancellation checks.
- Adzuna lease configuration and lease-loss response.
- Existing security and Docker configuration.

## Remaining requirements

The remaining scope is per-source lifecycle and safe migration; deterministic canonical selection and conflict reconciliation; durable sync history and employer-specific operations; provider reliability and observability; an ADMIN frontend; registry evidence; CI, Docker, and final operational documentation.

- [ ] **M1 — Lifecycle, canonical selection, conflicts, and migration**

  - Required behavior: persist source-listing state (identity, URL, first/last seen, active/missing count); deactivate imported canonical jobs only when all listings are inactive; reactivate on rediscovery; protect recruiter and application-referenced jobs; select canonical source/link deterministically independent of provider order; persist identity/fingerprint conflicts; provide idempotent, reference-safe reconciliation and documented backfill.
  - Expected files/components: `JobDocument`, imported-job store, aggregation service, lifecycle/cleanup service and scheduler, conflict document/repository/service, migration/audit scripts, operations documentation.
  - Tests required: real Mongo tests for misses, partial/failed runs, multisource survival, reactivation, retention, recruiter/reference protection, opposite provider order, concurrent inserts/races, conflicts, reconciliation, and preserved application references.
  - Verification commands: `cd backend/backend && ./mvnw -Dtest='*Lifecycle*,*JobStoreMongoIntegrationTest,*Conflict*' test`; `mongosh "$MONGODB_URI" backend/backend/scripts/audit-mongo-indexes.js`.
  - Dependencies: frozen imported identity/index foundations.
  - Done when: listing lifecycle is authoritative, no ambiguous document is deleted automatically, canonical links are deterministic, reconciliation is idempotent and reference-safe, and all listed Mongo tests pass.

- [ ] **M2 — Sync history, manual operations, outcomes, and concurrency tests**

  - Required behavior: record every scheduled/manual/locked/lease-lost run with bounded sanitized details and counts; support provider-wide and configured-employer ADMIN sync using safe shared locks; return structured completed/partial/failed/locked/lease-lost outcomes.
  - Expected files/components: sync-run document/repository/service, coordinator extensions, admin controllers/DTOs, indexes, API/OpenAPI documentation.
  - Tests required: two-instance acquisition/contention/expiry/renewal/release tests; heartbeat exception/loss/cancellation tests; ADMIN `401/403/200`, provider/employer validation, lock contention, history pagination/filter/detail/last-status tests.
  - Verification commands: `cd backend/backend && ./mvnw -Dtest='*Coordinator*,*Lease*,*SyncHistory*,*Admin*' test`.
  - Dependencies: M1 supplies lifecycle counts and conflict visibility.
  - Done when: every execution has durable history, all manual paths share locks, bounded history queries work, and concurrency/security tests pass.

- [ ] **M3 — Greenhouse/Lever reliability and secured observability**

  - Required behavior: provider-neutral transient failure handling; configured timeouts, retry/backoff/jitter and `Retry-After`; payload limits; employer-isolated circuit/rate control; sanitized logs; secured metrics for outcomes, operation counts, durations, retries, errors, contention, and lease loss.
  - Expected files/components: shared reliability package, provider clients/configuration, Micrometer/Actuator configuration, metrics recorder, environment/Docker documentation.
  - Tests required: deterministic mock-server tests for timeout, 429, 5xx, permanent 4xx, malformed/oversized/empty payloads, exhaustion, recovery, and isolation; metric and endpoint-security tests.
  - Verification commands: `cd backend/backend && ./mvnw -Dtest='*Greenhouse*,*Lever*,*Reliability*,*Metrics*' test`.
  - Dependencies: M2 run outcomes/history provide bounded metric semantics.
  - Done when: no live provider calls occur in tests, transient-only retry/circuit behavior is proven, and operational metrics are secured and low-cardinality.

- [ ] **M4 — ADMIN frontend and frontend tests**

  - Required behavior: ADMIN-only aggregation page with provider status/counts, last outcomes, paginated history/detail, provider/employer controls, conflict reconciliation, accessible loading/empty/error/locked/partial/failed/lease-lost states, and duplicate-submit protection.
  - Expected files/components: React route/navigation guard, page/components, aggregation API service, API/component tests.
  - Tests required: API and component tests for all page states/actions plus unauthenticated/forbidden handling and hidden navigation for non-admins.
  - Verification commands: `cd frontend && npm ci && npm run lint && npm test -- --run && npm run build`.
  - Dependencies: M1 conflicts and M2 admin/history APIs.
  - Done when: ADMIN can complete all supported operations, non-admin users cannot see/access the page, and frontend checks pass.

- [ ] **M5 — Registry evidence, CI, Docker, documentation, and final audit**

  - Required behavior: run and record dated registry evidence (ACTIVE/EMPTY/INVALID/UNREACHABLE), disable/document invalid entries, expand CI and document migration/operations/configuration; verify Compose images and smoke behavior.
  - Expected files/components: registry evidence under `docs/`, validation script, `.github/workflows/verify.yml`, README, architecture/operations docs, `.env.example`, Docker Compose.
  - Tests required: script classification coverage; workflow-equivalent backend/frontend/Mongo/Docker/smoke checks.
  - Verification commands: `cd backend/backend && ./mvnw clean verify`; `cd frontend && npm ci && npm run lint && npm test -- --run && npm run build && npm audit --omit=dev`; `cd .. && docker compose config && docker compose build && git diff --check`; run registry validation when network access permits.
  - Dependencies: M1–M4 complete.
  - Done when: evidence is dated and truthful, all repository checks and practical smoke tests pass, GitHub Actions are green, PR description is accurate, and the final acceptance audit has no unchecked requirements.

## Release checklist

- [ ] `./mvnw clean verify` passes, including real Mongo integration tests.
- [ ] Frontend clean install, lint, tests, audit, and production build pass.
- [ ] Mongo duplicate/index audit passes against seeded validation data.
- [ ] Docker Compose config, builds, and practical backend/frontend/Mongo smoke tests pass.
- [ ] Registry validation evidence is recorded or its external blocker is documented.
- [ ] `git diff --check` and secret scanning pass.
- [ ] GitHub Actions succeeds on the pushed branch.
- [ ] PR description documents migrations/configuration/results and contains no premature completion claim.
