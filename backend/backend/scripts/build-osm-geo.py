#!/usr/bin/env python3
"""Build offline OpenStreetMap localities and company offices for India (GEO-8A). Laptop only; never on EC2.

Usage (from backend/backend):
    pip install osmium==4.3.2          # pyosmium; needs libexpat (Debian: apt install libexpat1)
    python3 scripts/build-osm-geo.py [--pbf PATH] [--cache-dir DIR] [--min-population N]

Without --pbf it downloads https://download.geofabrik.de/asia/india-latest.osm.pbf (about 1.5 GB) into --cache-dir
(default ~/.cache/jobportal-osm) and reuses it while it is less than a day old. On a host without pip, run it in a
throwaway container:
    docker run --rm -v "$PWD":/work -v ~/.cache/jobportal-osm:/cache -w /work python:3.12-slim sh -c \
      'apt-get update -qq && apt-get install -y -qq libexpat1 >/dev/null && pip install -q osmium==4.3.2 \
       && python3 scripts/build-osm-geo.py --pbf /cache/india-latest.osm.pbf'

Writes, under src/main/resources/geo/ (OpenStreetMap data, ODbL 1.0; see ATTRIBUTION.md):
  in-localities.tsv    place=suburb|neighbourhood|quarter and named tech hubs, one row per (city, name)
  in-osm-offices.tsv   named company offices and office/commercial buildings
A file larger than 4 MB is written gzipped (.tsv.gz) instead, and the plain file is removed. Output is sorted, so a
rebuild diffs cleanly.

Every object gets a parent city from in-places.tsv: the nearest place with population >= --min-population
(default 100,000) within 40 km, preferring places in the object's own state (OpenStreetMap admin_level=4 boundaries,
only consulted when the candidate cities span more than one state), among places that are not absorbed into a bigger city. A place is absorbed when it is a city section
(feature code PPLX), when its name is a place-aliases.tsv alias of another place ("New Delhi" -> Delhi), or when a
city of >= 1,000,000 people with >= 1.5x its population lies within 30 km, or a place-aliases.tsv target (a city
listings name) in the same state lies within 10 km ("Kanayannur" -> Kochi), unless it is a district or state seat
(PPLA, PPLA2) or a place-aliases.tsv target. Listings name the big city ("Hinjewadi, Pune", "Dwarka, New Delhi"), so
without this GeoNames' large sub-places (Rohini, Najafgarh, Kukatpally, Pimpri-Chinchwad, ...) or a nearer city across
a state line (Dwarka is closer to Gurugram's centre than to Delhi's) would own the localities. Objects with no parent
are dropped.

Geometry: nodes use their location; closed ways use the polygon centroid; multipolygon/boundary relations use the
area-weighted centroid of their closed outer ways (or the mean of their outer nodes when the rings are split across
ways). Coordinates are rounded to 4 decimals (about 11 m).
"""
import argparse
import gzip
import math
import os
import re
import sys
import time
import unicodedata
import urllib.request

PBF_URL = "https://download.geofabrik.de/asia/india-latest.osm.pbf"
GEO_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources", "geo")
PARENT_RADIUS_KM = 40.0
ABSORB_RADIUS_KM = 30.0
ABSORB_MIN_POPULATION = 1_000_000
ABSORB_RATIO = 1.5
ALIAS_TARGET_RADIUS_KM = 10.0
GZIP_ABOVE_BYTES = 4 * 1024 * 1024
LOCALITY_PLACES = ("suburb", "neighbourhood", "quarter")
TECH_HUB = re.compile(r"(?i)\b(tech|it|software|cyber|knowledge|info|business)\s*(park|city|hub|centre|center)\b"
                      r"|\bsez\b|special economic zone")
TECH_HUB_LANDUSE = ("commercial", "industrial", "retail")
OFFICE_KINDS = ("company", "it", "telecommunication", "consulting", "financial", "insurance", "research", "engineer",
                "advertising_agency", "logistics", "employment_agency")
