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

- [x] **M2B — Paginated/filterable ADMIN history, detail, and last-status APIs**

  - Required behavior: expose ADMIN-only bounded history pagination and filtering by provider/employer/outcome/trigger, stable detail lookup, and latest status/provider-company counts without exposing unsanitized data.
  - Expected files/components: admin ingestion controller/DTOs, sync-run query service, security/OpenAPI and operations documentation.
  - Tests required: `401/403/200`, page/size bounds, valid and invalid filters, stable sorting, detail not-found, latest status, counts, and response sanitization tests.
  - Verification commands: `cd backend/backend && ./mvnw -Dtest='*SyncHistory*,*Admin*' test`; `cd backend/backend && ./mvnw test`; `git diff --check`.
  - Dependencies: M2A.
  - Done when: secured bounded ADMIN history/detail/status queries return deterministic documented responses and all tests pass.
  - Evidence (2026-08-16): `cd backend/backend && ./mvnw -Dtest='*SyncHistory*,*Admin*' test` passed 7 tests (0 failures/errors/skips), covering ADMIN `401/403/200`, bounded pagination, all filters and invalid values, deterministic tie ordering, detail/not-found, latest-per-scope status, provider/company per-listing counts, recruiter exclusion, and defensive response sanitization. History responses omit TTL metadata and retain only bounded failure fields. `cd backend/backend && ./mvnw test` passed 105 tests (0 failures/errors/skips). `git diff --check` passed.

- [x] **M2C — Provider-wide and employer-specific manual synchronization**

  - Required behavior: support ADMIN provider-wide sync and optional configured-employer Greenhouse/Lever sync while preserving provider-wide behavior; validate provider/employer combinations and route scheduled/manual execution through the same coordinator and distributed lock.
  - Expected files/components: employer registry lookup, provider coordinators/schedulers, admin controller/DTOs, configuration/OpenAPI documentation.
  - Tests required: provider-wide and employer-specific success, unknown/disabled employer, unsupported Adzuna employer, scheduled/manual path equivalence, and lock-key tests.
  - Verification commands: `cd backend/backend && ./mvnw -Dtest='*EmployerIngestion*,*ManualSync*,*Admin*' test`; `cd backend/backend && ./mvnw test`; `git diff --check`.
  - Dependencies: M2A–M2B.
  - Done when: every supported manual scope uses the identical scheduled coordinator/lock path and validation/response tests pass.
  - Evidence (2026-08-16): `cd backend/backend && ./mvnw -Dtest='*EmployerIngestion*,*ManualSync*,*Admin*' test` passed 13 tests (0 failures/errors/skips), covering ADMIN `401/403/200`, provider-wide Adzuna/Greenhouse/Lever routing, configured employer-only selection, disabled/unknown/unsafe employer rejection, Adzuna employer rejection, scheduled/manual trigger attribution, and identical provider lock keys for broad and narrow runs. Locked requests return HTTP `409` with their durable run ID. `cd backend/backend && ./mvnw test` initially caught a provider-wide validation regression before lock acquisition; after narrowing validation to employer-scoped calls, the clean rerun passed 110 tests (0 failures/errors/skips). `git diff --check` passed.

- [x] **M2D — Shared locking, structured outcomes, and complete concurrency/security tests**

  - Required behavior: finalize provider-neutral outcomes and prove two-instance exclusion, renewal, heartbeat exception/loss, cancellation, ownership-safe release, expiry recovery, and no post-loss writes/progress for every scheduled/manual provider path.
  - Expected files/components: lease manager/heartbeat/coordinator, structured outcome DTOs, provider schedulers, deterministic concurrency test fixtures, operations documentation.
  - Tests required: deterministic long-run renewal, Mongo renewal failure/exception, ownership theft, expiry recovery, two-instance overlap, cancellation/release ownership, locked/conflict HTTP mapping, and `401/403/200` coverage.
  - Verification commands: `cd backend/backend && ./mvnw -Dtest='*Coordinator*,*Lease*,*Concurrency*,*Admin*' test`; `cd backend/backend && ./mvnw test`; `git diff --check`.
  - Dependencies: M2A–M2C.
  - Done when: all execution paths share safe distributed coordination, lease loss stops writes immediately, structured outcomes are consistent, and every concurrency/security test passes.
  - Evidence (2026-08-16): `cd backend/backend && ./mvnw -Dtest='*Coordinator*,*Lease*,*Concurrency*,*Admin*,EmployerIngestionServiceTest,AdzunaServiceTest' test` passed 30 tests (0 failures/errors/skips). Deterministic unit and real-Mongo cases cover two-instance overlap, acquisition/contention, long-run renewal, false renewal, Mongo renewal exception, ownership theft, expiry recovery, former-owner release safety, heartbeat scheduling failure, idempotent cancellation, and ADMIN `401/403/200/409/503`. Explicit provider tests prove lease loss after one stored item prevents later writes/lifecycle progress and lease loss during a transient Adzuna failure prevents retries/provider progress. Coordinator responses and history share the same low-cardinality outcomes. `cd backend/backend && ./mvnw test` passed 118 tests (0 failures/errors/skips). `git diff --check` passed.

