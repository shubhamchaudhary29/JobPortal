#!/usr/bin/env bash
set -euo pipefail

: "${MONGODB_URI:?Set MONGODB_URI to a disposable local Mongo server URI; this harness drops its test database}"
db_name="jobportal_m1f_verify"
script_dir=$(cd "$(dirname "$0")" && pwd)
audit_script="$script_dir/audit-mongo-indexes.js"

mongosh "$MONGODB_URI/$db_name" --quiet --eval '
db.jobs.drop();
db.aggregation_conflicts.drop();
const first = ISODate("2026-01-01T00:00:00Z");
const last = ISODate("2026-02-01T00:00:00Z");
db.jobs.insertMany([
  {_id:"valid", recruiterId:null, source:"greenhouse", externalId:"board:1", fingerprint:"valid-fingerprint",
   applicationUrl:"https://greenhouse.test/1", active:true, sourceIdentities:["greenhouse:board:1"],
   sourceListings:[{identity:"greenhouse:board:1",provider:"greenhouse",employer:"board",externalId:"board:1",
     applicationUrl:"https://greenhouse.test/1",firstSeenAt:first,lastSeenAt:last,active:true,consecutiveMissingRuns:0}]},
  {_id:"manual", recruiterId:"recruiter-1", source:"manual", externalId:"manual-1", active:true}
]);
db.aggregation_conflicts.insertOne({_id:"resolved",type:"IDENTITY_FINGERPRINT",status:"RESOLVED",
  identity:"greenhouse:board:1",fingerprint:"valid-fingerprint",jobIds:["valid","duplicate"],
  firstObservedAt:first,lastObservedAt:last,occurrences:1,canonicalJobId:"valid",duplicateJobId:"duplicate",
  resolvedAt:last,resolvedBy:"admin@example.test"});'

clean_output=$(mongosh "$MONGODB_URI/$db_name" --quiet "$audit_script")
grep -Fq 'malformedSourceListings: []' <<<"$clean_output"
grep -Fq 'canonicalMismatches: []' <<<"$clean_output"
grep -Fq 'lifecycleMismatches: []' <<<"$clean_output"

mongosh "$MONGODB_URI/$db_name" --quiet --eval '
db.jobs.insertOne({_id:"invalid",recruiterId:null,source:"lever",externalId:"bad",fingerprint:"bad",
  applicationUrl:"https://wrong.test/bad",active:true,sourceListings:[
    {identity:"lever:bad",provider:"lever",externalId:"bad",applicationUrl:"https://lever.test/bad",
      firstSeenAt:"not-a-date",lastSeenAt:new Date(),active:false,consecutiveMissingRuns:-1},
    {identity:"lever:bad",provider:"lever",externalId:"bad",applicationUrl:"https://lever.test/bad",
      firstSeenAt:new Date(),lastSeenAt:new Date(),active:false,consecutiveMissingRuns:0}
  ]});'

set +e
invalid_output=$(mongosh "$MONGODB_URI/$db_name" --quiet "$audit_script" 2>&1)
invalid_status=$?
set -e
test "$invalid_status" -eq 2
grep -Fq "invalid_listing" <<<"$invalid_output"
grep -Fq "duplicateListingIdentities" <<<"$invalid_output"
grep -Fq "canonicalMismatches" <<<"$invalid_output"
grep -Fq "lifecycleMismatches" <<<"$invalid_output"

echo "Aggregation migration audit harness passed (clean acceptance and non-destructive anomaly detection)."
