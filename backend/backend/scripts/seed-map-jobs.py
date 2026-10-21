#!/usr/bin/env python3
"""Seed ~2,000 map-ready jobs for LOCAL load tests only (GEO-3). Never run against production.

Usage (from the repo root, with the Compose stack running):
    python3 backend/backend/scripts/seed-map-jobs.py | docker compose exec -T mongodb mongosh --quiet jobportal

Prints a mongosh script that removes earlier seed jobs (seedBatch "geo-load") and inserts jobs across the 30 most
populous gazetteer places, with a few remote and India-wide jobs. Place ids come from in-places.tsv, so they
are real gazetteer ids. Restart the backend (or wait for the snapshot refresh) afterwards.

--offices N also pins one "Acme" office (1 km from the centre) in each of the N biggest places, so the GEO-7D k6
office leg has offices to request. Seeded offices carry seedBatch "geo-load" and are replaced on every run.

--realistic N (GEO-8) adds N jobs (default 0) cycling through hand-written location strings shaped like real ATS and
Adzuna listings ("Whitefield, Bengaluru, Karnataka, India", "Noida Sector 62", ...). They are stored unresolved
(listing `rawLocation`, geo version "seed"; Adzuna ones keep a provider point), so the backend's own resolver places them
through the re-resolution job. Locally that job only runs with scheduling on; see docs/geo-operations.md, "Precision
report", for a Compose override that enables it without calling any provider.
"""
import argparse
import json
import os
import random

PLACES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources", "geo", "in-places.tsv")
ROLES = ["Java Engineer", "Python Developer", "Data Analyst", "Product Manager", "DevOps Engineer", "React Developer",
         "QA Engineer", "Business Analyst", "Sales Executive", "Support Specialist"]
LEVELS = ["Junior", "Senior", "Lead", "Staff", "Associate"]
COMPANIES = ["Acme", "Globex", "Initech", "Umbrella", "Hooli", "Stark", "Wayne", "Wonka"]
# (location, provider, company, Adzuna point or None): strings as providers send them.
REALISTIC = [
    ("Whitefield, Bengaluru, Karnataka, India", "greenhouse", "Infosys", None),
    ("Bengaluru, Karnataka, India", "greenhouse", "Globex", None),
    ("Koramangala, Bangalore", "lever", "Hooli", None),
    ("Koramangala 5th Block, Bangalore", "lever", "Hooli", None),
    ("Electronic City, Bengaluru", "lever", "Wipro", None),
    ("Electronic City Phase 1, Bangalore, Karnataka", "workable", "Wipro", None),
    ("Bellandur, Bengaluru", "ashby", "Initech", None),
    ("Manyata Tech Park, Bangalore", "greenhouse", "Stark", None),
    ("Bangalore, Whitefield", "smartrecruiters", "Wayne", None),
    ("HSR Layout, Bengaluru", "lever", "Umbrella", None),
    ("Whitefield", "workable", "Acme", None),
    ("Gachibowli, Hyderabad, Telangana", "greenhouse", "Initech", None),
    ("HITEC City, Hyderabad", "lever", "Microsoft", None),
    ("Hitech City", "workable", "Globex", None),
    ("Madhapur, Hyderabad", "ashby", "Hooli", None),
    ("Hyderabad, Telangana, India", "ashby", "Stark", None),
    ("Hinjewadi, Pune", "workable", "Wayne", None),
    ("Hinjewadi Phase 2, Pune, Maharashtra", "greenhouse", "Infosys", None),
    ("Hinjewadi / Kharadi, Pune", "lever", "Umbrella", None),
    ("Kharadi, Pune", "lever", "Wonka", None),
    ("Pune, Maharashtra, India", "greenhouse", "Acme", None),
    ("Whitefield, Pune", "smartrecruiters", "Globex", None),
    ("Noida Sector 62", "smartrecruiters", "Umbrella", None),
    ("Sector-62, Noida", "workable", "Initech", None),
    ("Sec 62 Noida, Uttar Pradesh", "lever", "Hooli", None),
    ("Gurgaon Cyber City", "lever", "Wonka", None),
    ("Cyber City, Gurugram, Haryana", "greenhouse", "Stark", None),
    ("Udyog Vihar Phase 4, Gurgaon", "ashby", "Wayne", None),
    ("Gurgaon, Haryana", "lever", "Wonka", None),
    ("Okhla Phase II, New Delhi", "workable", "Acme", None),
    ("Connaught Place, New Delhi, Delhi", "greenhouse", "Globex", None),
    ("New Delhi, Delhi, India", "greenhouse", "Initech", None),
    ("Delhi NCR", "workable", "Acme", None),
    ("NCR", "workable", "Hooli", None),
    ("Powai, Mumbai, Maharashtra", "greenhouse", "Globex", None),
    ("Bandra Kurla Complex, Mumbai", "lever", "Stark", None),
    ("Andheri East, Mumbai", "ashby", "Wayne", None),
    ("Mumbai, Maharashtra, India", "greenhouse", "Umbrella", None),
    ("Vashi, Navi Mumbai", "workable", "Wonka", None),
    ("Sholinganallur, Chennai", "greenhouse", "Initech", None),
    ("Perungudi, Chennai, Tamil Nadu", "lever", "Hooli", None),
    ("Chennai, Tamil Nadu, India", "ashby", "Acme", None),
    ("Sector V, Salt Lake, Kolkata", "smartrecruiters", "Globex", None),
    ("Infopark, Kochi, Kerala", "workable", "Stark", None),
    ("Whitefield, Bengaluru; Gachibowli, Hyderabad", "greenhouse", "Wayne", None),
    ("Bengaluru / Mumbai", "lever", "Umbrella", None),
    ("Sector 18", "workable", "Wonka", None),
    ("Delhi, NY", "greenhouse", "Acme", None),
    ("Remote - India", "lever", "Globex", None),
    ("Multiple Locations", "greenhouse", "Initech", None),
    # Adzuna: a point near Whitefield, one at Adzuna's own Bengaluru centroid, one in Gachibowli, one far from any locality.
    ("Bangalore", "adzuna", "Initech", (12.9790, 77.7480)),
    ("Bangalore", "adzuna", "Hooli", (12.9716, 77.5946)),
    ("Hyderabad", "adzuna", "Stark", (17.4400, 78.3480)),
    ("Bangalore", "adzuna", "Wayne", (12.8000, 77.3000)),
]


