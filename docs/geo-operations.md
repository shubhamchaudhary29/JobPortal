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
| `in-localities.tsv` | `scripts/build-osm-geo.py` | OpenStreetMap suburbs, neighbourhoods, quarters and tech hubs per city (GEO-8, ODbL) |
| `in-osm-offices.tsv` | `scripts/build-osm-geo.py` | OpenStreetMap company offices and office buildings per city (GEO-8, ODbL) |
| `locality-aliases.tsv` | hand | Localities that resolve without a city, and spellings OSM lacks (`Electronic City` → `Electronics City Phase 1`) |

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

### Rebuild the OpenStreetMap localities and offices (GEO-8)

Run on a laptop, never on EC2 (it reads a 1.7 GB file and needs about 2 GB of RAM):

```bash
cd backend/backend
pip install osmium==4.3.2        # or the docker one-liner in the script header
python3 scripts/build-osm-geo.py # downloads india-latest.osm.pbf to ~/.cache/jobportal-osm, or pass --pbf PATH
./mvnw test -Dtest='GazetteerDataTest,LocalityIndexTest'
```

It writes `in-localities.tsv` and `in-osm-offices.tsv` (gzipped as `.tsv.gz` if either passes 4 MB) with the source
URL, the extract's replication timestamp and the ODbL notice in their header comments, and prints row counts. Output is
sorted, so the diff shows only real OpenStreetMap changes. Monthly is plenty.

- **Parent city.** Each object belongs to the nearest gazetteer place of 100,000+ people within 40 km, in its own
  state when the candidates span states (OpenStreetMap state boundaries), skipping places absorbed into a bigger city:
  city sections (`PPLX`), names that `place-aliases.tsv` sends elsewhere (New Delhi → Delhi), towns within 30 km of a
  1M+ city at least 1.5× their size, and places within 10 km of a same-state alias target (Kanayannur → Kochi);
  district/state seats and alias targets are never absorbed. Listings name the big city, so this keeps HITEC City in
  Hyderabad, Dwarka in Delhi and Hinjawadi in Pune rather than in GeoNames sub-places. The script prints the absorbed
  list; check it after a gazetteer or alias change.
- **Cost of a rebuild.** `in-localities.tsv` is part of the gazetteer data hash, so a rebuild changes the resolver
  version and every active job is re-resolved by the job below. At the defaults (200 × 25 jobs every 10 minutes,
  30,000 jobs per hour) that takes about N hours for N × 30,000 active jobs; `GET /api/v1/admin/geo/precision` shows
  the figure for the current job count (`reResolution.hoursForAllActiveJobs`). Map counts are right throughout: old
  resolutions keep their places until they are replaced. `in-osm-offices.tsv` is applied at read time and does not
  trigger re-resolution.
- **Memory.** Both files are loaded once at startup. The backend logs
  `event=geo_osm_loaded localities=… offices=… heap_delta_mb=…`; the budget is 80 MB of the 1 GiB container. If a
  rebuild exceeds it, rebuild with `--min-population 300000` (fewer parent cities) and note it here.
- **Licence.** The files are OpenStreetMap data under ODbL 1.0 (see `src/main/resources/geo/ATTRIBUTION.md`); keep them
  separate from our own data and keep "© OpenStreetMap contributors" on the map.

### Add a locality alias

A locality alone ("Whitefield") resolves only through `locality-aliases.tsv`, so keep it to unambiguous, well-known
hubs. Add `alias<TAB>localityId` (an `osm-…` id from `in-localities.tsv`) or `alias<TAB>name|placeId`, then run
`./mvnw test -Dtest=GazetteerDataTest`: it fails on a target that is not in the locality file, or on an alias that is
also the name of a 100k+ city. An OSM rebuild can change ids (rarely for nodes); the same test catches it. Like every
data file, an edit changes the resolver version and re-resolves all jobs.

Inside a resolved city no alias is needed: "Hinjawadi, Pune" matches by name, "Hinjewadi, Pune" through the alias.

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

Since GEO-8B the job passes a listing's stored provider point (Adzuna) back to the resolver, so it is kept and can
snap to a locality. Earlier releases dropped the point on every version bump until the next Adzuna sync, so after the
first GEO-8 deploy some Adzuna jobs have no point until Adzuna lists them again.

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

## Precision report

`GET /api/v1/admin/geo/precision` (ADMIN, cached 5 minutes, every aggregation bounded by `maxTimeMS` 15 s with
`allowDiskUse`) says how precisely visible jobs can be shown:

