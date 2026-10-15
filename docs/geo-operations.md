# Geo operations (GEO-1)

Job locations are resolved offline against a GeoNames gazetteer bundled with the backend. There are no
runtime geocoding API calls. Each imported listing stores its own `rawLocation` and `geo`; jobs without
listings (manual and legacy) store a top-level `geo`.

## Data files

All under `backend/backend/src/main/resources/geo/`:

| File | Maintained by | Purpose |
|---|---|---|
| `in-places.tsv`, `in-admin1.tsv` | `scripts/build-gazetteer.py` | GeoNames populated places and state codes for India |
| `place-aliases.tsv` | hand | Old or alternate spellings → gazetteer place (`Bangalore` → `Bengaluru`) |
| `state-aliases.tsv` | hand | State/UT names and abbreviations → admin1 code |
| `countries.tsv` | hand | India markers and foreign countries/regions |

### Regenerate the gazetteer

```bash
cd backend/backend
python3 scripts/build-gazetteer.py
```

It downloads `cities500.zip` and `admin1CodesASCII.txt` from GeoNames and rewrites the two TSVs. The output
is deterministic, so the diff shows only real GeoNames changes. Run `./mvnw test -Dtest=GazetteerDataTest`
afterwards: every alias must still resolve to exactly one place.

### Add an alias

1. Add a row to `place-aliases.tsv`: `alias<TAB>target name as spelled in in-places.tsv<TAB>admin1`.
   Always give the admin1 code when the target name exists in more than one state.
2. Run `GazetteerDataTest` and commit.
3. Deploy. Editing any data file changes the resolver version (`GeoResolver.PARSER_VERSION` + data hash),
   so every listing on the old version is re-resolved automatically on the next scheduled run.

## Re-resolution job

A lease-locked (`geo-reresolution`) background job re-resolves active jobs whose `geo.version` is not
current. It runs on the global scheduler, so it only runs when `JOB_AGGREGATION_SCHEDULING_ENABLED=true`.

| Variable | Default | Meaning |
|---|---|---|
| `GEO_RERESOLUTION_ENABLED` | `true` | Turn the job off without touching other schedulers |
| `GEO_RERESOLUTION_DELAY_MS` | `600000` | Delay between runs (first run starts at boot) |
| `GEO_RERESOLUTION_BATCH_SIZE` | `200` | Jobs per batch |
| `GEO_RERESOLUTION_MAX_BATCHES` | `25` | Batches per run; leftovers wait for the next run |

Listings imported before GEO-1 have no `rawLocation`. The job seeds it from the canonical `location` and
sets `rawLocationInferred: true`; the next sync of that listing replaces it with the provider's own value.

Each run logs one line:

```
event=geo_reresolution_completed scanned=… updated=… remaining_estimate=… duration_ms=…
```

## Coverage report

`GET /api/v1/admin/geo/coverage` (ADMIN, cached 60 s):

- `byStatus`: active jobs by their best status over **active** listings
  (PLACE > REMOTE > STATE > COUNTRY > FOREIGN > UNRESOLVED > EMPTY); `UNPROCESSED` = not resolved yet.
- `placeResolvedPercent`: share of active jobs with at least one place. This is the GEO-2+ gate.
- `staleJobs`: active jobs still on an old resolver version. It should fall to 0 after re-resolution.
- `topUnresolved`: the 50 most frequent strings the resolver could not place (from `unresolved_locations`,
  kept 90 days after last sighting). These are the best candidates for new aliases.

## Map snapshot

`GET /api/v1/jobs/map/snapshot` (public) returns every place with visible jobs and its count, plus
`unplaced` counts by status (REMOTE, COUNTRY, …, UNPROCESSED) and `placedJobs` (distinct jobs with at least
one place). A job's places are the union over its **active** listings, the same definition the coverage
report uses; each job counts once per place. Reconciliation duplicates and inactive jobs are excluded.

It is built by one aggregation and served from memory; requests never reach MongoDB.

| Header | Value |
|---|---|
| `ETag` | `"<version>"`: a hash of the content, unchanged while the data is unchanged |
| `Cache-Control` | `public, max-age=60, stale-while-revalidate=240` |
| `Vary` | `Accept-Encoding` |
| `Content-Encoding` | `gzip` when the client accepts it (precomputed once per change) |