def top_places(count):
    with open(PLACES, encoding="utf-8") as tsv:
        rows = [line.rstrip("\n").split("\t") for line in tsv][1:]
    rows.sort(key=lambda row: -int(row[6]))
    seen, places = set(), []
    for row in rows:
        if row[2] in seen:
            continue
        seen.add(row[2])
        places.append((row[0], row[2], float(row[4]), float(row[5])))
        if len(places) == count:
            break
    return places


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--jobs", type=int, default=2000)
    parser.add_argument("--places", type=int, default=30)
    parser.add_argument("--offices", type=int, default=0)
    parser.add_argument("--realistic", type=int, default=0)
    args = parser.parse_args()
    random.seed(42)
    places = top_places(args.places)
    jobs = []
    for index in range(args.jobs):
        title = f"{random.choice(LEVELS)} {random.choice(ROLES)}"
        roll = random.random()
        if roll < 0.05:
            geo, location = {"status": "REMOTE", "placeIds": [], "remote": True, "version": "seed"}, "Remote"
        elif roll < 0.10:
            geo, location = {"status": "COUNTRY", "placeIds": [], "remote": False, "version": "seed"}, "India"
        else:
            # Weight towards the biggest cities, like real listings.
            place_id, name, _, _ = places[min(int(random.expovariate(0.15)), len(places) - 1)]
            geo, location = {"status": "PLACE", "placeIds": [place_id], "remote": False, "version": "seed"}, name
        jobs.append({
            "title": title, "description": f"{title} role. Seeded for local load tests.",
            "company": random.choice(COMPANIES), "location": location, "source": "seed", "active": True,
            "seedBatch": "geo-load", "createdAtOffsetMinutes": index,
            "sourceListings": [{"identity": f"seed:{index}", "provider": "seed", "active": True, "geo": geo}],
        })
    for index in range(args.realistic):
        location, provider, company, point = REALISTIC[index % len(REALISTIC)]
        listing = {"identity": f"seed-real:{index}", "provider": provider, "active": True, "rawLocation": location,
                   "geo": {"status": "UNRESOLVED", "placeIds": [], "remote": False, "version": "seed"}}
        if point:
            listing["geo"]["point"] = {"lat": point[0], "lng": point[1]}
        title = f"{random.choice(LEVELS)} {random.choice(ROLES)}"
        jobs.append({
            "title": title, "description": f"{title} role. Seeded for local geo checks.", "company": company,
            "location": location, "source": provider, "active": True, "seedBatch": "geo-load",
            "createdAtOffsetMinutes": args.jobs + index, "sourceListings": [listing],
        })
    print("db.jobs.deleteMany({seedBatch: 'geo-load'});")
    print("const now = Date.now();")
    print(f"const jobs = {json.dumps(jobs)};")
    print("jobs.forEach(j => { j.createdAt = new Date(now - j.createdAtOffsetMinutes * 60000); "
          "delete j.createdAtOffsetMinutes; });")
    print("const result = db.jobs.insertMany(jobs);")
    print("print('seeded ' + Object.keys(result.insertedIds).length + ' jobs');")
    offices = [{
        "companyKey": "acme", "company": "Acme", "label": f"Acme {name} seed office", "placeId": place_id,
        "lat": round(lat + 0.009, 5), "lng": lng, "enabled": True, "createdBy": "seed", "updatedBy": "seed",
        "seedBatch": "geo-load",
    } for place_id, name, lat, lng in places[:args.offices]]
    print("db.company_offices.deleteMany({seedBatch: 'geo-load'});")
    if offices:
        print(f"const offices = {json.dumps(offices)};")
        print("offices.forEach(o => { o.createdAt = new Date(now); o.updatedAt = new Date(now); });")
        print("db.company_offices.insertMany(offices);")
        print(f"print('seeded {len(offices)} offices');")


if __name__ == "__main__":
    main()