- [x] **M3A — Configurable timeouts, transient retries, backoff/jitter, and `Retry-After`**

  - Required behavior: give Greenhouse and Lever a shared provider-neutral failure model, bounded configurable connect/read timeouts and retry attempts, exponential backoff with injectable jitter, HTTP-date/seconds `Retry-After`, and transient-only retry classification for timeout/429/5xx; permanent 4xx and malformed responses must not retry.
  - Expected files/components: shared reliability package, Greenhouse/Lever HTTP sources and configuration, environment/Compose forwarding, operations documentation.
  - Tests required: deterministic local mock-HTTP-server tests for success, connect/read timeout, 429 with both `Retry-After` forms, 5xx recovery/exhaustion, permanent 4xx, malformed response, retry bounds, and interruption; no live provider calls.
  - Verification commands: `cd backend/backend && ./mvnw -Dtest='*Greenhouse*,*Lever*,*Reliability*' test`; `cd backend/backend && ./mvnw test`; `git diff --check`.
  - Dependencies: M2A–M2D.
  - Done when: both providers use the same deterministic transient-only retry policy, all knobs are validated/documented, and focused/full backend tests pass without live calls.
  - Evidence (2026-08-16): Greenhouse and Lever now share provider-neutral failures, a dedicated JDK HTTP transport, validated connect/read timeout and attempt/backoff bounds, transient-only classification, bounded exponential jitter, both `Retry-After` formats, interrupt restoration, and sync-history retry propagation. All reliability tests use a local mock HTTP server. `cd backend/backend && ./mvnw -Dtest='*Greenhouse*,*Lever*,*Reliability*' test` passed 6 tests (0 failures/errors/skips). The expanded focused run `./mvnw -Dtest='*Greenhouse*,*Lever*,*Reliability*,EmployerIngestionServiceTest' test` passed 13 tests (0 failures/errors/skips), including retry-count propagation. `cd backend/backend && ./mvnw test` passed 125 tests (0 failures/errors/skips). `git diff --check` passed.

- [x] **M3B — Circuit protection, request/payload limits, employer isolation, and sanitized logging**

  - Required behavior: apply bounded provider/employer request-rate limiting and payload/item limits, employer-keyed circuit breakers with half-open recovery, malformed-item isolation, and sanitized structured logs that never expose URLs, credentials, or raw payloads.
  - Expected files/components: shared reliability state/rate limiter, provider sources, employer ingestion service, configuration/Compose, operations documentation.
  - Tests required: deterministic circuit open/recovery and employer-isolation tests; rate-limit/bounds tests; oversized, malformed-item, malformed-payload, and legitimate-empty payload tests; log-capture redaction tests using local mock servers only.
  - Verification commands: `cd backend/backend && ./mvnw -Dtest='*Greenhouse*,*Lever*,*Reliability*,*Payload*,*SanitizedLog*' test`; `cd backend/backend && ./mvnw test`; `git diff --check`.
  - Dependencies: M3A.
  - Done when: one employer cannot exhaust or open another employer's controls, all payload/rate bounds and redaction tests pass, and no automated test calls a live provider.
  - Evidence (2026-08-16): Greenhouse/Lever requests now use provider/board-scoped bounded pacing and circuit state with deterministic half-open recovery. Declared and chunked response bytes and item counts are bounded; oversized payloads are permanent failures. Null/malformed items are isolated and counted while valid siblings proceed, and the partial result cannot advance missing detection. Failure logs contain only controlled provider/board fields. `cd backend/backend && ./mvnw -Dtest='*Greenhouse*,*Lever*,*Reliability*,*Payload*,*SanitizedLog*' test` passed 11 deterministic tests (0 failures/errors/skips) using local HTTP servers only. The expanded focused run including `EmployerIngestionServiceTest` passed 19 tests (0 failures/errors/skips). `cd backend/backend && ./mvnw test` passed 131 tests (0 failures/errors/skips). `git diff --check` passed.