Send `If-None-Match` with the last ETag to get `304 Not Modified`.

| Variable | Default | Meaning |
|---|---|---|
| `GEO_SNAPSHOT_ENABLED` | `true` | Build and refresh the snapshot |
| `GEO_SNAPSHOT_REFRESH_MS` | `300000` | Delay between rebuilds (first build at startup) |

The refresh runs on its own executor, so it keeps running when `JOB_AGGREGATION_SCHEDULING_ENABLED=false`.

- **Force a rebuild:** restart the backend; the first build runs as soon as it is ready.
- **503 with `Retry-After: 10`:** no snapshot has been built yet (just after startup, or every build since
  startup failed). After one good build, a failed rebuild keeps serving the previous snapshot and logs
  `event=geo_snapshot_build_failed`.
- **`event=geo_snapshot_unknown_places dropped_jobs=N`:** N jobs only had place ids that are no longer in the
  gazetteer (usually after a data refresh). They disappear from the map until re-resolution catches up.

Metrics: `jobportal.geo.snapshot.places`, `jobportal.geo.snapshot.placed.jobs`,
`jobportal.geo.snapshot.age.seconds` (gauges), `jobportal.geo.snapshot.build` (timer),
`jobportal.geo.snapshot.build.failures` (counter).

## Map job lists

All anonymous `GET`s; responses never include descriptions, application URLs or internal ids.

| Endpoint | Returns | `Cache-Control` |
|---|---|---|
| `/api/v1/jobs/map/places/{placeId}/jobs` | Jobs at one place (404 if the place is not in the gazetteer) | `public, max-age=60` |
| `/api/v1/jobs/map/nearby?lat&lng&radiusKm` | Nearest 50 places within the radius + their jobs | `private, max-age=60` |
| `/api/v1/jobs/map/unplaced?kind=remote\|india` | Jobs with no place: REMOTE, or STATE/COUNTRY | `public, max-age=60` |

Common parameters: `q` (optional phrase search, max 100 chars; quotes are ignored), `page` 0–49 (default 0),
`size` 1–20 (default 20). Paging is slice-based (`hasNext`, no count query). Jobs are newest first.
`totalHint` is the snapshot's count when `q` is absent, otherwise `null` (always `null` for nearby).

- **Job definition:** the same as the snapshot (`GeoPipelines`): visible jobs; places from active listings, or
  the top-level geo of jobs without listings.
- **Nearby:** `lat`/`lng` are rounded to a 0.05° grid (≈5 km) before any use; the raw values are never stored,
  cached or logged. Candidate places come from the in-memory snapshot (at most 200 are queried, 50 returned).
  Each job lists its in-radius places with `distanceKm`.
- **Limits:** `radiusKm` 1–100 (default 25); every query has `maxTimeMS` 3000.
- **503 `Retry-After: 10`:** no snapshot yet (same as the snapshot endpoint).
  **503 `Retry-After: 5`:** a query exceeded 3 s.
- **Cache:** results are cached by endpoint + snapshot version + places/kind + normalized `q` + page + size, so a
  new snapshot starts fresh. Concurrent misses for one key share a single query.

| Variable | Default | Meaning |
|---|---|---|
| `GEO_MAP_JOBS_CACHE_TTL_MS` | `120000` | How long a result is reused |
| `GEO_MAP_JOBS_CACHE_MAX_ENTRIES` | `2000` | Expired entries go first, then the oldest |

Metrics: `jobportal.geo.mapjobs.cache` (counter, `result=hit|miss`), `jobportal.geo.mapjobs.query` (timer,
`endpoint=place|nearby|unplaced`), `jobportal.geo.mapjobs.timeouts` (counter).

**nginx:** `/api/` is proxied through an upstream with `keepalive 64` over HTTP/1.1 (GEO-3A). Without it,
every request opened a new backend connection and load tests failed with 502 (`99: Address not available`).

