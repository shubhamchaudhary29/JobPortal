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

- [x] **M1A — Additive source-listing schema and safe backfill foundation**

  - Required behavior: add an additive per-listing model for identity, application URL, first/last seen, active state, and missing-run count; leave legacy documents readable; supply a non-destructive backfill/audit command.
  - Expected files/components: `backend/backend/src/main/java/.../job/infrastructure/JobDocument.java`, aggregation persistence, Mongo index initializer, `backend/backend/scripts/`, operations documentation.
  - Tests required: real Mongo persistence/backfill tests for legacy documents, imported documents, and recruiter-job isolation.
  - Verification commands: `cd backend/backend && ./mvnw -Dtest='*LifecycleSchema*,*JobStoreMongoIntegrationTest' test`; `mongosh "$MONGODB_URI" backend/backend/scripts/audit-mongo-indexes.js`.
  - Dependencies: frozen imported identity/index foundations.
  - Done when: additive fields persist/read safely, no document is deleted or ambiguously rewritten, backfill is repeatable, and focused Mongo tests pass.
  - Evidence (2026-08-16): `cd backend/backend && ./mvnw -Dtest='AdzunaJobStoreTest,AdzunaJobStoreMongoIntegrationTest' test` passed 7 tests (0 failures/errors/skips), including a deterministic forced duplicate-key retry that targets the persisted winner by `_id`, preserves the earliest listing `firstSeenAt`, refreshes listing state, and retains unrelated listings. `MONGODB_URI=mongodb://localhost:27017 bash backend/backend/scripts/verify-backfill-source-listings.sh` passed from the repository root and the same harness passed via its absolute path from `/tmp`; both runs asserted dry-run `changed: 0`, apply `changed: 1`, second apply `changed: 0`, recruiter isolation, and unchanged ambiguous records. `env MONGODB_URI=mongodb://localhost:27017/jobportal_m1a_audit bash -c 'mongosh "$MONGODB_URI" backend/backend/scripts/audit-mongo-indexes.js'` passed against seeded disposable data with zero duplicate source identities/fingerprints. `cd backend/backend && ./mvnw test` passed 72 tests (0 failures/errors/skips). `git diff --check` passed.

- [ ] **M1B — Deterministic canonical selection and atomic multi-source upsert**

  - Required behavior: choose primary source/external ID/application URL by a documented stable ordering and preserve all listing identities/URLs during atomic upsert.
  - Expected files/components: imported-job store, `JobDocument`, normalizer, index/audit support, operations documentation.
  - Tests required: real Mongo opposite-order, replay, same-source-change, cross-provider, concurrent insert, and duplicate-key race tests.
  - Verification commands: `cd backend/backend && ./mvnw -Dtest='*Canonical*,*JobStoreMongoIntegrationTest' test`.
  - Dependencies: M1A.
  - Done when: order-independent primary fields and idempotent multi-source persistence are demonstrated by focused Mongo tests.

- [ ] **M1C — Successful-run seen-set tracking, missing detection, deactivation and reactivation**

  - Required behavior: update listing seen/missing state only after complete successful runs; deactivate only when every listing is inactive; reactivate on rediscovery.
  - Expected files/components: aggregation service, lifecycle service, source-listing model, lifecycle configuration.
  - Tests required: successful miss, failed/locked/lease-lost protection, empty-board policy, multisource survival, threshold deactivation, and reactivation tests.
  - Verification commands: `cd backend/backend && ./mvnw -Dtest='*Lifecycle*,*EmployerIngestion*' test`.
  - Dependencies: M1A–M1B.
  - Done when: all lifecycle transitions are source-specific and every listed lifecycle test passes.