OFFICE_EXCLUDED = ("government", "diplomatic", "political_party", "religion", "coworking", "estate_agent")
OFFICE_BUILDINGS = ("office", "commercial")
KIND_RANK = {"suburb": 0, "quarter": 1, "neighbourhood": 1, "techhub": 1}
TYPE_ORDER = {"n": 0, "w": 1, "r": 2}
LOCALITY_HEADER = ["localityId", "name", "nameEn", "altNames", "placeId", "lat", "lng", "kind", "osmTags"]
OFFICE_HEADER = ["osmRef", "name", "nameEn", "brand", "operator", "kind", "placeId", "lat", "lng"]
WATCHED_KEYS = ("place", "office", "building", "landuse")


def key(value):
    """Python twin of Gazetteer.key(): NFKD, no marks, lower case, & -> and, punctuation -> spaces."""
    if not value:
        return ""
    folded = "".join(ch for ch in unicodedata.normalize("NFKD", value) if not unicodedata.combining(ch))
    folded = re.sub(r"['.’]", "", folded.lower().replace("&", " and "))
    return " ".join(re.sub(r"[\W_]+", " ", folded).split())


def clean(value):
    return " ".join((value or "").replace("\t", " ").replace("|", " ").split())


def haversine_km(lat1, lng1, lat2, lng2):
    p = math.pi / 180
    a = (math.sin((lat2 - lat1) * p / 2) ** 2
         + math.cos(lat1 * p) * math.cos(lat2 * p) * math.sin((lng2 - lng1) * p / 2) ** 2)
    return 2 * 6371.0088 * math.asin(min(1.0, math.sqrt(a)))


# ---------------------------------------------------------------------------------------------------------------
# Parent cities

def read_tsv(path):
    with open(path, encoding="utf-8") as handle:
        return [line.rstrip("\n").split("\t") for line in handle if line.strip() and not line.startswith("#")]


def aliases():
    """(alias key, target key, admin1) rows of place-aliases.tsv."""
    rows = []
    for row in read_tsv(os.path.join(GEO_DIR, "place-aliases.tsv")):
        if len(row) >= 2:
            rows.append((key(row[0]), key(row[1]), row[2].strip() if len(row) > 2 else ""))
    return rows


def grid_of(items, lat_index, lng_index):
    grid = {}
    for item in items:
        grid.setdefault((int(math.floor(item[lat_index])), int(math.floor(item[lng_index]))), []).append(item)
    return grid


def nearby(grid, lat, lng):
    cell_lat, cell_lng = int(math.floor(lat)), int(math.floor(lng))
    for d_lat in (-1, 0, 1):
        for d_lng in (-1, 0, 1):
            yield from grid.get((cell_lat + d_lat, cell_lng + d_lng), ())


class Cities:
    """Candidate parent cities and a state lookup, both on a 1-degree grid."""

    def __init__(self, min_population):
        rows = read_tsv(os.path.join(GEO_DIR, "in-places.tsv"))[1:]
        every = [(r[0], r[1], r[3], float(r[4]), float(r[5]), int(r[6]), r[7]) for r in rows]
        big = [c for c in every if c[5] >= min_population]
        alias_rows = aliases()
        targets = {(target, admin1) for _, target, admin1 in alias_rows}
        aliased_away = {(alias, admin1) for alias, target, admin1 in alias_rows if alias != target}
        metros = [c for c in big if c[5] >= ABSORB_MIN_POPULATION]
        named = [c for c in big if (key(c[1]), c[2]) in targets or (key(c[1]), "") in targets]
        self.cities, self.absorbed = [], []
        for city in big:
            place_id, name, admin1, lat, lng, population, feature = city
            name_key = key(name)
            protected = feature in ("PPLA", "PPLA2") or (name_key, admin1) in targets or (name_key, "") in targets
            if (name_key, admin1) in aliased_away or (name_key, "") in aliased_away:
                self.absorbed.append(city)
                continue
            near_bigger = any(m[0] != place_id and m[5] >= ABSORB_RATIO * population
                              and haversine_km(lat, lng, m[3], m[4]) <= ABSORB_RADIUS_KM for m in metros)
            pplx_inside = feature == "PPLX" and any(o[0] != place_id and o[5] > population
                                                   and haversine_km(lat, lng, o[3], o[4]) <= ABSORB_RADIUS_KM for o in big)
            near_named = any(n[0] != place_id and n[2] == admin1
                             and haversine_km(lat, lng, n[3], n[4]) <= ALIAS_TARGET_RADIUS_KM for n in named)
            if not protected and (near_bigger or pplx_inside or near_named):
                self.absorbed.append(city)
            else:
                self.cities.append(city)
        self.grid = grid_of(self.cities, 3, 4)
        self.every = grid_of(every, 3, 4)
        self.names = {city[0]: key(city[1]) for city in self.cities}

    def parent(self, lat, lng, states):
        best = {}
        for city in nearby(self.grid, lat, lng):
            km = haversine_km(lat, lng, city[3], city[4])
            if km > PARENT_RADIUS_KM:
                continue
            current = best.get(city[2])
            if current is None or km < current[1] or (km == current[1] and int(city[0]) < int(current[0][0])):
                best[city[2]] = (city, km)
        if not best:
            return None
        if len(best) > 1:
            admin1 = states.admin1(lat, lng)
            if admin1 in best:
                return best[admin1][0]
        return min(best.values(), key=lambda entry: (entry[1], int(entry[0][0])))[0]


