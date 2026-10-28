#!/usr/bin/env python3
"""Build src/main/resources/geo/in-pincode-localities.tsv: PIN code -> one known locality (GEO-10B).

Source: GeoNames postal codes for India, https://download.geonames.org/export/zip/IN.zip (CC BY 4.0), read offline:

    curl -sS -o /tmp/IN.zip https://download.geonames.org/export/zip/IN.zip
    python3 scripts/build-pincodes.py --zip /tmp/IN.zip

Why by name, not by coordinates: GeoNames gives most Indian PINs a district-level point (97 of Bengaluru's 109 PINs
share one point, 28 km north of the city), so "nearest locality to the PIN point" picks the wrong locality. The post
office NAMES are good ("560066 Whitefield", "560100 Electronics City"), so a PIN is mapped to a locality only when
the names of its post offices name exactly one locality of in-localities.tsv / curated-localities.tsv in the same
state (directly or through locality-aliases.tsv). PINs whose names name no locality, or several, are left out: they
add nothing over the city, or would guess. A same-named locality of another city is ruled out by the PIN's district:
the locality's city must be the city the district is named after (in-places.tsv or place-aliases.tsv), or else lie
within MAX_SANITY_KM of the GeoNames point (a district-level point, so the radius is wide). The point is never a
location.

PIN codes never become map pins: the backend only uses a PIN to pick a locality inside a city the job already has.
"""
import argparse
import collections
import io
import math
import os
import re
import sys
import unicodedata
import zipfile

SCRIPTS_DIR = os.path.dirname(os.path.abspath(__file__))
GEO_DIR = os.path.join(SCRIPTS_DIR, "..", "src", "main", "resources", "geo")
OUT = "in-pincode-localities.tsv"
MAX_SANITY_KM = 20.0
PIN = re.compile(r"^[1-9]\d{5}$")
# Post-office suffixes India Post appends to office names ("Whitefield S.O", "Koramangala VI Bk SO").
OFFICE_SUFFIX = re.compile(r"\s+(?:s o|b o|h o|so|bo|ho|gpo|po|sub office|head office|branch office)$")
DROPPED = re.compile(r"['.’]")
SEPARATORS = re.compile(r"[^0-9a-z]+")
FUSED_NUMBER = re.compile(r"^(sector|sec|phase|ph|block|stage)(\d+[a-z]?)$")
UNIFY = {"sec": "sector", "ph": "phase", "blk": "block", "bk": "block"}
ROMAN = {"i": "1", "ii": "2", "iii": "3", "iv": "4", "v": "5"}
SPLITTERS = {"sector", "phase", "block", "stage"}


def key(value):
    """Gazetteer.key: fold diacritics, lower case, '&' -> 'and', drop ' . ', other punctuation -> space."""
    folded = unicodedata.normalize("NFKD", value or "")
    folded = "".join(ch for ch in folded if not unicodedata.combining(ch)).lower().replace("&", " and ")
    return " ".join(SEPARATORS.sub(" ", DROPPED.sub("", folded)).split())


def locality_key(value):
    """LocalityIndex.localityKey: sec -> sector, sector62 -> sector 62, Phase II -> phase 2."""
    words = []
    for word in key(value).split():
        fused = FUSED_NUMBER.match(word)
        words.extend([fused.group(1), fused.group(2)] if fused else [word])
    out = []
    for word in words:
        word = UNIFY.get(word, word)
        if out and out[-1] in SPLITTERS and word in ROMAN:
            word = ROMAN[word]
        out.append(word)
    return " ".join(out)


def name_variants(name):
    """The office name, its parenthesised part, and both without post-office suffixes; at least 3 characters."""
    raw = [re.sub(r"\([^)]*\)", " ", name)] + re.findall(r"\(([^)]*)\)", name)
    variants = set()
    for part in raw:
        current = locality_key(part)
        while current:
            variants.add(current)
            stripped = OFFICE_SUFFIX.sub("", current)
            if stripped == current:
                break
            current = stripped
    return {variant for variant in variants if len(variant) >= 3}


def km(lat1, lng1, lat2, lng2):
    d_lat, d_lng = math.radians(lat2 - lat1), math.radians(lng2 - lng1)
    a = math.sin(d_lat / 2) ** 2 + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(d_lng / 2) ** 2
    return 6371.0088 * 2 * math.asin(min(1.0, math.sqrt(a)))


def tsv_rows(path):
    """Data rows of a resource TSV: comment lines and the column header dropped."""
    header = True
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            if header:
                header = False
                continue
            yield line.split("\t")


