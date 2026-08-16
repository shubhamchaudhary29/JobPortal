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

- [x] **M1B — Deterministic canonical selection and atomic multi-source upsert**

  - Required behavior: choose primary source/external ID/application URL by a documented stable ordering and preserve all listing identities/URLs during atomic upsert.
  - Expected files/components: imported-job store, `JobDocument`, normalizer, index/audit support, operations documentation.
  - Tests required: real Mongo opposite-order, replay, same-source-change, cross-provider, concurrent insert, and duplicate-key race tests.
  - Verification commands: `cd backend/backend && ./mvnw -Dtest='*Canonical*,*JobStoreMongoIntegrationTest' test`.
  - Dependencies: M1A.
  - Done when: order-independent primary fields and idempotent multi-source persistence are demonstrated by focused Mongo tests.
  - Evidence (2026-08-16): `cd backend/backend && ./mvnw -Dtest='*Canonical*,*JobStoreMongoIntegrationTest' test` passed 10 real-Mongo tests (0 failures/errors/skips), covering opposite provider order, lexical same-provider tie-breaking, non-primary replay, changed fingerprints, 20-way concurrent cross-provider ingestion, same-source concurrency, and a forced duplicate-key winner. The Mongo aggregation update atomically de-duplicates and sorts source listings, preserves earliest listing timestamps and every deep link, and derives canonical content/source/link only from the stable primary listing. `cd backend/backend && ./mvnw test` passed 77 tests (0 failures/errors/skips). `git diff --check` passed.

- [x] **M1C — Successful-run seen-set tracking, missing detection, deactivation and reactivation**

  - Required behavior: update listing seen/missing state only after complete successful runs; deactivate only when every listing is inactive; reactivate on rediscovery.
  - Expected files/components: aggregation service, lifecycle service, source-listing model, lifecycle configuration.
  - Tests required: successful miss, failed/locked/lease-lost protection, empty-board policy, multisource survival, threshold deactivation, and reactivation tests.
  - Verification commands: `cd backend/backend && ./mvnw -Dtest='*Lifecycle*,*EmployerIngestion*' test`.
  - Dependencies: M1A–M1B.
  - Done when: all lifecycle transitions are source-specific and every listed lifecycle test passes.
  - Evidence (2026-08-16): `cd backend/backend && ./mvnw -Dtest='*Lifecycle*,*EmployerIngestion*,AdzunaServiceTest' test` passed 14 tests (0 failures/errors/skips), including real-Mongo successful misses, threshold deactivation, rediscovery reactivation, multi-source survival/canonical switching, seen-set reset, recruiter isolation, legitimate empty boards, provider failure, rejected/partial items, lock contention, and employer/Adzuna lease-loss protection. Missing state advances through one source/employer-scoped Mongo update pipeline only after a complete lease-valid run; `JOB_AGGREGATION_MISSING_THRESHOLD` is validated and defaults to 3. `cd backend/backend && ./mvnw test` passed 85 tests (0 failures/errors/skips). `git diff --check` passed.

- [x] **M1D — Retention cleanup with recruiter-job and application-reference protection**

  - Required behavior: run lock-coordinated cleanup of eligible inactive imported data only; never delete recruiter jobs or application-referenced jobs.
  - Expected files/components: cleanup service/scheduler, application repository query support, configuration, operations documentation.
  - Tests required: retention age, lock, recruiter, application-reference, and non-eligible imported-job tests.
  - Verification commands: `cd backend/backend && ./mvnw -Dtest='*Cleanup*,*Lifecycle*' test`.
  - Dependencies: M1C.
  - Done when: cleanup is bounded, reference-safe, and all cleanup tests pass.
  - Evidence (2026-08-16): `cd backend/backend && ./mvnw -Dtest='*Cleanup*,*Lifecycle*' test` passed 8 tests (0 failures/errors/skips), including real-Mongo retention age, deterministic batch bounds, recruiter/active/recent/undated protection, retained application references, and distributed lock contention/owner release. Cleanup uses the indexed imported/inactive/`inactiveAt` eligibility query, checks `applications.jobId`, and conditionally rechecks eligibility at deletion. `cd backend/backend && ./mvnw test` passed 89 tests (0 failures/errors/skips). `git diff --check` passed.