class States:
    """OpenStreetMap state/UT polygons (admin_level=4) mapped to gazetteer admin1 codes, decimated for speed."""

    MAX_STEP_KM = 0.25

    def __init__(self, polygons):
        names = {}
        for row in read_tsv(os.path.join(GEO_DIR, "state-aliases.tsv")):
            if len(row) >= 2:
                names[key(row[0])] = row[1].strip()
        for row in read_tsv(os.path.join(GEO_DIR, "in-admin1.tsv"))[1:]:
            names.setdefault(key(row[1]), row[0])
        self.polygons = []
        self.unmapped = []
        for tags, rings in polygons:
            code = None
            for tag in ("name:en", "name", "official_name:en", "alt_name:en", "short_name:en"):
                code = names.get(key(tags.get(tag, "")))
                if code:
                    break
            if code is None:
                self.unmapped.append(tags.get("name:en") or tags.get("name") or "?")
                continue
            for ring in rings:
                ring = decimate(ring, self.MAX_STEP_KM)
                lats = [p[0] for p in ring]
                lngs = [p[1] for p in ring]
                self.polygons.append((code, ring, (min(lats), max(lats), min(lngs), max(lngs))))

    def admin1(self, lat, lng):
        """Code of a state whose outer ring contains the point (holes are ignored), else None."""
        for code, ring, (lat0, lat1, lng0, lng1) in self.polygons:
            if lat0 <= lat <= lat1 and lng0 <= lng <= lng1 and inside(ring, lat, lng):
                return code
        return None


def decimate(ring, step_km):
    """Drops vertices closer than step_km to the last kept one (borders stay within about step_km)."""
    kept = [ring[0]]
    for point in ring[1:-1]:
        if haversine_km(kept[-1][0], kept[-1][1], point[0], point[1]) >= step_km:
            kept.append(point)
    kept.append(ring[-1])
    return kept


def inside(ring, lat, lng):
    result = False
    j = len(ring) - 1
    for i in range(len(ring)):
        yi, xi = ring[i]
        yj, xj = ring[j]
        if (yi > lat) != (yj > lat) and lng < (xj - xi) * (lat - yi) / (yj - yi) + xi:
            result = not result
        j = i
    return result


def assemble_rings(ways):
    """Joins way node lists end to end into closed rings; unclosable pieces are dropped."""
    pending = [list(way) for way in ways if len(way) >= 2]
    rings = []
    while pending:
        ring = pending.pop()
        progress = True
        while ring[0] != ring[-1] and progress:
            progress = False
            for index, way in enumerate(pending):
                if way[0] == ring[-1]:
                    ring += way[1:]
                elif way[-1] == ring[-1]:
                    ring += way[-2::-1]
                elif way[-1] == ring[0]:
                    ring = way[:-1] + ring
                elif way[0] == ring[0]:
                    ring = way[:0:-1] + ring
                else:
                    continue
                pending.pop(index)
                progress = True
                break
        if ring[0] == ring[-1] and len(ring) >= 4:
            rings.append(ring)
    return rings


