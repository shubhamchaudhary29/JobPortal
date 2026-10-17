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
| Precise office pins | Admin clicks the map in `/admin/offices` (GEO-6) |

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
- [x] GEO-1 — offline GeoNames place resolution per listing, re-resolution job, admin coverage report (PR #16)
- [x] GEO-2 — in-memory map snapshot endpoint with ETag/304 and pregzipped bodies (PR #17)
- [x] GEO-3 — map job lists (place, nearby, unplaced) with a snapshot-versioned cache, plus nginx upstream keepalive (PR #18)
- [x] GEO-4 — self-hosted India basemap (.pmtiles + fonts/sprites in a volume, served by nginx) and a MapLibre base map with no borders or country/state labels (PR #19)
- [x] GEO-5 — `/jobs/map`: clustered city markers, place/near-me/this-area job lists, Remote and Anywhere-in-India tabs, linked from the header and Jobs page (PR #20)
- [x] GEO-6 — foreign-only remote listings are `FOREIGN`; ADMIN office pins (audited, 60 km guard) applied at read time to the snapshot, office job lists and nearby; `/admin/offices` editor and office markers (PR #21)
- [x] GEO-7A — trust only nginx's `X-Real-IP` for client IPs (`shared.web.ClientIp`), backend port no longer published, Swagger off in Compose by default
- [x] GEO-7B — domain-templated nginx, `limit_req` rate limits with a JSON 429, security headers
- [x] GEO-7C — Cloudflare real IP (`CLOUDFLARE_PROXY`), Origin CA cert support, own-domain runbook (code and runbook done; **cutover pending**: domain purchase and DNS are manual)
- [x] GEO-7D — k6 capacity runs inside the Compose network and a rate-limit check through nginx
- [ ] GEO-7E — tiles to Cloudflare R2: trigger and target design (decision only)
- [x] GEO-7F — session resilience behind shared IPs: refresh limited per session, reuse grace window, separate nginx `refresh` zone, HTTP/2, one refresh across tabs, no logout on transient errors

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
- Refresh runs on its own executor, independent of `JOB_AGGREGATION_SCHEDULING_ENABLED`.
- `GET /api/v1/jobs/map/snapshot` → snapshot with `ETag` and `Cache-Control: public, max-age=300`.
- Browser loads it once; MapLibre clusters client-side; panning causes no server calls.

**Done when:** snapshot rebuilds on schedule, ETag is stable when nothing changed, and a load test shows snapshot traffic never reaches Mongo.

---

## GEO-3 — Place-based job list

- `GET /api/v1/jobs/map/nearby?lat&lng&radiusKm&q&page&size`: find places within radius from the in-memory snapshot → query `placeIds $in` (both listing-level with `active: true` and top-level) + existing text filter → sort by recency.
- `GET /api/v1/jobs/map/places/{placeId}/jobs?q&page&size` for marker clicks.
- `GET /api/v1/jobs/map/unplaced?kind=remote|india&q&page&size` for jobs without a place.
- Round incoming lat/lng to ~0.05° before use; never log precise coordinates.
- Short-TTL cache keyed by sorted place set + query + page.
- Caps: `radiusKm ≤ 100`, page size ≤ 20, `maxTimeMS`, projection without `description`. Invalid input → existing `BadRequestException`.

No geo query and no distance sort yet — with city-centre places they add nothing.

- nginx upstream keepalive (GEO-3A) fixes 502s under load.

---

## GEO-4 — Self-hosted tiles

- `pmtiles extract` an India bounding box from the Protomaps build, max zoom 10–11.
- Mount the file as a volume into the nginx container (not baked into the image); serve with range requests and long cache headers.
- Style: **drop country and disputed boundary layers, and filter disputed-region place labels** (separate layer). Keep city labels and roads.
- Never use `tile.openstreetmap.org` in production.
- Volume layout (`TILES_DIR`, default `./tiles`, mounted read-only at `/usr/share/nginx/tiles`): `manifest.json`
  (written last), `india-<YYYYMMDD>-z<maxzoom>.pmtiles` (current + previous), and
  `assets/<basemaps-assets commit>/{fonts,sprites}`. Built by `frontend/scripts/build-tiles.sh`.
- Size budget: 600 MB at z11 (the 2026-10-14 build is 276 MiB); the script warns above it and suggests `--maxzoom 10`.
- No boundary lines of any kind and no country or region/state labels; city labels, water, land use and roads stay.
- nginx serves `/tiles/` as static files with byte ranges (`.pmtiles` is not gzipped), immutable caching, and
  `no-cache` on `manifest.json`; missing files are 404, never the SPA's `index.html`.

---

## GEO-5 — Map page

- `/jobs/map`: MapLibre map + list panel; list/map toggle on mobile.
- "Use my location" via `navigator.geolocation`; on denial, city picker from the gazetteer.
- Marker → place panel → existing `JobDetails` → existing `ApplyJobModal`.
- City-level markers labelled as areas ("Bengaluru · 214 jobs"); office pins styled differently (GEO-6).
- "Remote & anywhere in India" tab for jobs without places.

**Done (PR #20):**
- The page is `/jobs/map` (lazy chunk), linked from the header ("Map") and the Jobs page ("View on map"). The
  **homepage is unchanged**: whether the map becomes the homepage is decided from production coverage numbers.
- The temporary GEO-4 `/map-preview` route redirects to `/jobs/map`; `MapPreview.jsx` is gone.
- Separate tabs: **Remote** (foreign-only remote listings are `FOREIGN` since GEO-6A) and
  **Anywhere in India** (STATE + COUNTRY).
- Privacy: the browser rounds user coordinates to the 0.05° grid before any request; they are never put in the URL,
  stored (no localStorage) or logged. Only `place`, `tab` and `q` are query params; the map view is in the hash,
  except while a near-me area is shown.
  Near-me and this-area searches live in memory only and reset to "Top places" on reload.
- Office pins stay GEO-6; markers are city-level areas.

---

## GEO-6 — Office pins and remote scope (done, PR #21)

**6A — remote scope.** A remote marker is scoped by its own location candidate. "Remote - United States",
"United States (Remote)" and similar foreign-only remote listings with no Indian place are `FOREIGN` (with
`remote: true`); "Remote" and "Remote - India" stay `REMOTE`; a place still wins. Parser version 2 re-resolves stored
listings automatically.

**6B — offices.** `company_offices` (one document per office) with an audited ADMIN API. Admins pin offices by
clicking the map; there is no geocoding. An office belongs to a gazetteer city (given, or the nearest city of 100k+
people) and must be within **60 km** of its centre (misclick guard). Offices match jobs from every provider by company
key (`shared.text.CompanyNames`, the fingerprint normalization moved out of `JobFingerprint` with byte-identical
output). A worklist ranks (company, city) pairs without an office by job count.

**Rule:** a job of company key `k` at place `p` belongs to an office only if exactly one enabled office has `k` and
`p`; none or several (ambiguous) → city level.

**6C — read-time assignment.** Offices are applied when the snapshot is built and when a job page is mapped, never
written into jobs: edits show after one snapshot rebuild (async, 5 s after a change; ≤ 5 min worst case) with no job
rewrites. The snapshot adds `places[].officeJobs` and `offices[]` without changing existing fields. New
`/api/v1/jobs/map/offices/{id}/jobs`; job places carry `officeId`/`officeLabel`; nearby includes office points and
measures distances to the office.

**6D — UI.** `/admin/offices` (worklist, click/drag pin editor, offices, audit) and office markers, panel, URL
`office=` and chips on `/jobs/map`.

**Deferred (decided):** no `2dsphere` index and no `$geoNear`. Job lists stay newest first; distances are shown per job
from the office or city centre. Revisit only if users ask for distance sorting. Adzuna `rawPoint` is still stored but
unused.

---

## GEO-7 — Edge and hardening

- Own domain (Cloudflare cannot proxy `*.sslip.io`).
- Cloudflare free plan in front; nginx `real_ip_header CF-Connecting-IP` with `set_real_ip_from` for Cloudflare's published ranges.
- nginx `limit_req` on `/api/`, tighter on `/jobs/nearby`.
- Tiles to Cloudflare R2 + Worker (Protomaps' documented path) when bandwidth justifies it.
- k6 load tests for snapshot, nearby, and place endpoints.

Rate limits and Cloudflare are pointless while the backend can be reached directly or trusts a client-supplied IP,
so GEO-7 starts by closing those bypasses.

**7A — close the bypasses (done).**
- The login/refresh limiter keyed on the *first* `X-Forwarded-For` entry, which the client controls (nginx's
  `$proxy_add_x_forwarded_for` appends to it). `shared.web.ClientIp.of(request)` now trusts only `X-Real-IP`, which
  nginx overwrites with `$remote_addr`, and only when it is an IPv4/IPv6 literal of at most 45 characters (validated
  without DNS); otherwise it uses the socket address. `X-Forwarded-For` is ignored. nginx now sends
  `X-Forwarded-For $remote_addr` instead of forwarding the client's chain.
- The backend is `expose`d on the Compose network only; `docker-compose.dev.yml` publishes `127.0.0.1:8080` for local
  debugging. The smoke test checks health from inside the network and that host port 8080 refuses connections.
- `SWAGGER_ENABLED` defaults to `false` in Compose (the properties default stays `true` for `./mvnw spring-boot:run`).

**7B — domain-agnostic nginx, rate limits, security headers (done).**
- `nginx.conf.template` is rendered at container start from `APP_SERVER_NAMES`, `TLS_CERT_PATH` and `TLS_KEY_PATH`
  (defaults derived from `APP_DOMAIN`, default the sslip.io host, so production renders exactly as before). Values that
  are not host names or absolute paths stop the container. All locations live in one include shared by the HTTPS and
  HTTP servers.
- `limit_req`: `api` 20 r/s (burst 40) on `/api/`; `map_list` 5 r/s (burst 10) on the nearby/place/office/unplaced
  lists; `auth` 10 r/min (burst 5) on login, refresh and registrations. The snapshot, tiles, static files and `/ws` are
  not throttled beyond that. 429s carry a ProblemDetail body (`code: RATE_LIMITED`); the map list and login show a
  friendly message.
- HSTS (no `includeSubDomains`), `nosniff`, `Referrer-Policy`, `X-Frame-Options: DENY`, `Permissions-Policy`
  (geolocation self only), and a report-only CSP, on every response.

**7C — Cloudflare and own domain (code and runbook done; cutover pending).**
- `frontend/cloudflare-realip.conf` (generated by `frontend/scripts/update-cloudflare-ips.sh` from Cloudflare's
  published lists, fetch date in the header) is included in both servers only when `CLOUDFLARE_PROXY=true`. nginx then
  takes `$remote_addr` from `CF-Connecting-IP` for requests from Cloudflare ranges only, so `limit_req` and `ClientIp`
  see the real visitor. Proven locally: a forged header from the Docker network is ignored.
- Origin CA certificates: `TLS_DIR` (default `/etc/letsencrypt`) is mounted read-only at the same path and
  `TLS_CERT_PATH`/`TLS_KEY_PATH` point inside it. Defaults leave the sslip.io deployment unchanged.
- Runbook: `docs/geo-operations.md` → "Own domain and Cloudflare (GEO-7C)".

**7D — load tests that fit the limits (done).** `map-snapshot.js` and `map-jobs.js` now default to
`http://backend:8080` and run inside the Compose network; `map-jobs.js` has a 5% office leg (seed offices with
`seed-map-jobs.py --offices N`). `rate-limits.js` goes through nginx and asserts nearby is throttled while the snapshot
and tiles are not. 2026-10-17 laptop results (200 VUs, GEO-0 memory limits): snapshot 13k req/s p95 34 ms, map lists
10.8k req/s p95 34 ms, 0% errors, backend peak 513 MiB, Mongo 250 MiB. Table in `docs/geo-operations.md`.

**7E — tiles to Cloudflare R2 (decision only; not built).** Ships "when bandwidth justifies it".

*Why not just Cloudflare's proxy:* `.pmtiles` is not a default cached extension and the map reads it with byte-range
requests, so behind the orange cloud every tile read still leaves EC2 (GEO-7C runbook, step 7).

*Trigger:* `/tiles/` egress from EC2 above **50 GB/month** (half of AWS's 100 GB/month free data transfer out, which the
API, SPA and snapshot also use), or tiles above half of total egress. Measure it for a week:

1. `/tiles/` has `access_log off` (MapLibre makes many range requests per pan). For the measurement only, remove that
   line from `frontend/jobportal-locations.conf`, rebuild the frontend and deploy; restore it afterwards.
2. After 7 days (container logs survive restarts but not `docker compose down`; record the start time):

   ```bash
   docker compose logs --no-log-prefix --since 168h frontend \
     | awk '$7 ~ /^\/tiles\// { bytes += $10 } END { printf "%.2f GB/week, %.1f GB/month est.\n", bytes/1e9, bytes/1e9*30/7 }'
   ```

   Fields are nginx's default `combined` format (`$7` path, `$10` body bytes sent). Compare with the instance's
   `NetworkOut` in CloudWatch for the same week.

*Target design (when triggered):*
- Upload the current `.pmtiles` and `assets/<ref>/` to an R2 bucket from `build-tiles.sh` (new `--r2-bucket` option
  via `rclone` or `wrangler r2 object put`); `manifest.json` is uploaded last, as today.
- Deploy Protomaps' documented Cloudflare Worker (`serverless/cloudflare` in protomaps/PMTiles), bound to the bucket,
  on a route such as `tiles.<domain>/*`. It serves individual `/{z}/{x}/{y}.mvt` tiles with cache headers, so
  Cloudflare's CDN caches them and EC2 serves no tile bytes. R2 has no egress fees.
- Frontend: `VITE_TILES_URL` (default `/tiles`) replaces the hard-coded `TILES_BASE` in `src/map/tilesManifest.js`;
  the style switches from the `pmtiles://` protocol to the Worker's tile URL template when the variable is set. The
  manifest validation (single path segments) stays; CORS on the Worker allows only `https://<domain>`.
- CSP: add the tiles origin to `connect-src` (and `img-src` for sprites) before the policy is promoted to enforcing.
- Rollback: unset `VITE_TILES_URL` and rebuild; nginx `/tiles/` keeps serving the volume.
- Cost guard (zero paid cost): free R2 is 10 GB storage and 10 M reads/month; the free Workers plan allows
  **100,000 requests/day**. CDN-cached tiles do not invoke the Worker, but a cold cache does; check Worker request
  counts for the first week and keep the nginx path as the fallback if the free plan would be exceeded.

**7F — session resilience behind shared IPs.** Students reach us through campus Wi-Fi and Jio/Airtel carrier-grade
NAT, so many users share one public IP. After GEO-7 two bugs logged real users out:
1. Refresh (called on every page load and new tab) was limited per IP: the backend login limiter (5/min, never reset
   on success) plus nginx's `auth` zone. The SPA cleared the session on *any* refresh error, so one user reloading six
   times, or ten students on one Wi-Fi, were logged out.
2. Two tabs refreshing with the same cookie: the second saw an already-rotated token, treated it as theft and revoked
   the whole token family.

*7F-1 backend.* Refresh is limited by a separate limiter keyed `refresh:<first 16 hex of SHA-256(token)>`, 20/min
(`REFRESH_RATE_LIMIT_*`). Because every refresh rotates the token, this caps replays of one cookie (a tab storm before
the first rotation lands); IP floods are nginx's job. A missing cookie is 401 before the limiter. Login keeps IP + email,
5/min. Reusing a token that was *rotated* (has `replacedByHash`) at most `REFRESH_REUSE_GRACE_SECONDS` (30) ago is a 401
without revoking the family; a later replay, or reuse of a logged-out or expired token, still revokes it. An optimistic-lock
conflict (two rotations racing) is a plain 401.

*7F-2 nginx.* `auth` (login, registrations) is now 30 r/min, burst 20; refresh has its own `refresh` zone, 120 r/min,
burst 60. Logout stays in `api`. The HTTPS server has `http2 on` (tiles' parallel range requests share a connection).

*7F-3 frontend (`services/helper.js`, `user-service.js`, `auth-store.js`, `AuthContext.jsx`).*
- Tabs refresh one at a time under the Web Lock `jobportal-auth-refresh` (with the in-tab promise dedupe kept, and
  used alone when `navigator.locks` is missing). After a refresh the tab broadcasts the new access token on
  `BroadcastChannel("jobportal-auth")`; a tab that receives one while waiting for the lock uses it and sends nothing.
  Logout broadcasts `{type: "logout"}` and every tab clears. Tokens stay in memory; nothing is written to web storage.
- The session is cleared only on 401/403. A 429 is retried twice after `Retry-After` (clamped 1–10 s, default 2); a
  network error or 5xx rejects without clearing. On page load a transient failure marks the session `unknown`: the
  header shows logged out and protected pages offer Retry instead of redirecting to login. A non-401 refresh failure in
  the 401 interceptor reaches the UI as that error (e.g. the rate-limit message), not a silent logout.
- Found by the browser check, not in the original plan:
  - A reload while a refresh was in flight dropped the response that carried the rotated cookie (nginx 499), leaving
    the browser with a token the server had already rotated: logged out. The refresh now uses a `keepalive` fetch, so
    the browser still stores the new cookie after the page unloads. Because the unloading tab releases the lock before
    that response lands, a tab that had to wait for another tab's lock and then gets a 401 retries once after 1 s
    (still inside the 30 s reuse grace, so nothing is revoked). Single-tab and anonymous loads never retry.
  - `AuthProvider` copied the store into React state and subscribed in an effect, so a session set between the first
    render and the subscription (a token broadcast while the tab was loading) never reached the UI. It now uses
    `useSyncExternalStore`.
- Manual check (headless Chrome against the Compose stack, run three times): 8 tabs of `/jobs/map` reloaded twice
  each stay logged in (24 page loads, 2 refresh requests: the rest came by broadcast); a job opened in a new tab while
  the map tab reloads keeps both logged in (24/24 races); five reloads with the refresh on the wire stay logged in;
  signing out in one tab logs out the other nine without a reload.

---

## Scaling path (only when load demands it)

1. Move Mongo to its own instance.
2. Multiple backend instances — requires sticky sessions or an external STOMP broker for chat. The aggregation lease lock is already distributed.
3. Publish the snapshot as a static file to the CDN after each rebuild.

## Follow-ups

- Investigate Adzuna `"India"`-only listings (31% COUNTRY in the GEO-1 local preview).

## Optional later

- Feed `placeIds` into `MATCHING_LOCATION_WEIGHT` for distance-aware matching.