- [x] **M1E — Conflict persistence, ADMIN reconciliation API and reference-safe idempotent resolution**

  - Required behavior: persist identity/fingerprint conflicts without deletion; expose ADMIN listing/resolution; make resolution idempotent and preserve all job/application references.
  - Expected files/components: conflict document/repository/service, admin controller/DTOs, security/OpenAPI documentation.
  - Tests required: conflict persistence, `401/403/200`, idempotent resolution, and preserved reference tests.
  - Verification commands: `cd backend/backend && ./mvnw -Dtest='*Conflict*,*Admin*' test`.
  - Dependencies: M1A–M1D.
  - Done when: conflicts are durable and administratively resolvable without unsafe automatic deletion.
  - Evidence (2026-08-16): `cd backend/backend && ./mvnw -Dtest='*Conflict*,*Admin*' test` passed 7 tests (0 failures/errors/skips), including real-Mongo conflict coalescing, bounded/filterable pagination, ADMIN `401/403/200`, explicit resolution, same-candidate ambiguity refusal without mutation, application/conversation reference rewrites, duplicate removal only after rewrites, completed replay idempotence, and recovery from a persisted mid-flight reconciliation marker. `cd backend/backend && ./mvnw test` initially found and then verified the fix for a shared/integration architecture cycle; the clean rerun passed 96 tests (0 failures/errors/skips). `git diff --check` passed.

- [x] **M1F — Complete lifecycle/conflict migration audit and real-Mongo end-to-end verification**

  - Required behavior: provide rollout evidence covering schema backfill, canonical rules, lifecycle, cleanup, and conflict reconciliation.
  - Expected files/components: migration/audit scripts, integration fixtures/tests, operations documentation.
  - Tests required: real Mongo end-to-end migration, lifecycle, conflict, and application-reference tests.
  - Verification commands: `cd backend/backend && ./mvnw -Dtest='*Lifecycle*,*Conflict*,*JobStoreMongoIntegrationTest' test`; `mongosh "$MONGODB_URI" backend/backend/scripts/audit-mongo-indexes.js`.
  - Dependencies: M1A–M1E.
  - Done when: migration audit and all end-to-end real-Mongo tests pass with documented evidence.
  - Evidence (2026-08-16): `cd backend/backend && ./mvnw -Dtest='*Lifecycle*,*Conflict*,*JobStoreMongoIntegrationTest,*MigrationEndToEnd*' test` passed 22 tests (0 failures/errors/skips). The combined real-Mongo acceptance flow verified deterministic multi-source canonical state, per-provider misses, all-source deactivation, application-reference cleanup protection, reactivation, durable conflict resolution, reference reassignment, and duplicate removal. `MONGODB_URI=mongodb://localhost:27017 bash backend/backend/scripts/verify-aggregation-migration.sh` passed against its disposable database, accepting a structurally valid rollout and returning status `2` with every expected category for intentionally malformed listing, duplicate-identity, canonical, and lifecycle anomalies. `cd backend/backend && MONGODB_URI=mongodb://localhost:27017 bash scripts/verify-aggregation-migration.sh && ./mvnw test` passed the harness again and all 97 backend tests (0 failures/errors/skips). `git diff --check` passed.

- [x] **M2A — Persistent sync-run schema, indexes, and recording for every outcome**

  - Required behavior: persist one bounded, sanitized sync-run record for scheduled and manual executions, including source/employer, trigger, run ID, timestamps, structured outcome, all ingestion/lifecycle counts, retry count, and safe failure details; record completed, partial, failed, locked, and lease-lost outcomes and retain records by configured TTL.
  - Expected files/components: `integration/aggregation` sync-run document/service/DTOs, ingestion coordinators, Mongo index initializer, configuration and operations documentation.
  - Tests required: unit and real-Mongo tests for every outcome, count mapping, bounded failure sanitization, TTL/query indexes, and persistence when ingestion throws.
  - Verification commands: `cd backend/backend && ./mvnw -Dtest='*SyncRun*,*IngestionCoordinator*,AdzunaServiceTest' test`; `cd backend/backend && ./mvnw test`; `git diff --check`.
  - Dependencies: M1A–M1F.
  - Done when: every existing coordinator exit path creates exactly one durable, indexed, retention-controlled run record and focused/full backend tests pass.
  - Evidence (2026-08-16): `cd backend/backend && ./mvnw -Dtest='*SyncRun*,*IngestionCoordinator*,AdzunaServiceTest' test` passed 10 tests (0 failures/errors/skips), including real-Mongo records for completed, partial, failed, locked, and lease-lost exits from employer and Adzuna coordinators; scheduled/manual triggers; run IDs; ingestion, retry, and lifecycle counts; bounded URL/credential sanitization; duplicate completion protection; and scope/outcome/TTL indexes. A deterministic thrown-ingestion case remained durably `FAILED`. `cd backend/backend && ./mvnw test` passed 100 tests (0 failures/errors/skips). `git diff --check` passed.

