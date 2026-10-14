# Map-First Job Discovery Plan (GEO-0 … GEO-7)

Goal: show job openings on a map, let candidates find openings near them and apply from there.

Constraints:
- **Zero paid API cost.** Open data and open-source software only.
- **Must not fall over as users grow.** Map browsing must not hit MongoDB per request.
- Additive changes only to existing entities (see engineering rules).

Phase prefix is `GEO-n` to avoid colliding with the aggregation milestones (M10–M16, M11A–C) and Phases 1–4 in `aggregation-completion-plan.md`.

---

## Free building blocks

| Need | Approach |
|---|---|
| Location string → place | GeoNames offline gazetteer (CC-BY 4.0) + alias file, in memory |
| Map tiles | Protomaps `.pmtiles` India extract, self-hosted |
| Map rendering | MapLibre GL JS, client-side clustering |
| User location | Browser Geolocation API; city picker fallback (from gazetteer) |
| Precise office pins | Admin clicks the map in the employer registry (GEO-6) |

Attribution required: © OpenStreetMap contributors, GeoNames (CC-BY 4.0).

---

## Key facts from the codebase that shape the design

1. **Jobs change outside syncs.** Recruiters create jobs manually; the cleanup cron runs at 02:30; `SyncRunService.begin(provider, employer, trigger)` creates one run per employer per provider on staggered schedules, plus manual admin runs. There is no single "sync finished" event.
2. **Jobs are written from many paths**, several of them server-side pipeline updates that bypass `BeforeConvertCallback`: `ImportedJobLifecycleService` (`updateMulti` with an aggregation pipeline), `AdzunaJobStore` (pipeline `findAndModify`), `AggregationConflictService`, `FingerprintMigrationService`, `JobFeatureStore` (bulk ops), cleanup, manual creation.
3. **Canonical `location` is last-writer-wins** (`EmployerIngestionService:169`). `ImportedSourceListing` stores no location.
4. **Locations are free text and can be multi-city** (`"Bengaluru / Mumbai"` appears in `JobFingerprintTest`). The most common ATS form is hierarchical: `"Bengaluru, Karnataka, India"`.
5. **Adzuna** returns `latitude`/`longitude` at the job level and `location.area[]` (e.g. `["India","Karnataka","Bangalore"]`); the current `AdzunaResponse` drops both. `AdzunaJobMapper` falls back to `"India"` when location is missing.
6. **Mongo is standalone** (no replica set) → no change streams.
7. **STOMP uses the in-memory simple broker** → a second backend instance breaks chat.
8. **Everything runs on one EC2 box** with no memory limits.

---

## Status

