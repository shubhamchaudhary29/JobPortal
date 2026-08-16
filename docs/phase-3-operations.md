# Phase 3: Adzuna reliability and MongoDB operations

## Runtime behavior

The public `GET /api/v1/jobs` API reads only from MongoDB. It never waits for a live Adzuna request. Trigger an import explicitly with `POST /api/v1/jobs/ingestion/adzuna` using a recruiter JWT; there is no periodic scheduler.

`ADZUNA_APP_ID` and `ADZUNA_APP_KEY` are required server environment variables. They are only used to create the outbound Adzuna request and are never logged or returned in errors. Do not place either value in source code, frontend configuration, screenshots, or issue comments.

Configuration defaults (all can be overridden through the matching environment variable):

- `ADZUNA_CONNECT_TIMEOUT_MS=3000`, `ADZUNA_READ_TIMEOUT_MS=5000`
- `ADZUNA_RETRY_MAX_ATTEMPTS=3` (bounded to 1–5) and `ADZUNA_RETRY_INITIAL_BACKOFF_MS=200`
- `ADZUNA_CIRCUIT_FAILURE_THRESHOLD=3`, `ADZUNA_CIRCUIT_OPEN_MS=60000`
- `ADZUNA_PAGES_PER_KEYWORD=2` (1–10), `ADZUNA_RESULTS_PER_PAGE=20` (1–50), and `ADZUNA_KEYWORDS`

Network failures, 429s, and 5xx responses receive bounded exponential backoff with jitter. 4xx authentication/validation responses and malformed provider payloads are not retried. Repeated failed batches open a small in-process circuit; after its open interval one successful probe closes it. Completion logs contain sanitized event names, counts, and latency only; the lightweight counters report cumulative successful runs, failed batches, and total latency.

Every provider record is validated and atomically upserted using `(source, externalId)`. Imported jobs carry `source`, `externalId`, `fetchedAt`, and `lastSeenAt`. A failed or partial sync does not delete or alter previously imported jobs. The current stale-data policy is **retain and label by `lastSeenAt`**: operators should query/report jobs not seen within their business freshness window before any separately approved cleanup. The manual sync endpoint is authenticated and recruiter-only.

## Indexes and query rationale

The startup verifier creates the job indexes below after rejecting legacy duplicate `(source, externalId)` values. It fails startup loudly rather than silently ignoring an unsafe unique-index migration.

| Index | Supporting query |
| --- | --- |
| `source_external_id_unique` (partial unique) | atomic Adzuna upsert and replay deduplication |
| `jobs_created_at_idx` | default public job listing sort |
| `jobs_source_created_at_idx` | public source-filtered listing, newest first |
| `jobs_recruiter_created_at_idx` | recruiter `/api/v1/jobs/mine`, newest first |
| `jobs_search_text_idx` | public tokenized title/description/company search; avoids user-controlled regex |
| `candidate_job_unique`, `job_applied_at_idx`, `candidate_applied_at_idx` | application duplicate protection plus recruiter/candidate pages |
| `candidate_last_message_idx`, `recruiter_last_message_idx` | conversation pages |
| `room_sent_at_idx`, `room_unread_idx` | message pages and unread lookups |

Indexes consume storage and make writes slower. These are limited to current repository query paths; do not add speculative sort indexes. MongoDB pagination remains database-side. Location filtering uses a bounded, quoted, anchored case-insensitive pattern, so it cannot execute user regex syntax; its case-insensitive form is not index-friendly and should be replaced with a normalized location field only through a separately backfilled migration.

## Production migration

1. Back up the database and run `mongosh "$MONGODB_URI" backend/backend/scripts/audit-mongo-indexes.js` from the repository root. Keep credentials in environment variables only.
2. Resolve every reported duplicate before deploying. Do not merely disable `MONGO_INDEX_VERIFY_ON_STARTUP` to bypass it.
3. Deploy once with `MONGO_INDEX_VERIFY_ON_STARTUP=true`; startup verifies duplicates and ensures the indexes.
4. Monitor the sanitized `adzuna_sync_completed`, `adzuna_batch_failed`, and `adzuna_sync_skipped` events and review `lastSeenAt` before any approved stale-job cleanup.