**Remote scope (GEO-6A, parser version 2):** a remote marker is scoped by its own candidate. "Remote",
"Work from home" and "Remote - India" are REMOTE; a remote marker whose only location is foreign ("Remote - United
States", "United States (Remote)", "Remote - San Francisco, CA, United States") is FOREIGN with `remote: true`, so it
is not in the Remote list. Any open or India-scoped remote marker in the location keeps the job REMOTE, and a resolved
Indian place keeps it PLACE. Postings can still restrict eligibility in their description, which the resolver does not
read, so the Remote tab asks candidates to check each posting.

**Load tests (local only):** seed with `python3 backend/backend/scripts/seed-map-jobs.py | docker compose exec
-T mongodb mongosh --quiet jobportal`, restart the backend, then run `scripts/k6/map-jobs.js`.

## Map tiles

The base map is an India extract of the Protomaps OpenStreetMap basemap (one `.pmtiles` file) plus the fonts and
sprites its style uses, served by our own nginx from a host directory. No map API, no keys, no per-view cost.

### Build or refresh (on the EC2 host, in the repo directory)

1. Check disk space: `df -h .` — a refresh needs about **2×** the archive size free while the old and new files
   coexist (≈600 MB at z11 today).
2. Install the `pmtiles` CLI from <https://github.com/protomaps/go-pmtiles/releases> (Linux x86_64 tarball; put the
   `pmtiles` binary on `PATH`) and check it with `pmtiles version`.
3. Run `bash frontend/scripts/build-tiles.sh --out ./tiles` (bash, not sh). It picks the newest daily build from
   `build-metadata.protomaps.dev/builds.json` (pass `--source https://build.protomaps.com/<YYYYMMDD>.pmtiles` to pin
   one), extracts India at `--maxzoom 11` (about 35 s, 276 MiB), and downloads only the fonts/sprites the style uses
   from a pinned `protomaps/basemaps-assets` commit.
4. Run it **before** the first `docker compose up`: if `./tiles` does not exist, Docker creates it owned by root and
   the script cannot write to it (`sudo chown -R "$USER" ./tiles` fixes that).

`TILES_DIR` (default `./tiles`) is mounted read-only into the frontend container. The script writes everything into
a temp dir and swaps `manifest.json` **last**, so browsers never see a manifest pointing at a missing file, and no
container restart or image rebuild is needed. It keeps the current and the previous `.pmtiles` and deletes older
ones. Monthly refreshes are plenty.

**Roll back:** edit `tiles/manifest.json` so `"pmtiles"` names the previous file (it is still on disk). Clients pick
it up on their next page load (`manifest.json` is served `no-cache`; everything else is immutable).

**Missing volume:** the site still works; `/tiles/manifest.json` is 404 and the map shows a plain background with
"Map background unavailable — markers still work".

### Rules

- **Disputed boundaries:** India's official map differs from OpenStreetMap's de facto boundaries. The style draws no
  boundary lines of any kind and no country or state/region labels (`baseStyle.js`; enforced by tests). Do not try to
  draw "corrected" borders.
- **Never hotlink** `maps.protomaps.com` / `build.protomaps.com` builds from the app; always extract and self-host.
- **Attribution** must stay visible on the map: © OpenStreetMap contributors (ODbL), Protomaps, GeoNames (CC BY 4.0).
  It comes from `manifest.json`.