# ---------------------------------------------------------------------------------------------------------------
# Geometry

def ring_centroid(points):
    """Centroid and planar area (km^2, equirectangular) of a closed ring of (lat, lng); area 0 if degenerate."""
    if len(points) < 4:
        return None, 0.0
    lat0 = sum(p[0] for p in points) / len(points)
    kx = 111.32 * math.cos(math.radians(lat0))
    ky = 110.57
    xs = [p[1] * kx for p in points]
    ys = [p[0] * ky for p in points]
    area2 = cx = cy = 0.0
    for i in range(len(points) - 1):
        cross = xs[i] * ys[i + 1] - xs[i + 1] * ys[i]
        area2 += cross
        cx += (xs[i] + xs[i + 1]) * cross
        cy += (ys[i] + ys[i + 1]) * cross
    if abs(area2) < 1e-12:
        return None, 0.0
    return (cy / (3 * area2) / ky, cx / (3 * area2) / kx), abs(area2) / 2


def mean_point(points):
    if not points:
        return None
    return sum(p[0] for p in points) / len(points), sum(p[1] for p in points) / len(points)


# ---------------------------------------------------------------------------------------------------------------
# Classification

def tags_of(obj):
    return {tag.k: tag.v for tag in obj.tags}


def locality_kind(tags):
    name = tags.get("name")
    if not name:
        return None, None
    place = tags.get("place")
    if place in LOCALITY_PLACES:
        return place, f"place={place}"
    if TECH_HUB.search(name) or TECH_HUB.search(tags.get("name:en", "")):
        if tags.get("landuse") in TECH_HUB_LANDUSE:
            return "techhub", f"landuse={tags['landuse']}"
        if "office" in tags:
            return "techhub", f"office={tags['office']}"
        if "place" in tags:
            return "techhub", f"place={tags['place']}"
    return None, None


def office_kind(tags):
    if not (tags.get("name") or tags.get("brand") or tags.get("operator")):
        return None
    office = tags.get("office")
    if office in OFFICE_EXCLUDED or tags.get("amenity") in ("bank", "atm") or "shop" in tags:
        return None
    if office in OFFICE_KINDS:
        return f"office={office}"
    if tags.get("building") in OFFICE_BUILDINGS:
        return f"building={tags['building']}"
    return None


def interesting(tags):
    return locality_kind(tags)[0] is not None or office_kind(tags) is not None


# ---------------------------------------------------------------------------------------------------------------
# Extraction (three passes, memory-light: only referenced node locations are kept)