- [x] **M3C — Secured Actuator/Micrometer observability**

  - Required behavior: expose secured health/metrics for run outcomes, operation counts, duration, retries, errors, contention, and lease loss using only provider/outcome/trigger tags from fixed allowlists; do not use employer, run ID, URL, exception text, or other high-cardinality labels.
  - Expected files/components: Actuator/Micrometer dependencies/configuration, aggregation metrics recorder, coordinators/reliability hooks, security rules, environment and operations documentation.
  - Tests required: metric counter/timer/tag assertions, forbidden-tag regression tests, endpoint `401/403/200`, health sanitization, and disabled/exposure configuration tests.
  - Verification commands: `cd backend/backend && ./mvnw -Dtest='*Metrics*,*Actuator*,*Observability*' test`; `cd backend/backend && ./mvnw test`; `git diff --check`.
  - Dependencies: M3A–M3B.
  - Done when: operational metrics are accurate, secured, low-cardinality, documented, and all focused/full backend tests pass.
  - Evidence (2026-08-16): finalized durable sync runs emit Micrometer counters and a duration timer for outcomes, operations, retries, errors, contention, lease loss, and lifecycle work. Every aggregation meter is limited to fixed `provider`, `outcome`, and `trigger` tags; tests reject employer, run ID, URL, exception, or other labels. Actuator exposes only health/info/metrics, requires ADMIN for every path, suppresses health components/details, and omits env/config endpoints. `cd backend/backend && ./mvnw -Dtest='*Metrics*,*Actuator*,*Observability*' test` passed 5 tests (0 failures/errors/skips), including endpoint `401/403/200`, durable-finalization ordering, and exposure/tag assertions. `cd backend/backend && ./mvnw test` passed 136 tests (0 failures/errors/skips). `git diff --check` passed.

- [x] **M4 — ADMIN frontend and frontend tests**

  - Required behavior: ADMIN-only aggregation page with provider status/counts, last outcomes, paginated history/detail, provider/employer controls, conflict reconciliation, accessible loading/empty/error/locked/partial/failed/lease-lost states, and duplicate-submit protection.
  - Expected files/components: React route/navigation guard, page/components, aggregation API service, API/component tests.
  - Tests required: API and component tests for all page states/actions plus unauthenticated/forbidden handling and hidden navigation for non-admins.
  - Verification commands: `cd frontend && npm ci && npm run lint && npm test -- --run && npm run build`.
  - Dependencies: M1A–M1F conflicts and M2A–M2D admin/history APIs.
  - Done when: ADMIN can complete all supported operations, non-admin users cannot see/access the page, and frontend checks pass.
  - Evidence (2026-08-16): `/admin/aggregation` is protected by the ADMIN route guard and appears only in ADMIN navigation/login flow. The accessible operations page shows imported/provider/company counts, latest health, paginated history and detail, bounded failure counts, provider-wide/employer sync controls, and explicit loading/empty/error/PARTIAL/FAILED/LOCKED/LEASE_LOST states. Open conflicts require an explicit distinct canonical/duplicate choice; sync and resolution buttons prevent duplicate submission. API tests cover every ADMIN route and safe `401/403/409/503` messaging; component tests cover page states/actions, pagination/detail, conflict resolution, employer scope, duplicate suppression, route redirects, and hidden navigation. `cd frontend && npm ci && npm run lint && npm test -- --run && npm run build` passed: clean install audited 351 packages with 0 vulnerabilities, ESLint passed, Vitest passed 24 tests across 8 files, and Vite built 210 modules successfully. `git diff --check` passed.