| Field | Meaning |
|---|---|
| `byPrecision` | Jobs by best placement: `office` (a pinned office in one of its cities) > `locality` > `city` (city centre); jobs without a place by status: `state`, `country`, `remote`, `foreign`, `unresolved` (includes EMPTY and not yet resolved) |
| `topCityCentreStrings` | The 50 most common location strings of `city` jobs: text the resolver could still learn from |
| `topCities[]` | The 10 cities with most jobs: `centreJobs`, and of their top 500 centre strings how many (`stringsWithLocality`, `jobsWithLocality`) name a locality the city has (`knownLocalities`) |
| `adzuna` | Adzuna jobs with a stored point, points ≥ 1.5 km from their city centre, of those within 2 km of a known locality, and how many already have a locality |
| `ncr` | Jobs whose location is exactly "NCR", "Delhi NCR" or "Delhi-NCR" versus all jobs placed in Delhi |
| `reResolution` | Re-resolution throughput at the current settings and the hours a full pass takes |

**Check it locally.** Locally `.env` keeps scheduling off, so nothing re-resolves seeded jobs. This override turns
scheduling on but points every provider host at the container's own closed port, so no provider is called and only the
re-resolution job does real work:

```bash
python3 backend/backend/scripts/seed-map-jobs.py --offices 3 --realistic 60 \
  | docker compose exec -T mongodb mongosh --quiet jobportal
cat > /tmp/geo-local.yml <<'YML'
services:
  backend:
    environment:
      JOB_AGGREGATION_SCHEDULING_ENABLED: "true"
      GEO_RERESOLUTION_DELAY_MS: "20000"
    extra_hosts:
      - "api.adzuna.com:127.0.0.1"
      - "in.jooble.org:127.0.0.1"
      - "boards-api.greenhouse.io:127.0.0.1"
      - "api.lever.co:127.0.0.1"
      - "api.ashbyhq.com:127.0.0.1"
      - "api.smartrecruiters.com:127.0.0.1"
      - "apply.workable.com:127.0.0.1"
YML
docker compose -f docker-compose.yml -f /tmp/geo-local.yml up -d backend
docker compose logs -f backend | grep -m1 'geo_reresolution_completed.*remaining_estimate=0'
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost/api/v1/admin/geo/precision | jq
```

Restart without the override file afterwards (`docker compose up -d backend`).

## Map snapshot

`GET /api/v1/jobs/map/snapshot` (public) returns every place with visible jobs and its count, plus
`unplaced` counts by status (REMOTE, COUNTRY, …, UNPROCESSED) and `placedJobs` (distinct jobs with at least
one place). A job's places are the union over its **active** listings, the same definition the coverage
report uses; each job counts once per place. Reconciliation duplicates and inactive jobs are excluded.

It is built by one aggregation and served from memory; requests never reach MongoDB.

Office pins (GEO-6) add two fields and change no existing one: `places[].officeJobs` (how many of the city's `jobs`
belong to a pinned office; `jobs - officeJobs` stay at the city centre) and `offices[]` (`id`, `company`, `label`,
`placeId`, `lat`, `lng`, `jobs`; only offices that own jobs, sorted by id). The aggregation groups placed jobs by sorted
place ids and `lower(trim(company))` (keeping up to 5 raw spellings per group); Java maps each group's company key to
an office. More than 100,000 groups logs `event=geo_snapshot_large_facet` (no failure).

Localities (GEO-8C) add `places[].localityJobs` and `localities[]` (`id`, `name`, `placeId`, `lat`, `lng`, `jobs`; only
localities that own jobs, sorted by id) and change no existing field. At each of a job's places it counts toward the
company's office there, else toward its **first** locality there (resolver order), else the city centre, so per place
`jobs = officeJobs + localityJobs + centre jobs` and that place's `localities[].jobs` add up to `localityJobs`. The
aggregation also groups by the job's ordered locality ids. Measured locally on 21,080 jobs (19,023 placed, 33 places,
26 localities): builds took 113 ms on average (max 197 ms), heap in use after a build 69 MB, snapshot 7.6 KB (2.2 KB
gzipped).

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
| `/api/v1/jobs/map/places/{placeId}/jobs` | Jobs at one place (404 if the place is not in the gazetteer); `scope=unlisted` keeps only its centre jobs | `public, max-age=60` |
| `/api/v1/jobs/map/localities/{localityId}/jobs` | Jobs at one OpenStreetMap locality, without those at a pinned office in its city (400 for a malformed id, 404 if unknown) | `public, max-age=60` |
| `/api/v1/jobs/map/nearby?lat&lng&radiusKm` | Nearest 50 places within the radius + their jobs | `private, max-age=60` |
| `/api/v1/jobs/map/unplaced?kind=remote\|india` | Jobs with no place: REMOTE, or STATE/COUNTRY | `public, max-age=60` |
| `/api/v1/jobs/map/offices/{officeId}/jobs` | Jobs at one pinned office (404 if unknown, disabled or ambiguous) | `public, max-age=60` |

