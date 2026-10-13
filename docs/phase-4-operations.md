# Phase 4 operations: source expansion

Phase 4 adds ATS providers, India employer coverage, cross-provider de-duplication, a shared search-API
framework with request budgets, and a Mongo-backed employer registry. Everything from
[`phase-3-operations.md`](phase-3-operations.md) still applies; detailed settings live there and in
[`architecture.md`](architecture.md). Provider usage rules, limits, and open questions are in
[`provider-terms.md`](provider-terms.md).

## Providers at a glance

| Provider | Kind | Default state | Schedule | Request limits we apply |
| --- | --- | --- | --- | --- |
| Adzuna | search API | on (needs `ADZUNA_APP_ID`/`ADZUNA_APP_KEY`) | 12 h | 2,000/month budget (retries count), about 33/run, 2.5 s spacing |
| Jooble | search API | off until `JOOBLE_API_KEY` | 24 h | 100/month budget; the free key has 500 requests in total |
| Greenhouse, Lever | ATS | registry entries | 6 h each | 5 req/s per board, one request per board per run |
| Ashby | ATS | registry entries | 6 h | same |
| SmartRecruiters | ATS | registry entries | 6 h | list pages ≤ 20, ≤ 100 uncached details per board run |
| Workable | ATS | code ready, no boards enabled | 6 h | same as other ATS; terms unverified |
| Careerjet | — | not integrated | — | blocked by API terms (real end-user IP/user agent required) |

The ADMIN page lists every catalog provider and disables search providers that are not configured.

## Rollout order

1. **Back up** `jobs`, `aggregation_conflicts`, `applications`, and `chat_rooms`.
2. **Deploy.** Startup verifies indexes (including the `employer_registry` unique indexes and the
   `provider_request_budgets` and SmartRecruiters detail-cache TTL indexes), then imports any properties registry
   entries missing from `employer_registry`. New writes use fingerprint version 2.
3. **Migrate fingerprints.** From the repository root with the backend environment set:
   `bash backend/backend/scripts/migrate-fingerprints.sh` (dry-run), review the report, then
   `bash backend/backend/scripts/migrate-fingerprints.sh --apply`.
4. **Resolve conflicts.** Collisions that the new normalization exposes (for example a Bangalore and a Bengaluru
   copy of one job) are `FINGERPRINT_MIGRATION` conflicts on the ADMIN aggregation page. Resolve them, then rerun
   `--apply` until it reports no planned updates.
5. **Audit.** `mongosh "$MONGODB_URI" backend/backend/scripts/audit-mongo-indexes.js` must report no
   `fingerprintVersionMismatches` (application-held jobs are informational) and exit `0`.
6. **Validate boards.** `bash backend/backend/scripts/validate-employer-registry.sh` for the seed, or "Validate" on
   individual entries in `/admin/aggregation/registry`.
7. **Optional providers.** Read `provider-terms.md`, then set `JOOBLE_API_KEY` (an `in.jooble.org` key) and its
   budget, or confirm Workable's terms before enabling Workable boards.

Rollback: restore the backup. Version-1 fingerprints are not needed by this release. Disabling a provider does
not delete its jobs; they age out through the normal lifecycle and retention.

## Budgets, rotation, and lifecycle

- `provider_request_budgets` keeps one counter per provider and UTC month. A run that would exceed the budget
  stops before the next request and is recorded as `PARTIAL` with reason `BUDGET_EXHAUSTED`.
- Adzuna's plan (12 keywords × (country-wide + 10 cities) × 2 pages = 264 requests) is larger than one run, so it
  rotates through `search_query_rotation`; a full cycle takes about 4 days.
- With rotation, missing-listing detection runs once per complete, failure-free cycle using every identity seen
  in that cycle, so a job is never marked missing just because its query was not part of a run. With the default
  threshold of 3, a vanished Adzuna job is hidden after about 12 days.
- Manual ADMIN runs share the same budget; keep them occasional.

## Employer registry

- Manage boards at `/admin/aggregation/registry` (API: `/api/v1/admin/ingestion/registry`). Each change is audited
  in `employer_registry_audit` with the administrator and before/after values.
- Properties entries are only imported when missing; editing or removing them later does not change Mongo.
- A company may have only one enabled board; disable the old board before enabling a new one.
- SmartRecruiters returns an empty list for unknown company identifiers, so confirm a new identifier on
  `careers.smartrecruiters.com/{identifier}` before trusting an `EMPTY` validation.

## Attribution

Aggregator listings keep the provider's link as the application URL and show a "Jobs by Adzuna" or
"Jobs by Jooble" link (at least 116 × 23 px for Adzuna, as its terms require) on job cards and job details.