- [x] **M5 — Registry evidence, CI, Docker, documentation, and final audit**

  - Required behavior: run and record dated registry evidence (ACTIVE/EMPTY/INVALID/UNREACHABLE), disable/document invalid entries, expand CI and document migration/operations/configuration; verify Compose images and smoke behavior.
  - Expected files/components: registry evidence under `docs/`, validation script, `.github/workflows/verify.yml`, README, architecture/operations docs, `.env.example`, Docker Compose.
  - Tests required: script classification coverage; workflow-equivalent backend/frontend/Mongo/Docker/smoke checks.
  - Verification commands: `cd backend/backend && ./mvnw clean verify`; `cd frontend && npm ci && npm run lint && npm test -- --run && npm run build && npm audit --omit=dev`; `cd .. && docker compose config && docker compose build && git diff --check`; run registry validation when network access permits.
  - Dependencies: M1A–M1F, M2A–M2D, M3A–M3C, and M4 complete.
  - Done when: evidence is dated and truthful, all repository checks and practical smoke tests pass, GitHub Actions are green, PR description is accurate, and the final acceptance audit has no unchecked requirements.
  - Evidence (2026-08-16): `cd backend/backend && ./mvnw clean verify` passed 136 tests (0 failures/errors/skips), including the real-Mongo integration suite; `./mvnw -Dtest='*Reliability*,*Payload*,*SanitizedLog*' test` passed 11 deterministic mock-provider tests. `cd frontend && npm ci && npm run lint && npm test -- --run && npm run build && npm audit --omit=dev` passed with 24 tests across 8 files, 210 built modules, and 0 production vulnerabilities. `MONGODB_URI=mongodb://localhost:27017 bash backend/backend/scripts/verify-backfill-source-listings.sh` and `MONGODB_URI=mongodb://localhost:27017 bash backend/backend/scripts/verify-aggregation-migration.sh` passed against disposable seeded databases, covering dry-run/apply/idempotence and clean/anomalous audit results. `bash backend/backend/scripts/verify-employer-registry-classification.sh` passed from the repository and by absolute path from `/tmp`, covering ACTIVE, EMPTY, MALFORMED, INVALID, UNREACHABLE, and DISABLED. Live read-only registry verification at `2026-08-16T17:04:46Z` exited 0 with 18 ACTIVE Greenhouse and 2 ACTIVE Lever boards and zero other classifications; exact board counts are recorded in `docs/employer-registry-verification.md`. With test-only environment values, `docker compose config` produced a valid 153-line model and `docker compose build` built backend/frontend images. The first smoke attempt exposed an IPv6 `localhost` frontend-health probe; after targeting `127.0.0.1`, `bash backend/backend/scripts/compose-smoke-test.sh` passed MongoDB/backend/frontend health, direct/proxied API health, ADMIN SPA routing, and disposable cleanup. `git diff --check` and Markdown/shell syntax checks passed. GitHub CI initially exposed the unavailable runner-only `rg` prerequisite; after replacing it with direct `awk` parsing, both [push Verify](https://github.com/shubhamchaudhary29/JobPortal/actions/runs/31961133878) and [PR Verify](https://github.com/shubhamchaudhary29/JobPortal/actions/runs/31961135544) passed backend, frontend, registry, Compose build, smoke, and whitespace jobs, while both [push Secret scan](https://github.com/shubhamchaudhary29/JobPortal/actions/runs/31961133873) and [PR Secret scan](https://github.com/shubhamchaudhary29/JobPortal/actions/runs/31961135575) passed. PR #8's description was replaced with the completed scope, migrations, configuration, rollback precautions, exact results, and CI links.

## Release checklist

- [x] `./mvnw clean verify` passes, including real Mongo integration tests.
- [x] Frontend clean install, lint, tests, audit, and production build pass.
- [x] Mongo duplicate/index audit passes against seeded validation data.
- [x] Docker Compose config, builds, and practical backend/frontend/Mongo smoke tests pass.
- [x] Registry validation evidence is recorded or its external blocker is documented.
- [x] `git diff --check` and secret scanning pass.
- [x] GitHub Actions succeeds on the pushed branch.
- [x] PR description documents migrations/configuration/results and contains no premature completion claim.