Common parameters: `q` (optional phrase search, max 100 chars; quotes are ignored), `page` 0–49 (default 0),
`size` 1–20 (default 20). Paging is slice-based (`hasNext`, no count query). Jobs are newest first.
`totalHint` is the snapshot's count when `q` is absent, otherwise `null` (always `null` for nearby).

- **Job definition:** the same as the snapshot (`GeoPipelines`): visible jobs; places from active listings, or
  the top-level geo of jobs without listings.
- **Nearby:** `lat`/`lng` are rounded to a 0.05° grid (≈5 km) before any use; the raw values are never stored,
  cached or logged. Candidate places come from the in-memory snapshot (at most 200 are queried, 50 returned).
  Each job lists its in-radius places with `distanceKm`.
- **Offices:** each job place carries `officeId`/`officeLabel` when the company has a pinned office there, and in
  nearby its `distanceKm` is measured to the office point. Nearby `places` mixes cities (`kind: "city"`) and offices
  within the radius (`kind: "office"`, with `officeId`, `company`, `label`, the office's point and jobs), sorted by
  distance. An office in range brings its own jobs even when its city centre is out of range. Lists stay newest first;
  there is no distance sort.
- **Office list:** the GEO-3 place filter for the office's city AND `company` in the spellings the last snapshot
  counted for that office. A spelling first seen after the last snapshot appears after the next refresh.
- **Localities (GEO-8C):** each job place carries `localityId`/`localityName` when the job names a locality there and
  has no office there. A locality list holds jobs whose localities (active listings, or the top-level geo) include the
  id, minus the company spellings of pinned offices in its city; `totalHint` is the snapshot count, which puts a job
  with two localities in one city at its first, so a list can be a few jobs longer than its hint. `scope=unlisted` on a
  place list excludes office spellings and the place's snapshot localities ("office not listed"; `totalHint` is the
  centre count). Nearby adds `kind: "locality"` chips (`localityId`, `label`, the locality's point and jobs); a
  locality in range brings its jobs even when its city centre is out of range, and job distances are measured to the
  office, else the locality, else the city centre.
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
`endpoint=place|office|nearby|unplaced`), `jobportal.geo.mapjobs.timeouts` (counter).

**nginx:** `/api/` is proxied through an upstream with `keepalive 64` over HTTP/1.1 (GEO-3A). Without it,
every request opened a new backend connection and load tests failed with 502 (`99: Address not available`).

**Remote scope (GEO-6A, parser version 2):** a remote marker is scoped by its own candidate. "Remote",
"Work from home" and "Remote - India" are REMOTE; a remote marker whose only location is foreign ("Remote - United
States", "United States (Remote)", "Remote - San Francisco, CA, United States") is FOREIGN with `remote: true`, so it
is not in the Remote list. Any open or India-scoped remote marker in the location keeps the job REMOTE, and a resolved
Indian place keeps it PLACE. Postings can still restrict eligibility in their description, which the resolver does not
read, so the Remote tab asks candidates to check each posting.

**Load tests (local only):** see "Load tests (GEO-7D)" below.

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

**Editor:** `/admin/offices` (ADMIN; linked from Aggregation operations and the employer registry). Pick a row in the
worklist ("Pin office"), click the map at the office (zoom 12 on the city) and drag the pin to adjust, give it a label
and Save. A 400 (for example "Pin is far from Bengaluru") is shown under the map. "New office" starts without a city:
the server assigns the nearest large city. The offices table edits label and pin, enables/disables, and shows each
office's audit history.

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

**How long until a change shows:** every office create/update/disable schedules a snapshot rebuild 5 s later
(property `geo.snapshot.office-refresh-delay-ms`, default 5000; debounced, so a burst of edits costs one build). Browsers cache the snapshot for
up to 60 s, so the map shows a change within about a minute; the worst case is the regular 5-minute rebuild if the
async one fails. Job lists use the new snapshot version as their cache key, so they switch at the same time.

**Company matching key** (`shared.text.CompanyNames`, also used by job fingerprints): NFKC, lower case, `&` → "and",
punctuation removed, legal suffixes (`pvt ltd`, `limited`, `technologies`, `india`, …) stripped from the end, then
former names mapped by `backend/backend/src/main/resources/aggregation/company-aliases.json`. Changing that file
changes fingerprints too, so it needs a new `JobFingerprint.VERSION` and the fingerprint migration.

## OpenStreetMap offices (GEO-8D)

OpenStreetMap (`in-osm-offices.tsv`) suggests where a company's office is in a city. Suggestions can be wrong (the data
is volunteer-made and the match is by name only), so they are off the map until reviewed.