def extract(pbf):
    import osmium

    started = time.time()
    header = osmium.io.Reader(pbf, osmium.osm.osm_entity_bits.NOTHING).header()
    timestamp = header.get("osmosis_replication_timestamp") or header.get("timestamp") or "unknown"

    watched = osmium.filter.KeyFilter(*WATCHED_KEYS)
    objects = []  # (type, id, tags, geometry spec)
    state_relations = []  # (tags, outer way ids)
    rel_ways = {}
    for rel in osmium.FileProcessor(pbf, osmium.osm.RELATION).with_filter(osmium.filter.KeyFilter(*WATCHED_KEYS, "boundary")):
        tags = tags_of(rel)
        if tags.get("type") not in ("multipolygon", "boundary"):
            continue
        outers = [m.ref for m in rel.members if m.type == "w" and m.role in ("outer", "")]
        if not outers:
            continue
        if tags.get("boundary") == "administrative" and tags.get("admin_level") == "4":
            state_relations.append((tags, outers))
        elif interesting(tags):
            objects.append(("r", rel.id, tags, outers))
        else:
            continue
        for way_id in outers:
            rel_ways[way_id] = None
    print(f"relations: {len(objects)} kept, {len(state_relations)} states ({time.time() - started:.0f}s)", file=sys.stderr)

    nodes_needed = osmium.IdTracker()
    way_refs = {}
    way_count = 0
    for way in osmium.FileProcessor(pbf, osmium.osm.WAY):
        member = way.id in rel_ways
        tagged = any(t.k in WATCHED_KEYS for t in way.tags)
        if not member and not tagged:
            continue
        tags = tags_of(way)
        standalone = tagged and way.is_closed() and interesting(tags)
        if not member and not standalone:
            continue
        refs = [n.ref for n in way.nodes]
        for ref in refs:
            nodes_needed.add_node(ref)
        way_refs[way.id] = refs
        if standalone:
            objects.append(("w", way.id, tags, refs))
            way_count += 1
    print(f"ways: {way_count} kept, {len(way_refs)} loaded ({time.time() - started:.0f}s)", file=sys.stderr)

    locations = {}
    for node in osmium.FileProcessor(pbf, osmium.osm.NODE).with_filter(nodes_needed.id_filter()):
        locations[node.id] = (node.location.lat, node.location.lon)
    node_count = 0
    for node in osmium.FileProcessor(pbf, osmium.osm.NODE).with_filter(watched):
        tags = tags_of(node)
        if interesting(tags) and node.location.valid():
            objects.append(("n", node.id, tags, (node.location.lat, node.location.lon)))
            node_count += 1
    print(f"nodes: {node_count} kept, {len(locations)} way vertices ({time.time() - started:.0f}s)", file=sys.stderr)

    polygons = []
    for tags, outers in state_relations:
        rings = assemble_rings([way_refs[w] for w in outers if w in way_refs])
        polygons.append((tags, [[locations[r] for r in ring if r in locations] for ring in rings]))
    located = []
    for kind, osm_id, tags, spec in objects:
        if kind == "n":
            located.append((kind, osm_id, tags, spec, 0.0))
            continue
        if kind == "w":
            ring = [locations[r] for r in spec if r in locations]
            point, area = ring_centroid(ring) if len(ring) == len(spec) else (None, 0.0)
            located.append((kind, osm_id, tags, point or mean_point(ring), area))
            continue
        total_area, wx, wy, loose = 0.0, 0.0, 0.0, []
        for way_id in spec:
            ring = [locations[r] for r in way_refs.get(way_id, ()) if r in locations]
            refs = way_refs.get(way_id, ())
            if refs and refs[0] == refs[-1] and len(ring) == len(refs):
                point, area = ring_centroid(ring)
                if point and area > 0:
                    total_area += area
                    wx += point[0] * area
                    wy += point[1] * area
                    continue
            loose.extend(ring)
        if total_area > 0:
            located.append((kind, osm_id, tags, (wx / total_area, wy / total_area), total_area))
        else:
            located.append((kind, osm_id, tags, mean_point(loose), 0.0))
    return timestamp, [entry for entry in located if entry[3] is not None], States(polygons)


# ---------------------------------------------------------------------------------------------------------------
# Output

def alt_names(tags, name, name_en):
    seen = {key(name), key(name_en)}
    kept = []
    for tag in ("alt_name", "old_name", "short_name", "alt_name:en", "old_name:en"):
        for value in (tags.get(tag) or "").split(";"):
            value = clean(value)
            if value and key(value) and key(value) not in seen and len(value) <= 80:
                seen.add(key(value))
                kept.append(value)
    return "|".join(kept[:10])


def build_rows(objects, cities, states):
    localities = {}
    offices = []
    for kind, osm_id, tags, (lat, lng), area in objects:
        city = cities.parent(lat, lng, states)
        if city is None:
            continue
        place_id = city[0]
        ref = f"osm-{kind}{osm_id}"
        name = clean(tags.get("name"))
        name_en = clean(tags.get("name:en"))
        locality, osm_tag = locality_kind(tags)
        if locality and key(name) and key(name) != cities.names[place_id] and key(name_en) != cities.names[place_id]:
            row = [ref, name, name_en, alt_names(tags, name, name_en), place_id, f"{lat:.4f}", f"{lng:.4f}", locality,
                   osm_tag]
            rank = (KIND_RANK[locality], -area, TYPE_ORDER[kind], osm_id)
            slot = (place_id, key(name))
            if slot not in localities or rank < localities[slot][0]:
                localities[slot] = (rank, row)
        office = office_kind(tags)
        if office:
            offices.append([ref, name, name_en, clean(tags.get("brand")), clean(tags.get("operator")), office, place_id,
                            f"{lat:.4f}", f"{lng:.4f}"])
    locality_rows = [row for _, row in localities.values()]
    locality_rows.sort(key=lambda r: (int(r[4]), key(r[1]), r[1], TYPE_ORDER[r[0][4]], int(r[0][5:])))
    offices.sort(key=lambda r: (int(r[6]), key(r[1] or r[3] or r[4]), TYPE_ORDER[r[0][4]], int(r[0][5:])))
    return locality_rows, offices


