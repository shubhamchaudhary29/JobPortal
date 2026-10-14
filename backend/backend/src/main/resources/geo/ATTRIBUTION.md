# Geographic data attribution

`in-places.tsv` and `in-admin1.tsv` are derived from **GeoNames** (https://www.geonames.org),
licensed under **Creative Commons Attribution 4.0** (https://creativecommons.org/licenses/by/4.0/).

What was used and how it was filtered (see `backend/backend/scripts/build-gazetteer.py`):

- `cities500.zip`: rows with country code `IN` and feature class `P` (populated places, population ≥ 500).
  Kept columns: geonameid, name, asciiname, admin1 code, latitude/longitude (rounded to 4 decimals),
  population, feature code, and up to 15 ASCII alternate names (3–40 characters, not purely numeric).
- `admin1CodesASCII.txt`: `IN.*` rows (state/union-territory codes and names, `IN.` prefix removed).

`place-aliases.tsv`, `state-aliases.tsv` and `countries.tsv` are maintained by hand in this repository.

The product must show "GeoNames (CC BY 4.0)" wherever resolved places are displayed.