def load_city_names(geo_dir):
    """Place id -> keys it is known by: its names and every alias pointing at it ("bangalore" for Bengaluru)."""
    names = collections.defaultdict(set)
    by_name = collections.defaultdict(set)
    for row in tsv_rows(os.path.join(geo_dir, "in-places.tsv")):
        for spelling in (row[1], row[2]):
            names[row[0]].add(key(spelling))
            by_name[key(spelling)].add(row[0])
    with open(os.path.join(geo_dir, "place-aliases.tsv"), encoding="utf-8") as handle:
        for line in handle:
            if not line.strip() or line.startswith("#"):
                continue
            alias, target = line.rstrip("\n").split("\t")[:2]
            for place_id in by_name.get(key(target), ()):
                names[place_id].add(key(alias))
    return names


def load_localities(geo_dir):
    places = {row[0]: row[3] for row in tsv_rows(os.path.join(geo_dir, "in-places.tsv"))}
    localities = {}
    by_state_key = collections.defaultdict(set)
    for name in ("in-localities.tsv", "curated-localities.tsv"):
        for row in tsv_rows(os.path.join(geo_dir, name)):
            locality_id, place_id = row[0], row[4]
            if place_id not in places:
                continue
            localities[locality_id] = (row[1], place_id, float(row[5]), float(row[6]))
            for spelling in [row[1], row[2]] + [alt for alt in row[3].split("|") if alt]:
                if spelling:
                    by_state_key[(places[place_id], locality_key(spelling))].add(locality_id)
    with open(os.path.join(geo_dir, "locality-aliases.tsv"), encoding="utf-8") as handle:
        for line in handle:
            if not line.strip() or line.startswith("#"):
                continue
            alias, target = line.rstrip("\n").split("\t")[:2]
            target = target.strip()
            if target in localities:
                by_state_key[(places[localities[target][1]], locality_key(alias))].add(target)
    return localities, by_state_key


def read_postal(path):
    if path.endswith(".zip"):
        with zipfile.ZipFile(path) as archive:
            text = archive.read("IN.txt").decode("utf-8")
    else:
        with open(path, encoding="utf-8") as handle:
            text = handle.read()
    by_pin = collections.defaultdict(list)
    for line in io.StringIO(text):
        row = line.rstrip("\n").split("\t")
        if len(row) >= 11 and PIN.match(row[1]):
            by_pin[row[1]].append(row)
    return by_pin


def build(postal, localities, by_state_key, city_names):
    kept, stats = [], collections.Counter()
    for pin in sorted(postal):
        rows = postal[pin]
        state = rows[0][4]
        matches = {}
        for row in rows:
            for variant in name_variants(row[2]):
                for locality_id in by_state_key.get((state, variant), ()):
                    matches.setdefault(locality_id, row[2])
        if not matches:
            stats["no_locality_named"] += 1
            continue
        if len(matches) > 1:
            stats["several_localities_named"] += 1
            continue
        locality_id, office = next(iter(matches.items()))
        name, place_id, lat, lng = localities[locality_id]
        try:
            pin_lat, pin_lng = float(rows[0][9]), float(rows[0][10])
        except ValueError:
            stats["no_point"] += 1
            continue
        district = key(rows[0][5])
        if district not in city_names.get(place_id, ()) and km(pin_lat, pin_lng, lat, lng) > MAX_SANITY_KM:
            stats["other_district_too_far"] += 1
            continue
        kept.append((pin, place_id, locality_id, office))
        stats["kept"] += 1
    return kept, stats


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--zip", required=True, help="GeoNames IN.zip (or the extracted IN.txt)")
    parser.add_argument("--geo-dir", default=GEO_DIR)
    parser.add_argument("--sample", nargs="*", default=["560066", "560100", "400076", "500081", "411057"])
    args = parser.parse_args()
    localities, by_state_key = load_localities(args.geo_dir)
    postal = read_postal(args.zip)
    kept, stats = build(postal, localities, by_state_key, load_city_names(args.geo_dir))
    with open(os.path.join(args.geo_dir, OUT), "w", encoding="utf-8", newline="\n") as out:
        out.write("# PIN code -> the one locality its post offices name (GEO-10B). Generated by scripts/build-pincodes.py;"
                  " do not edit by hand.\n")
        out.write("# Source: GeoNames postal codes, https://download.geonames.org/export/zip/IN.zip ; License: CC BY 4.0,"
                  " https://creativecommons.org/licenses/by/4.0/ ; localities: OpenStreetMap (ODbL) and curated-localities.tsv\n")
        out.write("pin\tplaceId\tlocalityId\tpostOffice\n")
        for row in kept:
            out.write("\t".join(row) + "\n")
    print(f"pins={len(postal)} " + " ".join(f"{name}={count}" for name, count in sorted(stats.items())), file=sys.stderr)
    found = {row[0]: row for row in kept}
    for pin in args.sample:
        row = found.get(pin)
        print(f"sample {pin}: " + (f"{localities[row[2]][0]} ({row[2]}, place {row[1]}, via '{row[3]}')" if row
                                   else "left out"), file=sys.stderr)


if __name__ == "__main__":
    main()