def write(out_dir, name, header, rows, timestamp, description):
    lines = [
        f"# {description}",
        f"# Source: OpenStreetMap, {PBF_URL} (Geofabrik extract), replication timestamp {timestamp}",
        "# License: Open Database License (ODbL) 1.0, https://opendatacommons.org/licenses/odbl/1-0/ ;"
        " (c) OpenStreetMap contributors",
        "# Generated by backend/backend/scripts/build-osm-geo.py; do not edit by hand.",
        "\t".join(header),
    ] + ["\t".join(row) for row in rows]
    data = ("\n".join(lines) + "\n").encode("utf-8")
    plain = os.path.join(out_dir, name)
    zipped = plain + ".gz"
    for stale in (plain, zipped):
        if os.path.exists(stale):
            os.remove(stale)
    if len(data) > GZIP_ABOVE_BYTES:
        # mtime=0 keeps the gzip bytes identical for identical content.
        with open(zipped, "wb") as raw, gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as out:
            out.write(data)
        print(f"{name}.gz: {len(rows)} rows, {len(data)} bytes raw, {os.path.getsize(zipped)} gzipped", file=sys.stderr)
    else:
        with open(plain, "wb") as out:
            out.write(data)
        print(f"{name}: {len(rows)} rows, {len(data)} bytes", file=sys.stderr)


def download(cache_dir):
    os.makedirs(cache_dir, exist_ok=True)
    path = os.path.join(cache_dir, "india-latest.osm.pbf")
    if os.path.exists(path) and time.time() - os.path.getmtime(path) < 86_400:
        print(f"using cached {path}", file=sys.stderr)
        return path
    print(f"downloading {PBF_URL} (about 1.5 GB) ...", file=sys.stderr)
    partial = path + ".part"
    with urllib.request.urlopen(PBF_URL, timeout=600) as response, open(partial, "wb") as out:
        while True:
            chunk = response.read(1 << 20)
            if not chunk:
                break
            out.write(chunk)
    os.replace(partial, path)
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--pbf", help="an already downloaded india-latest.osm.pbf")
    parser.add_argument("--cache-dir", default=os.path.expanduser("~/.cache/jobportal-osm"))
    parser.add_argument("--out-dir", default=GEO_DIR, help="where to write the TSVs (default: the geo resources)")
    parser.add_argument("--min-population", type=int, default=100_000,
                        help="parent city population floor (300000 if the heap budget is exceeded)")
    args = parser.parse_args()
    pbf = args.pbf or download(args.cache_dir)
    cities = Cities(args.min_population)
    print(f"parent cities: {len(cities.cities)} (absorbed {len(cities.absorbed)}: "
          f"{', '.join(sorted(c[1] for c in cities.absorbed)[:40])} ...)", file=sys.stderr)
    timestamp, objects, states = extract(pbf)
    print(f"state polygons: {len(states.polygons)} rings" + (f"; unmapped: {', '.join(states.unmapped)}" if states.unmapped else ""),
          file=sys.stderr)
    localities, offices = build_rows(objects, cities, states)
    write(args.out_dir, "in-localities.tsv", LOCALITY_HEADER, localities, timestamp,
          "OpenStreetMap localities (suburb, neighbourhood, quarter, tech hubs) with their gazetteer parent city.")
    write(args.out_dir, "in-osm-offices.tsv", OFFICE_HEADER, offices, timestamp,
          "OpenStreetMap company offices and office/commercial buildings with their gazetteer parent city.")
    print(f"extract timestamp: {timestamp}; localities={len(localities)} offices={len(offices)}", file=sys.stderr)


if __name__ == "__main__":
    main()
