#!/usr/bin/env bash
# Proves the fingerprint migration against a disposable database: dry-run writes nothing, apply updates only
# unique stale jobs and records collision conflicts, a second apply is idempotent, and resolving the conflict
# lets a rerun upgrade the survivor. Recruiter and application-referenced jobs are never changed.
set -euo pipefail

: "${MONGODB_URI:?Set MONGODB_URI to a disposable local Mongo server URI; this harness drops its test database}"
script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
backend_dir=$(cd "$script_dir/.." && pwd)
db_name="jobportal_m11b_verify"
db_uri="${MONGODB_URI%/}/$db_name"
cleanup() { mongosh "$db_uri" --quiet --eval 'db.dropDatabase()' >/dev/null 2>&1 || true; }
trap cleanup EXIT
cleanup

if [[ -z "${FINGERPRINT_MIGRATION_JAR:-}" ]]; then
  (cd "$backend_dir" && ./mvnw -q -DskipTests package)
  FINGERPRINT_MIGRATION_JAR=$(find "$backend_dir/target" -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' | head -n 1)
fi
export FINGERPRINT_MIGRATION_JAR
migrate() {
  MONGODB_URI="$db_uri" JWT_SECRET=test-only-jwt-secret-that-is-at-least-thirty-two-characters \
    ADZUNA_APP_ID=test-only ADZUNA_APP_KEY=test-only bash "$script_dir/migrate-fingerprints.sh" "$@"
}
snapshot() { mongosh "$db_uri" --quiet --eval 'EJSON.stringify(db.jobs.find().sort({_id:1}).toArray().concat(db.aggregation_conflicts.find().sort({_id:1}).toArray()))'; }

mongosh "$db_uri" --quiet --eval '
  const listing = (identity) => ({identity, provider: identity.split(":")[0], externalId: identity.slice(identity.indexOf(":") + 1),
    applicationUrl: "https://jobs.example.test/" + identity, active: true,
    firstSeenAt: new Date("2026-09-01T00:00:00Z"), lastSeenAt: new Date("2026-10-01T00:00:00Z"), consecutiveMissingRuns: 0});
  const job = (id, company, title, location, fingerprint, identity) => ({_id: id, company, title, location, fingerprint,
    recruiterId: null, active: true, source: identity.split(":")[0], externalId: identity.slice(identity.indexOf(":") + 1),
    sourceIdentities: [identity], sourceListings: [listing(identity)]});
  db.jobs.insertMany([
    job("bangalore", "Razorpay Software Pvt Ltd", "Backend Engineer", "Bangalore", "v1-bangalore", "greenhouse:rzp:1"),
    job("bengaluru", "Razorpay", "Backend Engineer", "Bengaluru", "v1-bengaluru", "adzuna:adz-1"),
    job("solo", "Acme", "Engineer", "Pune", "v1-solo", "lever:acme:1"),
    job("referenced", "Globex", "Analyst", "Bombay", "v1-referenced", "lever:globex:1"),
    {_id: "recruiter", company: "Acme", title: "Engineer", location: "Pune", fingerprint: "v1-recruiter", recruiterId: "recruiter-1", active: true}
  ]);
  db.applications.insertOne({jobId: "referenced", userId: "candidate-1"});
'

before=$(snapshot)
set +e; dry_output=$(migrate); dry_status=$?; set -e
printf '%s\n' "$dry_output" | grep FINGERPRINT_MIGRATION_REPORT
[[ "$dry_status" -eq 2 ]] || { echo "dry-run expected status 2, got $dry_status"; exit 1; }
grep -q '"mode":"dry-run"' <<< "$dry_output"
grep -q '"planned":1' <<< "$dry_output"
grep -q '"collisions":2' <<< "$dry_output"
grep -q '"skippedApplicationReferenced":1' <<< "$dry_output"
[[ "$(snapshot)" == "$before" ]] || { echo "dry-run changed the database"; exit 1; }

set +e; apply_output=$(migrate --apply); apply_status=$?; set -e
printf '%s\n' "$apply_output" | grep FINGERPRINT_MIGRATION_REPORT
[[ "$apply_status" -eq 2 ]] || { echo "apply expected status 2, got $apply_status"; exit 1; }
grep -q '"updated":1' <<< "$apply_output"
grep -q '"conflictsRecorded":1' <<< "$apply_output"
mongosh "$db_uri" --quiet --eval '
  const solo = db.jobs.findOne({_id: "solo"});
  if (solo.fingerprintVersion !== 2 || solo.fingerprint === "v1-solo" || solo.fingerprint.length !== 64) throw new Error("solo was not migrated");
  for (const [id, fingerprint] of [["bangalore", "v1-bangalore"], ["bengaluru", "v1-bengaluru"], ["referenced", "v1-referenced"], ["recruiter", "v1-recruiter"]]) {
    const job = db.jobs.findOne({_id: id});
    if (job.fingerprint !== fingerprint || job.fingerprintVersion !== undefined) throw new Error(id + " changed");
  }
  const conflicts = db.aggregation_conflicts.find().toArray();
  if (conflicts.length !== 1 || conflicts[0].type !== "FINGERPRINT_MIGRATION" || conflicts[0].status !== "OPEN"
      || JSON.stringify(conflicts[0].jobIds.sort()) !== JSON.stringify(["bangalore", "bengaluru"])) throw new Error("conflict missing");
'

after_apply=$(mongosh "$db_uri" --quiet --eval 'EJSON.stringify(db.jobs.find().sort({_id:1}).toArray())')
set +e; second_output=$(migrate --apply); second_status=$?; set -e
[[ "$second_status" -eq 2 ]] || { echo "second apply expected status 2, got $second_status"; exit 1; }
grep -q '"updated":0' <<< "$second_output"
grep -q '"planned":0' <<< "$second_output"
[[ "$(mongosh "$db_uri" --quiet --eval 'EJSON.stringify(db.jobs.find().sort({_id:1}).toArray())')" == "$after_apply" ]] || { echo "second apply changed jobs"; exit 1; }
[[ "$(mongosh "$db_uri" --quiet --eval 'db.aggregation_conflicts.countDocuments()')" == "1" ]]

# An ADMIN resolution removes the duplicate; mimic its final state, then the rerun upgrades the survivor.
mongosh "$db_uri" --quiet --eval 'db.jobs.deleteOne({_id: "bangalore"}); db.aggregation_conflicts.updateMany({}, {$set: {status: "RESOLVED"}});'
set +e; rerun_output=$(migrate --apply); rerun_status=$?; set -e
[[ "$rerun_status" -eq 2 ]] || { echo "rerun expected status 2 (application-referenced job held), got $rerun_status"; exit 1; }
grep -q '"updated":1' <<< "$rerun_output"
grep -q '"collisions":0' <<< "$rerun_output"
mongosh "$db_uri" --quiet --eval '
  const survivor = db.jobs.findOne({_id: "bengaluru"});
  if (survivor.fingerprintVersion !== 2 || survivor.fingerprint.length !== 64) throw new Error("survivor was not migrated");
  if (db.jobs.findOne({_id: "recruiter"}).fingerprint !== "v1-recruiter") throw new Error("recruiter changed");
'
printf 'fingerprint migration verification passed\n'