- [ ] **M2B — Paginated/filterable ADMIN history, detail, and last-status APIs**

  - Required behavior: expose ADMIN-only bounded history pagination and filtering by provider/employer/outcome/trigger, stable detail lookup, and latest status/provider-company counts without exposing unsanitized data.
  - Expected files/components: admin ingestion controller/DTOs, sync-run query service, security/OpenAPI and operations documentation.
  - Tests required: `401/403/200`, page/size bounds, valid and invalid filters, stable sorting, detail not-found, latest status, counts, and response sanitization tests.
  - Verification commands: `cd backend/backend && ./mvnw -Dtest='*SyncHistory*,*Admin*' test`; `cd backend/backend && ./mvnw test`; `git diff --check`.
  - Dependencies: M2A.
  - Done when: secured bounded ADMIN history/detail/status queries return deterministic documented responses and all tests pass.

- [ ] **M2C — Provider-wide and employer-specific manual synchronization**

  - Required behavior: support ADMIN provider-wide sync and optional configured-employer Greenhouse/Lever sync while preserving provider-wide behavior; validate provider/employer combinations and route scheduled/manual execution through the same coordinator and distributed lock.
  - Expected files/components: employer registry lookup, provider coordinators/schedulers, admin controller/DTOs, configuration/OpenAPI documentation.
  - Tests required: provider-wide and employer-specific success, unknown/disabled employer, unsupported Adzuna employer, scheduled/manual path equivalence, and lock-key tests.
  - Verification commands: `cd backend/backend && ./mvnw -Dtest='*EmployerIngestion*,*ManualSync*,*Admin*' test`; `cd backend/backend && ./mvnw test`; `git diff --check`.
  - Dependencies: M2A–M2B.
  - Done when: every supported manual scope uses the identical scheduled coordinator/lock path and validation/response tests pass.

- [ ] **M2D — Shared locking, structured outcomes, and complete concurrency/security tests**

  - Required behavior: finalize provider-neutral outcomes and prove two-instance exclusion, renewal, heartbeat exception/loss, cancellation, ownership-safe release, expiry recovery, and no post-loss writes/progress for every scheduled/manual provider path.
  - Expected files/components: lease manager/heartbeat/coordinator, structured outcome DTOs, provider schedulers, deterministic concurrency test fixtures, operations documentation.
  - Tests required: deterministic long-run renewal, Mongo renewal failure/exception, ownership theft, expiry recovery, two-instance overlap, cancellation/release ownership, locked/conflict HTTP mapping, and `401/403/200` coverage.
  - Verification commands: `cd backend/backend && ./mvnw -Dtest='*Coordinator*,*Lease*,*Concurrency*,*Admin*' test`; `cd backend/backend && ./mvnw test`; `git diff --check`.
  - Dependencies: M2A–M2C.
  - Done when: all execution paths share safe distributed coordination, lease loss stops writes immediately, structured outcomes are consistent, and every concurrency/security test passes.

- [ ] **M3 — Greenhouse/Lever reliability and secured observability**

  - Required behavior: provider-neutral transient failure handling; configured timeouts, retry/backoff/jitter and `Retry-After`; payload limits; employer-isolated circuit/rate control; sanitized logs; secured metrics for outcomes, operation counts, durations, retries, errors, contention, and lease loss.
  - Expected files/components: shared reliability package, provider clients/configuration, Micrometer/Actuator configuration, metrics recorder, environment/Docker documentation.
  - Tests required: deterministic mock-server tests for timeout, 429, 5xx, permanent 4xx, malformed/oversized/empty payloads, exhaustion, recovery, and isolation; metric and endpoint-security tests.
  - Verification commands: `cd backend/backend && ./mvnw -Dtest='*Greenhouse*,*Lever*,*Reliability*,*Metrics*' test`.
  - Dependencies: M2A–M2D run outcomes/history provide bounded metric semantics.
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