- [x] GEO-0 — merged (PR #14)
- [x] GEO-1 — offline GeoNames place resolution per listing, re-resolution job, admin coverage report (PR #TBD)
- [ ] GEO-2 … GEO-7

---

## GEO-0 — Memory limits (separate small commit, deploy today)

- `mem_limit` on `mongodb` and `backend` in `docker-compose.yml`, env-driven.
- Mongo: `--wiredTigerCacheSizeGB` env-driven.
- Backend `Dockerfile.dev` (this is the production build despite the name): add `-XX:MaxRAMPercentage=60` and `-XX:+ExitOnOutOfMemoryError` to `ENTRYPOINT` so the heap follows the container limit and an OOM restarts the container cleanly.

**Done when:** `docker stats` shows limits applied and the app runs normally under them.

---

## GEO-1 — Offline place resolution

### Gazetteer
- GeoNames India dataset filtered to populated places, shipped as a resource; loaded into an in-memory `Gazetteer` with `placeId`, name, admin1 (state), lat, lng, population.
- Alias file: `Bangalore→Bengaluru`, `Gurgaon→Gurugram`, `Bombay→Mumbai`, `Delhi NCR/NCR→Delhi`, etc.

### Parsing (two stages)
1. **Split candidates** only on `/`, `;`, `|`, and the word `or`. Never on commas.
2. **Within each candidate, commas are hierarchy.** Try the full string, then the leftmost segment; use a state segment only to disambiguate same-name places.
3. Remote detection → `REMOTE`. Country-only (`"India"`, including the `AdzunaJobMapper` fallback) → `COUNTRY`, no place.

Required test cases:
- `"Bengaluru, Karnataka, India"` → exactly one place (Bengaluru)
- `"Bengaluru / Mumbai"` → two places
- `"Bengaluru; Remote"` → one place + remote flag
- `"India"` → country-level, no place
- `"Bangalore"` → Bengaluru via alias

### Data model (additive)
- Value object `GeoResolution { status, placeIds[], remote, point?, version }`, status ∈ `PLACE | REMOTE | STATE | COUNTRY | FOREIGN | UNRESOLVED | EMPTY`.
- `ImportedSourceListing`: `rawLocation`, `rawLocationInferred`, `geo` (GeoResolution). `point` holds the provider's lat/lng (Adzuna) — stored, unindexed, for GEO-6.
- `JobDocument`: `geo` (GeoResolution) — **only for jobs without source listings (manual + legacy).** Plus a `@Transient locationHint` carrying Adzuna `area[]` and lat/lng into the store; never persisted.
- `version` = parser version + hash of the gazetteer/alias data files, so editing data re-resolves automatically without remembering to bump anything.
- Indexes: multikey on `sourceListings.geo.placeIds`, `geo.placeIds`, `sourceListings.geo.version`. **No 2dsphere until GEO-6.**

### Where resolution happens
- **Imported jobs:** `AdzunaJobStore.upsert()` — the single path for all providers — resolves when it builds the listing; `listingDocument()` writes `rawLocation` and `geo` wrapped in `$literal` (pipeline update). `AggregationConflictService.copy()` carries the new fields. Lifecycle uses `$mergeObjects`, so fields survive.
- **Manual jobs:** a `BeforeConvertCallback<JobDocument>` resolves top-level `geo` when the job has no source listings.
- A job's places = union of `geo.placeIds` over its **active** listings (or top-level `geo` for listing-less jobs), derived at read time. Never `$addToSet` placeIds.
- Known limit: the `AdzunaSearchProvider` path goes through `ExternalJob`, which carries no hint; it falls back to parsing the display string.

### Parsing details
- Split on `/ ; | &` and ` or ` (not ` and ` — "Jammu and Kashmir").
- Comma segments are hierarchy, except when ≥2 segments are each a major city (pop ≥ 300k, not a state) → list.
- Hierarchy: leftmost resolvable segment wins, moving right; never resolve to a state or country.
- Every non-first segment must be India/an Indian state (name or abbreviation)/a gazetteer place/a postal code; otherwise no place (prevents "Delhi, NY" → Delhi).
- Match order: alias file → primary names → alternate names (alternate-name matches need pop ≥ 50k). Ties: state filter, then highest population.

### Re-resolution
- Scheduled, lease-locked (`DistributedLeaseLock`), batched: active jobs whose listing/top-level `geo.version` ≠ current. Per-listing `arrayFilters` update that only touches listings still on an old version, so it never clobbers a fresh ingestion write.
- Listings without `rawLocation` are seeded from canonical `location` with `rawLocationInferred: true`; the next sync replaces it with the true per-source value.

### Visibility
- `unresolved_locations`: normalized string → observation count, sample, providers, first/last seen; TTL 90 days.
- `GET /api/v1/admin/geo/coverage` (ADMIN): active jobs by status, stale-version count, resolver version, top 50 unresolved.

**Gate:** report the geo-hit rate before any UI work. If most jobs are remote or country-only, the map is a secondary view, not the homepage.

---

## GEO-2 — Map snapshot

- Fixed schedule, every 5 minutes, regardless of what changed (no dirty flags — too many write paths to keep honest, no change streams on standalone Mongo).
- One aggregation: active jobs → unwind active `sourceListings.placeIds` (plus top-level `placeIds` for listing-less jobs) → group by `placeId` → count distinct jobs.
- Join coordinates/names from the gazetteer in memory.
- Hash the result; swap the in-memory snapshot and ETag only when the hash changes.
- `GET /api/v1/jobs/map/snapshot` → snapshot with `ETag` and `Cache-Control: public, max-age=300`.
- Browser loads it once; MapLibre clusters client-side; panning causes no server calls.

**Done when:** snapshot rebuilds on schedule, ETag is stable when nothing changed, and a load test shows snapshot traffic never reaches Mongo.

---

## GEO-3 — Place-based job list

- `GET /api/v1/jobs/nearby?lat&lng&radiusKm&q&page`: find places within radius from the in-memory snapshot → query `placeIds $in` (both listing-level with `active: true` and top-level) + existing text filter → sort by recency.
- `GET /api/v1/jobs/map/place/{placeId}?page` for marker clicks.
- Round incoming lat/lng to ~0.05° before use; never log precise coordinates.
- Short-TTL cache keyed by sorted place set + query + page.
- Caps: `radiusKm ≤ 100`, page size ≤ 20, `maxTimeMS`, projection without `description`. Invalid input → existing `BadRequestException`.

No geo query and no distance sort yet — with city-centre places they add nothing.

---

## GEO-4 — Self-hosted tiles

- `pmtiles extract` an India bounding box from the Protomaps build, max zoom 10–11.
- Mount the file as a volume into the nginx container (not baked into the image); serve with range requests and long cache headers.
- Style: **drop country and disputed boundary layers, and filter disputed-region place labels** (separate layer). Keep city labels and roads.
- Never use `tile.openstreetmap.org` in production.

---

## GEO-5 — Map page

- `/jobs/map`: MapLibre map + list panel; list/map toggle on mobile.
- "Use my location" via `navigator.geolocation`; on denial, city picker from the gazetteer.
- Marker → place panel → existing `JobDetails` → existing `ApplyJobModal`.
- City-level markers labelled as areas ("Bengaluru · 214 jobs"); office pins styled differently (GEO-6).
- "Remote & anywhere in India" tab for jobs without places.

---

## GEO-6 — Office pins and real distance

- Employer registry: `offices: [{label, lat, lng}]`, set by clicking the map in `EmployerRegistryAdmin`.
- Resolver prefers an office point when an employer has exactly one office in the resolved city; Adzuna `rawPoint` also becomes usable.
- Add `geoPoints[]` + 2dsphere index now, and `$geoNear` distance sorting for jobs with office precision.

---

## GEO-7 — Edge and hardening

- Own domain (Cloudflare cannot proxy `*.sslip.io`).
- Cloudflare free plan in front; nginx `real_ip_header CF-Connecting-IP` with `set_real_ip_from` for Cloudflare's published ranges.
- nginx `limit_req` on `/api/`, tighter on `/jobs/nearby`.
- Tiles to Cloudflare R2 + Worker (Protomaps' documented path) when bandwidth justifies it.
- k6 load tests for snapshot, nearby, and place endpoints.

---

## Scaling path (only when load demands it)

1. Move Mongo to its own instance.
2. Multiple backend instances — requires sticky sessions or an external STOMP broker for chat. The aggregation lease lock is already distributed.
3. Publish the snapshot as a static file to the CDN after each rebuild.

## Optional later

- Feed `placeIds` into `MATCHING_LOCATION_WEIGHT` for distance-aware matching.