**How a match is made:** exact equality of the company matching key with the key of an office's name, English name,
brand or operator, as is or without trailing generic words (office, campus, India, HQ, development/technology/innovation
/delivery centre, IDC, GDC, R&D, SEZ, "tower B", "phase 2", the city's own names). Never fuzzy or by prefix. Generic,
short or city-name keys never match. Two objects of one company within 150 m are one office.

**Precedence for a (company, city) pair:** an ADMIN office (enabled *or disabled*) → OSM ignored; a rejected object is
ignored; exactly one object left and auto-apply on → it is the office (60 km guard); several → suggestion only.

**Review workflow (ADMIN, `/admin/offices`):**
1. Open the "OpenStreetMap matches" tab (`GET /api/v1/admin/geo/offices/osm-report`). It shows how many (company,
   city) pairs have one, several or no match, how many jobs auto-apply would move, and the 50 matches with most jobs.
2. Open each link on openstreetmap.org and check the object is that company's office. **Reject** the wrong ones.
3. In the worklist, "Pin office" shows a pair's matches as grey markers: **Use this office** makes it a normal pin
   (audited, `source: "OSM"`), **Not this company** rejects it. An admin pin always wins over OSM.
4. When the top matches look right, set `GEO_OSM_OFFICE_AUTO_APPLY=true` in `.env` and redeploy. Single matches then
   show as offices within about a minute; turning the flag off removes them again (no data is written).

Decisions are in `osm_office_decisions` (one per company key, city and object; a later decision replaces it) and in
`company_office_audit` (`OSM_PICK` on the new office, `OSM_REJECT` with the `osm-…` reference as `officeId`).

| Variable | Default | Meaning |
|---|---|---|
| `GEO_OSM_OFFICE_AUTO_APPLY` | `false` | Apply single, unrejected OSM matches as offices (after reviewing the report) |

## Map page

`/jobs/map` (GEO-5) is the user-facing map: clustered city markers from the snapshot, a list panel, "Near me", "Jobs in
this area", and Remote / Anywhere-in-India tabs. Job cards open the normal `/jobs/{id}` page and apply flow. The old
`/map-preview` route redirects here. The homepage is unchanged.

| URL part | Meaning |
|---|---|
| `?place=<placeId>` | Selected place, all its jobs (dropped if it is not in the snapshot or the place endpoint returns 404) |
| `&scope=unlisted` | With `place`: only its "office not listed" jobs (a click on the city bubble opens this) |
| `?locality=<osm-…>` | Selected locality (dropped if it is not in the snapshot); office > locality > place if several are given |
| `?tab=remote\|india` | Remote or Anywhere-in-India list (absent = On map) |
| `?q=<text>` | Search (max 100 chars); filters the list only, markers always show all openings |
| `#zoom/lat/lng` | Map view (MapLibre `hash`), so reload and Back from a job restore it |

**Markers (GEO-8C):** a city bubble holds only its "office not listed" jobs (`jobs - officeJobs - localityJobs`; no
bubble when that is 0), localities are lighter, smaller indigo bubbles at their own point (labelled "Whitefield · 42"
from zoom 10), offices white rings. Cluster sums still equal the city totals. The city panel says "{n} jobs · office
not listed" and "These listings only name the city.", with "Show all {City} jobs". Job cards show the office label, else
the locality, else "{City} · Office not listed".

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

## Edge and hardening (GEO-7)

### Client IP and direct access (GEO-7A)

- nginx is the only way in: the backend has no published port. For local debugging publish it on loopback with
  `docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d` (never on the server).
- Rate-limit keys use `shared.web.ClientIp`: `X-Real-IP` (set by nginx to `$remote_addr`) when it is a valid IP literal,
  else the socket address. `X-Forwarded-For` is ignored; nginx sends only `$remote_addr` in it.
- Swagger (`/swagger-ui.html`, `/v3/api-docs`) is 404 under Compose unless `SWAGGER_ENABLED=true` is set in `.env`.

### nginx configuration (GEO-7B)

| File in `frontend/` | Installed as | Purpose |
|---|---|---|
| `nginx.conf.template` | rendered to `conf.d/default.conf` | HTTPS server (when the certificate is readable) |
| `nginx-http.conf` | copied to `conf.d/default.conf` | Plain HTTP server for local runs |
| `jobportal-locations.conf` | `jobportal/locations.conf` | All locations, shared by both servers |
| `proxy-backend.conf` | `jobportal/proxy-backend.conf` | Proxy settings for every `/api/` location |
| `security-headers.conf` | `jobportal/security-headers.conf` | Security headers |
| `nginx-limits.conf` | `conf.d/00-limits.conf` | `limit_req` zones (http context) |
| `select-nginx-config.sh` | `/docker-entrypoint.d/05-…` | Picks HTTPS/HTTP and renders the template |

The template is rendered with `envsubst '${APP_SERVER_NAMES} ${TLS_CERT_PATH} ${TLS_KEY_PATH}'`, so nginx variables
such as `$host` are untouched. The script rejects values that are not plain host names or absolute paths, and the
container then fails to start instead of running a broken config.

| Variable (frontend) | Default | Meaning |
|---|---|---|
| `APP_DOMAIN` | `54-236-126-94.sslip.io` | Canonical host; Let's Encrypt directory name |
| `APP_SERVER_NAMES` | `$APP_DOMAIN` | Space-separated `server_name` list |
| `TLS_CERT_PATH` | `/etc/letsencrypt/live/$APP_DOMAIN/fullchain.pem` | Certificate inside the container |
| `TLS_KEY_PATH` | `/etc/letsencrypt/live/$APP_DOMAIN/privkey.pem` | Private key inside the container |
| `TLS_DIR` | `/etc/letsencrypt` | Host certificate directory, mounted read-only at the same path (GEO-7C) |
| `CLOUDFLARE_PROXY` | `false` | `true` restores the visitor IP from `CF-Connecting-IP` (GEO-7C) |

With no new variables in `.env`, the rendered server names and certificate paths are exactly those of the pre-GEO-7
`nginx.conf`.

**Check a config change** before deploying (dummy certificate; `--add-host` because nginx resolves the upstream):

```bash
docker build -t jobportal-frontend:test frontend
mkdir -p /tmp/le/live/54-236-126-94.sslip.io && openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj /CN=test \
  -keyout /tmp/le/live/54-236-126-94.sslip.io/privkey.pem -out /tmp/le/live/54-236-126-94.sslip.io/fullchain.pem
docker run --rm --add-host backend:127.0.0.1 -v /tmp/le:/etc/letsencrypt:ro jobportal-frontend:test \
  sh -c '/docker-entrypoint.d/05-select-jobportal-config.sh && nginx -t'          # HTTPS branch
docker run --rm --add-host backend:127.0.0.1 jobportal-frontend:test \
  sh -c '/docker-entrypoint.d/05-select-jobportal-config.sh && nginx -t'          # HTTP branch
```

### Rate limits (GEO-7B, GEO-7F)

Per client IP (`$binary_remote_addr`), `nodelay` (a burst is answered at once, then excess requests are rejected):

| Location | Zone | Rate | Burst |
|---|---|---|---|
| `/api/` (everything else, including `/jobs/map/snapshot`) | `api` | 20 r/s | 40 |
| `/api/v1/jobs/map/{nearby, places/*/jobs, offices/*/jobs, localities/*/jobs, unplaced}` | `map_list` | 5 r/s | 10 |
| `/api/v1/auth/{sessions, registrations, recruiter-registrations}` (login, sign-up) | `auth` | 30 r/min | 20 |
| `/api/v1/auth/sessions/refresh` | `refresh` | 120 r/min | 60 |
| Swagger (when enabled) | `api` | 20 r/s | 40 |
| `/tiles/`, static files, `/ws` | none | | |

- The snapshot stays in the general zone: it is precomputed and answered with ETag/304.
- Logout (`DELETE /api/v1/auth/sessions/current`) is in the general `api` zone.
- Behind nginx the backend has its own limits (429 with `code: RATE_LIMITED`, `detail` "Too many authentication
  attempts"):

  | Backend limit | Key | Default | Variables |
  |---|---|---|---|
  | Login | client IP + email | 5 / 60 s, reset on success | `LOGIN_RATE_LIMIT_*` |
  | Refresh | `refresh:` + first 16 hex of SHA-256(refresh token) | 20 / 60 s | `REFRESH_RATE_LIMIT_*` |

  Every successful refresh rotates the token, so the refresh limit caps replays of one cookie (for example a burst of
  tabs before the first rotation lands), not a user's refreshes over time.
- **Reuse grace (`REFRESH_REUSE_GRACE_SECONDS`, 30):** a refresh token reused within 30 s of being rotated gets 401 but
  keeps the session (two tabs racing). Later reuse, or reuse of a logged-out/expired token, revokes the whole family.

#### Shared IPs (campus Wi-Fi, CGNAT)

Many of our users are students on college Wi-Fi or Jio/Airtel mobile data, where carrier-grade NAT puts hundreds of
people behind one public IP. Anything keyed per IP is shared by all of them, so:
- Limits that protect an account are keyed on the account or session in the backend (login: IP + email; refresh:
  per session), never on the IP alone.
- nginx's per-IP zones are coarse flood guards with room for a lab full of users: refresh (every page load and every
  new tab) has its own 120 r/min zone, and login/sign-up 30 r/min with a burst of 20.
- The SPA never logs a user out because of a 429, a network error or a 5xx on refresh: it retries 429s with
  `Retry-After` and clears the session only on 401/403 (GEO-7F-3). Tabs refresh one at a time (Web Locks) and share
  the new token over a `BroadcastChannel`, so ten open tabs cost one refresh, not ten.
- A rejected request gets `429` with `Retry-After: 1` and a body in the backend's ProblemDetail shape:
  `{"type":".../problems/rate-limit","title":"Too many requests","status":429,"detail":"Too many requests, slow down
  and try again shortly.","code":"RATE_LIMITED"}`. The map list panel shows "You're browsing a little fast…" with
  Retry; login shows the detail inline.
- Rejections are logged at `warn` in the nginx error log (`limiting requests, excess: … by zone "map_list", client:
  …`). The access log format is unchanged (it does contain the request line, as before GEO-7).
- **Tune:** edit `frontend/nginx-limits.conf` (rates) or `frontend/jobportal-locations.conf` (bursts), rebuild the
  frontend image. Everyone behind one NAT shares one key, so raise `burst` before `rate` if campuses report 429s.

### Security headers (GEO-7B)

Set by nginx on every response (HTML, API, tiles, 429s) via `security-headers.conf`, which is included at server
level and again in each location with its own `add_header` (an `add_header` in a location drops the inherited ones).
Spring Security's copies are hidden on proxied responses so there are no duplicates.

| Header | Value |
|---|---|
| `Strict-Transport-Security` | `max-age=31536000` (HTTPS only; no `includeSubDomains`/`preload` while on shared sslip.io) |
| `X-Content-Type-Options` | `nosniff` |
| `Referrer-Policy` | `strict-origin-when-cross-origin` |
| `X-Frame-Options` | `DENY` |
| `Permissions-Policy` | `geolocation=(self), camera=(), microphone=()` (the map's "Near me" needs geolocation) |
| `Content-Security-Policy-Report-Only` | `default-src 'self'`, blob workers allowed, … (see the file) |

The CSP is **report-only**: browsers log violations in the console but block nothing. MapLibre runs blob workers, and a
wrong enforcing policy breaks the map silently. Promote it to `Content-Security-Policy` in a later phase, once the map,
chat, resume upload/download and admin pages show no violations.

### Load tests (GEO-7D)

Local only, never against production. Since GEO-7B, nginx caps one IP at 5 r/s on map lists and 20 r/s on the rest of
`/api/`, so capacity runs bypass nginx and target the backend **inside the Compose network**; a separate script checks
the limits through nginx.

```bash
# Seed ~2,000 jobs and 3 "Acme" offices, then restart the backend so the snapshot includes them.
python3 backend/backend/scripts/seed-map-jobs.py --offices 3 | docker compose exec -T mongodb mongosh --quiet jobportal
docker compose restart backend

# Capacity (network = <Compose project>_default, e.g. jobportal_default; check with `docker network ls`).
docker run --rm -i --network jobportal_default -e BASE_URL=http://backend:8080 grafana/k6 run - < backend/backend/scripts/k6/map-snapshot.js
docker run --rm -i --network jobportal_default -e BASE_URL=http://backend:8080 grafana/k6 run - < backend/backend/scripts/k6/map-jobs.js

# Rate limits, through nginx from one IP.
docker run --rm -i --network host -e BASE_URL=http://localhost grafana/k6 run - < backend/backend/scripts/k6/rate-limits.js

# Clean up.
docker compose exec -T mongodb mongosh --quiet jobportal --eval \
  'db.jobs.deleteMany({seedBatch: "geo-load"}); db.company_offices.deleteMany({seedBatch: "geo-load"})'
```

| Script | Mix |
|---|---|
| `map-snapshot.js` | 200 VUs × 60 s; half revalidate with `If-None-Match` (304), all gzip |
| `map-jobs.js` | 200 VUs × 60 s; 60% place lists, 20% nearby, 10% place + `q`, 5% office lists, 5% locality lists (place lists when the snapshot has no offices or localities) |
| `rate-limits.js` | 20 s at fixed rates through nginx: nearby 15 r/s, snapshot 10 r/s, `/tiles/manifest.json` 50 r/s |

**Results, 2026-10-17.** Laptop: Intel Core i5-1240P (16 threads), 7.4 GiB RAM, Linux 7.0, Docker 29.1, k6 2.3.0 in
Docker on the same machine (so k6 competes with the stack for CPU). Default Compose memory limits (GEO-0: backend
1 GiB, Mongo 768 MiB, WiredTiger 0.25 GB). Data: 2,000 seeded jobs (1,813 placed over 30 places), 3 offices.
Peak memory is the highest `docker stats` sample during the run.

| Run | VUs | Requests/s | p95 | p99 | Errors | Peak backend | Peak Mongo |
|---|---|---|---|---|---|---|---|
| `map-snapshot.js` (backend direct) | 200 | 13,047 | 33.6 ms | 58.6 ms | 0.00% | 482 MiB | 250 MiB |
| `map-jobs.js` (backend direct) | 200 | 10,756 | 34.4 ms | 91.3 ms | 0.00% | 513 MiB | 234 MiB |
| `rate-limits.js` (through nginx) | ≤ 20 | 75 | 3.7 ms | 4.4 ms | 0.00% unexpected | 512 MiB | 238 MiB |

**GEO-8C re-run, 2026-10-21** (same laptop and limits; 21,080 seeded jobs: `--jobs 20000 --realistic 1080 --ndjson`
plus 3 offices, re-resolved to 19,023 placed jobs over 33 places and 26 localities):

| Run | VUs | Requests/s | p95 | p99 | Errors | Peak backend | Peak Mongo |
|---|---|---|---|---|---|---|---|
| `map-jobs.js` with the locality leg (backend direct) | 200 | 13,718 | 26.2 ms | 55.3 ms | 0.00% | 519 MiB | 385 MiB |

Through nginx, 40 parallel locality-list requests from one IP got 11 × 200 and 29 × 429 (`map_list` burst 10).

`rate-limits.js` thresholds all passed: nearby 63.1% 429 (190 of 301; the expected share is ≈ 1 − 111/300), every 429
had the `RATE_LIMITED` body, the snapshot was 200/304 for 201 of 201, and `/tiles/manifest.json` had 0 429s.
These are laptop numbers for regressions, not a production capacity promise: the EC2 box has less CPU and RAM.

## Own domain and Cloudflare (GEO-7C)

Cloudflare cannot proxy `*.sslip.io`, so the edge needs an own domain. The code is ready; the cutover is manual.

### How the client IP flows

```
visitor ──▶ Cloudflare edge ──▶ nginx ──▶ backend
            adds CF-Connecting-IP   realip: if the TCP peer is in a Cloudflare range,   ClientIp.of():
                                    $remote_addr := CF-Connecting-IP                     X-Real-IP (= $remote_addr)
                                    limit_req keys on $binary_remote_addr                login limiter key
                                    X-Real-IP / X-Forwarded-For := $remote_addr
```

- `CLOUDFLARE_PROXY=true` makes `select-nginx-config.sh` install `frontend/cloudflare-realip.conf`
  (`set_real_ip_from` for every Cloudflare range, `real_ip_header CF-Connecting-IP`, `real_ip_recursive on`) into both
  servers; with `false` (default) the include is empty and the header is ignored.
- A `CF-Connecting-IP` from any address outside those ranges is ignored even when the flag is on, so a visitor that
  reaches the origin directly cannot pick its own rate-limit key. The security group (step 6) makes that moot anyway.
- **Local proof** (2026-10-17, stack up with `CLOUDFLARE_PROXY=true`, Compose project `geo7`):

  ```bash
  docker run --rm --network geo7_default curlimages/curl:8.10.1 -s -o /dev/null \
    -H 'CF-Connecting-IP: 1.2.3.4' -A cf-spoof http://frontend/api/v1/health
  docker compose -p geo7 logs frontend | grep cf-spoof
  # 172.19.0.5 - - [...] "GET /api/v1/health HTTP/1.1" 200 ... "cf-spoof"   ← the docker peer, not 1.2.3.4
  ```

  Eight logins with a different `CF-Connecting-IP` each still hit 429 after five. As a positive control, a container
  whose realip file also trusts `172.16.0.0/12` logged `1.2.3.4`, and the backend limiter then counted each forged
  visitor separately, which shows the header reaches `ClientIp` only through a trusted proxy.
- **Refresh the ranges** when Cloudflare announces a change: `bash frontend/scripts/update-cloudflare-ips.sh`, commit
  the diff, rebuild the frontend, and update the security group (step 6).

### Runbook

1. **Domain and Cloudflare.** Buy the domain. In Cloudflare (free plan) *Add a site*, pick Free, and set the two
   nameservers it shows at the registrar. Wait until the site shows *Active*.
2. **DNS.** `A  @  → <EC2 Elastic IP>` and `CNAME  www  → @`, both **Proxied** (orange cloud).
3. **TLS.** SSL/TLS → Overview → **Full (strict)**. SSL/TLS → Origin Server → *Create Certificate*: RSA (2048),
   hostnames `<domain>` and `*.<domain>`, 15 years. On EC2:

   ```bash
   sudo mkdir -p /etc/ssl/cloudflare
   sudo nano /etc/ssl/cloudflare/origin.pem   # paste the Origin Certificate
   sudo nano /etc/ssl/cloudflare/origin.key   # paste the Private Key (shown once)
   sudo chmod 600 /etc/ssl/cloudflare/origin.key && sudo chmod 644 /etc/ssl/cloudflare/origin.pem
   ```

   nginx's master process runs as root and reads the key before dropping privileges, so `600 root` is fine.
   The Origin CA certificate is trusted only by Cloudflare, never by browsers: it only works behind the orange cloud.
4. **`.env`** on EC2 (keep a copy of the old file: `cp .env .env.before-cloudflare`):

   ```bash
   APP_DOMAIN=<domain>
   APP_SERVER_NAMES="<domain> www.<domain>"
   TLS_DIR=/etc/ssl/cloudflare
   TLS_CERT_PATH=/etc/ssl/cloudflare/origin.pem
   TLS_KEY_PATH=/etc/ssl/cloudflare/origin.key
   CLOUDFLARE_PROXY=true
   CORS_ALLOWED_ORIGINS=https://<domain>,https://www.<domain>
   REFRESH_COOKIE_SECURE=true
   ```

   CORS origins need the `https://` scheme. Then deploy:
   `git pull && docker compose down && docker compose up -d --build`, and check
   `docker compose logs frontend | grep select-nginx` shows "Cloudflare real IP enabled" and "HTTPS for <domain> www.<domain>".
5. **WebSockets.** Network → WebSockets **On** (free plan). `/ws` (SockJS chat) depends on it.
6. **Security group.** Inbound 80 and 443 **only from Cloudflare's ranges**, 22 only from your own IP, nothing on 8080
   or 27017 (neither is published by Compose anyway). The ranges are the ones in `frontend/cloudflare-realip.conf`.
   Optional AWS CLI loop (replace `sg-…`; then delete the old `0.0.0.0/0` and `::/0` rules for 80/443 in the console):

   ```bash
   SG=sg-0123456789abcdef0
   for cidr in $(sed -n 's/^set_real_ip_from \(.*\);$/\1/p' frontend/cloudflare-realip.conf); do
     for port in 80 443; do
       case "$cidr" in
         *:*) range="Ipv6Ranges=[{CidrIpv6=$cidr,Description=cloudflare}]" ;;
         *)   range="IpRanges=[{CidrIp=$cidr,Description=cloudflare}]" ;;
       esac
       aws ec2 authorize-security-group-ingress --group-id "$SG" \
         --ip-permissions "IpProtocol=tcp,FromPort=$port,ToPort=$port,$range"
     done
   done
   ```

   Security groups allow 60 inbound rules per address family by default; 14 IPv4 × 2 ports fits.
   Once 80 is closed to the world, Let's Encrypt HTTP-01 renewals for the sslip.io fallback stop working; the Origin CA
   certificate does not need renewing for 15 years.
7. **Cache.** Caching → Cache Rules → *Create rule* "API bypass": URI Path starts with `/api/` → **Bypass cache**
   (Cloudflare does not cache JSON by default; the rule makes it explicit). Add `/ws` to the same rule. `/assets/*`
   (hashed Vite files) can be cached; nothing else needs a rule. `.pmtiles` is not a default cached extension, so tile
   range requests pass through to nginx. Serving tiles from R2 is GEO-7E.
8. **Verify.**
   - `curl -sI https://<domain>` shows `server: cloudflare` and `strict-transport-security: max-age=31536000`.
   - In a browser: login, `/jobs/map` (markers and base map), "Near me", chat over WebSocket (DevTools → Network → WS
     shows `101`), resume PDF download, `/admin/offices`.
   - `docker compose logs --tail 20 frontend` shows real visitor IPs, not `172.x` or Cloudflare addresses.
   - `curl -m 10 -k https://<EC2 Elastic IP>` from outside AWS **times out** (security group).
   - Rate limits still bite per visitor: run the `nearby` loop from GEO-7B against `https://<domain>`.
9. **Rollback.**
   - Restore the old `.env` (`cp .env.before-cloudflare .env`, which has `CLOUDFLARE_PROXY=false` and the sslip.io
     values), re-open 80/443 to `0.0.0.0/0` and `::/0` in the security group, and redeploy
     (`docker compose down && docker compose up -d --build`). The site is back on `https://54-236-126-94.sslip.io`.
   - Setting the DNS records to **DNS only** (grey cloud) sends visitors straight to EC2, where the Origin CA certificate
     is **not** browser-trusted. Grey cloud with the own domain therefore needs a Let's Encrypt certificate for it first
     (`certbot certonly` for `<domain>` and `www.<domain>`, then `APP_DOMAIN=<domain>`, `TLS_DIR=/etc/letsencrypt`, empty
     `TLS_CERT_PATH`/`TLS_KEY_PATH`, `CLOUDFLARE_PROXY=false`).

### Fallback: sslip.io with Let's Encrypt

With no GEO-7 variables in `.env`, the frontend serves `54-236-126-94.sslip.io` with the Let's Encrypt certificate from
`/etc/letsencrypt/live/54-236-126-94.sslip.io/`, exactly as before GEO-7. Renewal stays as it is today (port 80 must be
reachable for HTTP-01).

## Attribution

GeoNames data is CC BY 4.0 (see `src/main/resources/geo/ATTRIBUTION.md`). Any page that shows resolved
places must credit "GeoNames (CC BY 4.0)". OpenStreetMap attribution arrives with the map in GEO-4/5.