- **CDN (GEO-7):** Cloudflare does not cache byte-range requests to large files on the free plan by default; serving
  `.pmtiles` through Cloudflare needs R2 + a Worker (Protomaps' documented path) or a cache rule, not a plain proxy.

## Office pins

City markers sit at the city centre. An ADMIN can pin a company's real office once, and from then on that company's
jobs in that city show at the office, with real distances in "near me". Pins come from a click on the map: no
geocoding API.

**Data:** `company_offices` (one document per office: `companyKey`, `company` as entered, `label`, `placeId`, `lat`,
`lng`, `enabled`, created/updated by and at) and the append-only `company_office_audit` (before/after per change).
Company and city are an office's identity; to change either, disable the office and pin a new one.

**API** (ADMIN, `/api/v1/admin/geo/offices`): `GET` list (`q`, `enabled`, paging), `GET /{id}`, `POST`
(`company`, `label`, `lat`, `lng`, optional `placeId`), `PATCH /{id}` (`label`, `lat`, `lng`, `enabled`),
`GET /{id}/audit`, `GET /candidates?limit=50`.

**Work the candidates list.** `candidates` ranks (company, city) pairs by visible jobs where the company has no
enabled office in that city, so the pins that move the most jobs come first. Companies are merged by their matching
key, so "Acme Pvt Ltd" and "ACME" are one row (shown with the most common spelling). The list is cached for 5 minutes
and dropped when an office changes.

**Validation:**
- The pin must be inside India's bounding box (lat 6–37.5, lng 68–97.5).
- Without `placeId` the office belongs to the nearest city of 100,000+ people (a pin in Cyber City is Gurugram, not
  Delhi). Pass `placeId` for a smaller city.
- **60 km guard:** the pin must be within 60 km of its city's centre, otherwise 400 "Pin is far from <city>". It
  catches misclicks (a Bengaluru office dropped in Mysuru). A real office further out belongs to another city.
- Labels are 2–80 characters and unique per company and city.

**Which jobs an office gets:** a job of company key `k` at place `p` belongs to an office only if **exactly one**
enabled office has key `k` and place `p`. With two enabled offices for one company in one city, neither gets jobs and
the company stays at city level there (ambiguous; disable one to fix it). Offices apply to jobs from every provider.
They are assigned at read time; jobs are never rewritten.

**Company matching key** (`shared.text.CompanyNames`, also used by job fingerprints): NFKC, lower case, `&` → "and",
punctuation removed, legal suffixes (`pvt ltd`, `limited`, `technologies`, `india`, …) stripped from the end, then
former names mapped by `backend/backend/src/main/resources/aggregation/company-aliases.json`. Changing that file
changes fingerprints too, so it needs a new `JobFingerprint.VERSION` and the fingerprint migration.

## Map page

`/jobs/map` (GEO-5) is the user-facing map: clustered city markers from the snapshot, a list panel, "Near me", "Jobs in
this area", and Remote / Anywhere-in-India tabs. Job cards open the normal `/jobs/{id}` page and apply flow. The old
`/map-preview` route redirects here. The homepage is unchanged.

| URL part | Meaning |
|---|---|
| `?place=<placeId>` | Selected place (dropped if it is not in the snapshot or the place endpoint returns 404) |
| `?tab=remote\|india` | Remote or Anywhere-in-India list (absent = On map) |
| `?q=<text>` | Search (max 100 chars); filters the list only, markers always show all openings |
| `#zoom/lat/lng` | Map view (MapLibre `hash`), so reload and Back from a job restore it |

**Requests per visit:** one snapshot (browser cache `max-age=60` + ETag revalidation; served from backend memory), then
one list request per place/tab/search/page. Place and unplaced lists are `public, max-age=60` and backed by the
snapshot-versioned server cache (`GEO_MAP_JOBS_CACHE_TTL_MS`); nearby is `private, max-age=60`. Panning and zooming
make no API calls. A 503 snapshot is retried after `Retry-After`, up to 3 times, then the panel offers Retry; the base
map still renders.

**Privacy:** the browser rounds the position from `navigator.geolocation` (coarse: `enableHighAccuracy: false`, cached
up to 10 min) to the 0.05° grid immediately, and the request sends only the rounded values. Coordinates never enter the
URL, `localStorage` or logs; near-me and this-area results reset on reload. While a near-me area is shown, the
`#zoom/lat/lng` view hash is removed (the view centre would be the user's rounded position) and comes back once the area
is cleared. The map draws an "Approximate area" circle,
not a "you are here" dot. If location is denied or times out, a city picker (top 50 places by jobs) runs the same search
from that city.

**Test near-me locally:** geolocation needs a secure context, and `http://localhost` counts as one. In Chrome DevTools open
*More tools → Sensors* and pick a location (or enter e.g. 28.6139, 77.2090 for Delhi); choose "Location unavailable" to
test the city picker. To test denial, block location in the site settings. With an empty database, seed jobs with
`scripts/seed-map-jobs.py` (see *Load tests* above) and remove them afterwards
(`db.jobs.deleteMany({seedBatch: "geo-load"})`).

## Attribution

GeoNames data is CC BY 4.0 (see `src/main/resources/geo/ATTRIBUTION.md`). Any page that shows resolved
places must credit "GeoNames (CC BY 4.0)". OpenStreetMap attribution arrives with the map in GEO-4/5.