## Local and Docker checks

Backend unit/contract tests do not call Adzuna: `cd backend/backend && ./mvnw test`.

For Compose, create a local ignored `.env` with placeholders for `MONGODB_URI`, `JWT_SECRET`, `ADZUNA_APP_ID`, `ADZUNA_APP_KEY`, and `CORS_ALLOWED_ORIGINS`, then run `docker compose config` followed by `docker compose build`. Do not commit `.env` files. Docker image creation exercises the wrapper build inside the backend image; a live Adzuna sync still requires valid provider credentials and is not part of automated verification.
## Employer ingestion operations

### Source-listing backfill (M1A)

Imported jobs now retain additive `sourceListings` entries. Before lifecycle work is enabled, inspect legacy data without mutation:

```bash
mongosh "$MONGODB_URI" backend/backend/scripts/backfill-source-listings.js
```

Apply only after reviewing the reported candidates and ambiguous records:

```bash
BACKFILL_APPLY=true mongosh "$MONGODB_URI" backend/backend/scripts/backfill-source-listings.js
```

The script is idempotent and does not delete, merge, or guess ambiguous documents. Back up the `jobs` collection before applying; rollback consists of restoring that backup or manually removing only the reviewed additive `sourceListings` fields. Recruiter-created jobs are excluded.

Public job search reads MongoDB only; it never invokes Greenhouse, Lever, or Adzuna.
Greenhouse and Lever are independently scheduled every six hours by default and can be globally disabled with `JOB_AGGREGATION_SCHEDULING_ENABLED=false` (the test profile does this).

Imported records use a provider-independent SHA-256 fingerprint of normalized company, title, and location. The original provider identity and each original application deep link are retained on the canonical record. Apply the `imported_fingerprint_unique` index only after running the read-only audit:

```bash
mongosh "$MONGODB_URI" backend/backend/scripts/audit-mongo-indexes.js
```

Do not automatically delete ambiguous duplicate records; resolve them before enabling the unique index in a production rollout. Mongo lease locks in `ingestion_locks` prevent scheduled instances from overlapping and expire after five minutes, allowing crash recovery.

Canonical imported-job fields follow one stable policy: active source listings are ordered by provider name, then full source identity, then application URL, all ascending. The first listing owns the top-level `source`, `externalId`, `applicationUrl`, and `sourceUrl`; every identity and deep link remains in the additive listing and compatibility collections. Mongo applies listing replacement, de-duplication, ordering, and primary-field selection in one update pipeline, so provider arrival order and concurrent ingestion cannot change the selected application link.

Per-listing missing state advances only after a complete, lease-valid source/employer fetch. Provider errors, rejected or failed items, lock contention, and lease loss skip missing detection; a genuinely empty successful board is a valid seen set and does advance it. `JOB_AGGREGATION_MISSING_THRESHOLD` defaults to `3` consecutive successful misses. A canonical job is hidden only after every source listing is inactive, and any later successful upsert reactivates that listing and the canonical job while preserving its original first-seen timestamp.

The daily retention task uses the distributed `maintenance:imported-job-retention` lease and defaults to `02:30`, 90 days after deactivation, and batches of 100 (`JOB_AGGREGATION_CLEANUP_CRON`, `JOB_AGGREGATION_RETENTION_DAYS`, and `JOB_AGGREGATION_CLEANUP_BATCH_SIZE`). It selects only inactive imported jobs with an old `inactiveAt`, rechecks eligibility at removal, and preserves every job referenced by an application. Recruiter-created, active, recent, undated, and application-referenced jobs are never cleanup candidates. Operators should take a database backup before lowering retention and can disable all maintenance scheduling with `JOB_AGGREGATION_SCHEDULING_ENABLED=false`.

`/api/v1/admin/ingestion/**` is ADMIN-only. Public registration can create only USER or RECRUITER accounts. Provision an administrator through a controlled database migration by changing an existing trusted user's `role` to `ADMIN`, then have that user sign in again to receive a role-bearing token.
