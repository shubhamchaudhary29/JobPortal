#!/usr/bin/env python3
"""Build the offline India gazetteer from GeoNames (CC BY 4.0, https://www.geonames.org).

Usage (from backend/backend): python3 scripts/build-gazetteer.py [--population-floor N]

Downloads cities500.zip and admin1CodesASCII.txt, keeps populated places (feature class P)
in India, and writes src/main/resources/geo/in-places.tsv and in-admin1.tsv. Output is sorted
and deterministic: the same GeoNames input always produces byte-identical files.
"""
import argparse
import io
import os
import sys
import urllib.request
import zipfile

BASE_URL = "https://download.geonames.org/export/dump/"
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources", "geo")
PLACES_HEADER = ["geonameid", "name", "asciiname", "admin1", "lat", "lng", "population", "featureCode", "altNames"]
MAX_ALT_NAMES = 15


def download(name):
    with urllib.request.urlopen(BASE_URL + name, timeout=120) as response:
        return response.read()


def clean(value):
    # TSV safety: GeoNames fields never legitimately contain tabs or newlines.
    return " ".join(value.replace("\t", " ").split())


def alt_names(raw, name, asciiname):
    seen = {name.casefold(), asciiname.casefold()}
    kept = []
    for candidate in raw.split(","):
        candidate = clean(candidate)
        if not candidate.isascii() or not 3 <= len(candidate) <= 40 or candidate.isdigit():
            continue
        if "|" in candidate or candidate.casefold() in seen:
            continue
        seen.add(candidate.casefold())
        kept.append(candidate)
        if len(kept) == MAX_ALT_NAMES:
            break
    return "|".join(kept)


def build_places(archive, population_floor):
    with zipfile.ZipFile(io.BytesIO(archive)) as zipped:
        text = zipped.read("cities500.txt").decode("utf-8")
    rows = []
    for line in text.splitlines():
        fields = line.split("\t")
        if len(fields) < 19 or fields[8] != "IN" or fields[6] != "P":
            continue
        population = int(fields[14] or 0)
        if population < population_floor:
            continue
        name, asciiname = clean(fields[1]), clean(fields[2])
        rows.append([
            fields[0], name, asciiname, fields[10],
            f"{float(fields[4]):.4f}", f"{float(fields[5]):.4f}", str(population), fields[7],
            alt_names(fields[3], name, asciiname),
        ])
    rows.sort(key=lambda row: int(row[0]))
    return rows


def build_admin1(text):
    rows = []
    for line in text.splitlines():
        fields = line.split("\t")
        if len(fields) >= 2 and fields[0].startswith("IN."):
            rows.append([fields[0][3:], clean(fields[1])])
    rows.sort(key=lambda row: row[0])
    return rows


def write_tsv(path, header, rows):
    with open(path, "w", encoding="utf-8", newline="\n") as out:
        out.write("\t".join(header) + "\n")
        for row in rows:
            out.write("\t".join(row) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--population-floor", type=int, default=0,
                        help="drop places below this population (cities500 already starts at 500)")
    args = parser.parse_args()
    try:
        places = build_places(download("cities500.zip"), args.population_floor)
        admin1 = build_admin1(download("admin1CodesASCII.txt").decode("utf-8"))
    except OSError as failure:
        print(f"GeoNames download failed: {failure}", file=sys.stderr)
        return 1
    os.makedirs(OUT_DIR, exist_ok=True)
    write_tsv(os.path.join(OUT_DIR, "in-places.tsv"), PLACES_HEADER, places)
    write_tsv(os.path.join(OUT_DIR, "in-admin1.tsv"), ["admin1", "name"], admin1)
    print(f"in-places.tsv: {len(places)} places")
    print(f"in-admin1.tsv: {len(admin1)} admin1 regions")
    return 0


if __name__ == "__main__":
    sys.exit(main())
