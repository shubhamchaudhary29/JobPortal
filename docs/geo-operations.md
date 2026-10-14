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

## Attribution

GeoNames data is CC BY 4.0 (see `src/main/resources/geo/ATTRIBUTION.md`). Any page that shows resolved
places must credit "GeoNames (CC BY 4.0)". OpenStreetMap attribution arrives with the map in GEO-4/5.