- [ ] **M1D — Retention cleanup with recruiter-job and application-reference protection**

  - Required behavior: run lock-coordinated cleanup of eligible inactive imported data only; never delete recruiter jobs or application-referenced jobs.
  - Expected files/components: cleanup service/scheduler, application repository query support, configuration, operations documentation.
  - Tests required: retention age, lock, recruiter, application-reference, and non-eligible imported-job tests.
  - Verification commands: `cd backend/backend && ./mvnw -Dtest='*Cleanup*,*Lifecycle*' test`.
  - Dependencies: M1C.
  - Done when: cleanup is bounded, reference-safe, and all cleanup tests pass.

- [ ] **M1E — Conflict persistence, ADMIN reconciliation API and reference-safe idempotent resolution**

  - Required behavior: persist identity/fingerprint conflicts without deletion; expose ADMIN listing/resolution; make resolution idempotent and preserve all job/application references.
  - Expected files/components: conflict document/repository/service, admin controller/DTOs, security/OpenAPI documentation.
  - Tests required: conflict persistence, `401/403/200`, idempotent resolution, and preserved reference tests.
  - Verification commands: `cd backend/backend && ./mvnw -Dtest='*Conflict*,*Admin*' test`.
  - Dependencies: M1A–M1D.
  - Done when: conflicts are durable and administratively resolvable without unsafe automatic deletion.

- [ ] **M1F — Complete lifecycle/conflict migration audit and real-Mongo end-to-end verification**

  - Required behavior: provide rollout evidence covering schema backfill, canonical rules, lifecycle, cleanup, and conflict reconciliation.
  - Expected files/components: migration/audit scripts, integration fixtures/tests, operations documentation.
  - Tests required: real Mongo end-to-end migration, lifecycle, conflict, and application-reference tests.
  - Verification commands: `cd backend/backend && ./mvnw -Dtest='*Lifecycle*,*Conflict*,*JobStoreMongoIntegrationTest' test`; `mongosh "$MONGODB_URI" backend/backend/scripts/audit-mongo-indexes.js`.
  - Dependencies: M1A–M1E.
  - Done when: migration audit and all end-to-end real-Mongo tests pass with documented evidence.

- [ ] **M2 — Sync history, manual operations, outcomes, and concurrency tests**

  - Required behavior: record every scheduled/manual/locked/lease-lost run with bounded sanitized details and counts; support provider-wide and configured-employer ADMIN sync using safe shared locks; return structured completed/partial/failed/locked/lease-lost outcomes.
  - Expected files/components: sync-run document/repository/service, coordinator extensions, admin controllers/DTOs, indexes, API/OpenAPI documentation.
  - Tests required: two-instance acquisition/contention/expiry/renewal/release tests; heartbeat exception/loss/cancellation tests; ADMIN `401/403/200`, provider/employer validation, lock contention, history pagination/filter/detail/last-status tests.
  - Verification commands: `cd backend/backend && ./mvnw -Dtest='*Coordinator*,*Lease*,*SyncHistory*,*Admin*' test`.
  - Dependencies: M1A–M1F supply lifecycle counts and conflict visibility.
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
  - Dependencies: M1A–M1F conflicts and M2 admin/history APIs.
  - Done when: ADMIN can complete all supported operations, non-admin users cannot see/access the page, and frontend checks pass.

- [ ] **M5 — Registry evidence, CI, Docker, documentation, and final audit**

  - Required behavior: run and record dated registry evidence (ACTIVE/EMPTY/INVALID/UNREACHABLE), disable/document invalid entries, expand CI and document migration/operations/configuration; verify Compose images and smoke behavior.
  - Expected files/components: registry evidence under `docs/`, validation script, `.github/workflows/verify.yml`, README, architecture/operations docs, `.env.example`, Docker Compose.
  - Tests required: script classification coverage; workflow-equivalent backend/frontend/Mongo/Docker/smoke checks.
  - Verification commands: `cd backend/backend && ./mvnw clean verify`; `cd frontend && npm ci && npm run lint && npm test -- --run && npm run build && npm audit --omit=dev`; `cd .. && docker compose config && docker compose build && git diff --check`; run registry validation when network access permits.
  - Dependencies: M1A–M1F and M2–M4 complete.
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
