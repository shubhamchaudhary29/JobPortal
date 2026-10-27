# Geographic data attribution

`in-places.tsv` and `in-admin1.tsv` are derived from **GeoNames** (https://www.geonames.org),
licensed under **Creative Commons Attribution 4.0** (https://creativecommons.org/licenses/by/4.0/).

What was used and how it was filtered (see `backend/backend/scripts/build-gazetteer.py`):

- `cities500.zip`: rows with country code `IN` and feature class `P` (populated places, population ≥ 500).
  Kept columns: geonameid, name, asciiname, admin1 code, latitude/longitude (rounded to 4 decimals),
  population, feature code, and up to 15 ASCII alternate names (3–40 characters, not purely numeric).
- `admin1CodesASCII.txt`: `IN.*` rows (state/union-territory codes and names, `IN.` prefix removed).

`place-aliases.tsv`, `state-aliases.tsv`, `countries.tsv` and `foreign-qualifiers.tsv` are maintained by hand in this repository.

The product must show "GeoNames (CC BY 4.0)" wherever resolved places are displayed.

## OpenStreetMap-derived files (GEO-8)

`in-localities.tsv` and `in-osm-offices.tsv` (either may be stored as `.tsv.gz` when larger than 4 MB) contain
information from **OpenStreetMap**, © OpenStreetMap contributors, which is made available here under the
**Open Database License (ODbL) 1.0** (https://opendatacommons.org/licenses/odbl/1-0/). They are derived databases and
are published in this public repository under the same licence. Attribution guidelines:
https://osmfoundation.org/wiki/Licence/Attribution_Guidelines

- **Source:** the Geofabrik India extract, https://download.geofabrik.de/asia/india-latest.osm.pbf. Each file's
  header comment records the extract's replication timestamp (the data date).
- **Built by:** `backend/backend/scripts/build-osm-geo.py` (offline, with pyosmium). No public OSM API, Nominatim or
  Photon service is queried, at build time or at runtime.
- **What was kept:**
  - `in-localities.tsv`: named `place=suburb|neighbourhood|quarter` objects, plus named tech hubs (names such as
    "… Tech Park", "… IT Park", "… SEZ" with `landuse=commercial|industrial|retail`, `office=*` or `place=*`).
    Columns: OSM id, `name`, `name:en`, `alt_name`/`old_name`/`short_name`, the gazetteer city it was assigned to,
    a representative point (4 decimals), kind and the matching tag.
  - `in-osm-offices.tsv`: objects with a `name`, `brand` or `operator` and `office=company|it|…` or
    `building=office|commercial` (government, diplomatic, political, religious, coworking and estate-agent offices,
    banks, ATMs and shops excluded). Columns: OSM reference, names, brand, operator, matching tag, assigned city and
    a representative point.
- **Hand-maintained, not OSM-derived:** `locality-aliases.tsv` (if present) only maps well-known spellings to rows of
  `in-localities.tsv`; `place-aliases.tsv`, `state-aliases.tsv`, `countries.tsv`, `foreign-qualifiers.tsv` as above.

Keep these files separate from the GeoNames-derived files and from our own data, and keep the visible
"© OpenStreetMap contributors" credit on every map that shows them (the map attribution already includes it).
