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
   applicationUrls:["https://greenhouse.test/1"],
   sourceListings:[{identity:"greenhouse:board:1",provider:"greenhouse",employer:"board",externalId:"board:1",
     applicationUrl:"https://greenhouse.test/1",firstSeenAt:first,lastSeenAt:last,active:true,consecutiveMissingRuns:0}]},
  {_id:"manual", recruiterId:"recruiter-1", source:"manual", externalId:"manual-1", active:true}
]);
db.aggregation_conflicts.insertOne({_id:"resolved",type:"IDENTITY_FINGERPRINT",status:"RESOLVED",
  identity:"greenhouse:board:1",fingerprint:"valid-fingerprint",jobIds:["valid","duplicate"],
  firstObservedAt:first,lastObservedAt:last,occurrences:1,canonicalJobId:"valid",duplicateJobId:"duplicate",
  resolvedAt:last,resolvedBy:"admin@example.test"});'

snapshot() {
  mongosh "$MONGODB_URI/$db_name" --quiet --eval \
    'EJSON.stringify({jobs:db.jobs.find().sort({_id:1}).toArray(),conflicts:db.aggregation_conflicts.find().sort({_id:1}).toArray()})'
}

clean_before=$(snapshot)
clean_output=$(mongosh "$MONGODB_URI/$db_name" --quiet "$audit_script")
clean_after=$(snapshot)
test "$clean_before" = "$clean_after"
grep -Fq 'malformedSourceListings: []' <<<"$clean_output"
grep -Fq 'canonicalMismatches: []' <<<"$clean_output"
grep -Fq 'lifecycleMismatches: []' <<<"$clean_output"
grep -Fq 'crossDocumentSourceIdentityDuplicates: []' <<<"$clean_output"
grep -Fq 'sourceIdentityCompatibilityMismatches: []' <<<"$clean_output"
grep -Fq 'applicationUrlCompatibilityMismatches: []' <<<"$clean_output"

mongosh "$MONGODB_URI/$db_name" --quiet --eval '
const first = ISODate("2026-01-01T00:00:00Z");
const last = ISODate("2026-02-01T00:00:00Z");
db.jobs.insertOne({_id:"identity-duplicate",recruiterId:null,source:"greenhouse",externalId:"board:2",
  fingerprint:"identity-duplicate-fingerprint",applicationUrl:"https://greenhouse.test/2",active:true,
  sourceIdentities:["greenhouse:board:1"],applicationUrls:["https://greenhouse.test/2"],
  sourceListings:[{identity:"greenhouse:board:1",provider:"greenhouse",employer:"board",externalId:"board:2",
    applicationUrl:"https://greenhouse.test/2",firstSeenAt:first,lastSeenAt:last,active:true,consecutiveMissingRuns:0}]});'
duplicate_before=$(snapshot)
set +e
duplicate_output=$(mongosh "$MONGODB_URI/$db_name" --quiet "$audit_script" 2>&1)
duplicate_status=$?
set -e
duplicate_after=$(snapshot)
test "$duplicate_status" -eq 2
test "$duplicate_before" = "$duplicate_after"
grep -Fq "crossDocumentSourceIdentityDuplicates" <<<"$duplicate_output"
grep -Fq "identity-duplicate" <<<"$duplicate_output"
mongosh "$MONGODB_URI/$db_name" --quiet --eval 'db.jobs.deleteOne({_id:"identity-duplicate"});'

mongosh "$MONGODB_URI/$db_name" --quiet --eval '
db.jobs.updateOne({_id:"valid"},{$set:{sourceIdentities:["greenhouse:wrong"],
  applicationUrls:["https://wrong.test/1"]}});'
compatibility_before=$(snapshot)
set +e
compatibility_output=$(mongosh "$MONGODB_URI/$db_name" --quiet "$audit_script" 2>&1)
compatibility_status=$?
set -e
compatibility_after=$(snapshot)
test "$compatibility_status" -eq 2
test "$compatibility_before" = "$compatibility_after"
grep -Fq "sourceIdentityCompatibilityMismatches" <<<"$compatibility_output"
grep -Fq "applicationUrlCompatibilityMismatches" <<<"$compatibility_output"
grep -Fq "valid" <<<"$compatibility_output"
mongosh "$MONGODB_URI/$db_name" --quiet --eval '
db.jobs.updateOne({_id:"valid"},{$set:{sourceIdentities:["greenhouse:board:1"],
  applicationUrls:["https://greenhouse.test/1"]}});'

mongosh "$MONGODB_URI/$db_name" --quiet --eval '
db.jobs.insertOne({_id:"invalid",recruiterId:null,source:"lever",externalId:"bad",fingerprint:"bad",
  applicationUrl:"https://wrong.test/bad",active:true,sourceListings:[
    {identity:"lever:bad",provider:"lever",externalId:"bad",applicationUrl:"https://lever.test/bad",
      firstSeenAt:"not-a-date",lastSeenAt:new Date(),active:false,consecutiveMissingRuns:-1},
    {identity:"lever:bad",provider:"lever",externalId:"bad",applicationUrl:"https://lever.test/bad",
      firstSeenAt:new Date(),lastSeenAt:new Date(),active:false,consecutiveMissingRuns:0}
  ]});'

invalid_before=$(snapshot)
set +e
invalid_output=$(mongosh "$MONGODB_URI/$db_name" --quiet "$audit_script" 2>&1)
invalid_status=$?
set -e
test "$invalid_status" -eq 2
grep -Fq "invalid_listing" <<<"$invalid_output"
grep -Fq "duplicateListingIdentities" <<<"$invalid_output"
grep -Fq "canonicalMismatches" <<<"$invalid_output"
grep -Fq "lifecycleMismatches" <<<"$invalid_output"

invalid_after=$(snapshot)
test "$invalid_before" = "$invalid_after"

echo "Aggregation migration audit harness passed (clean acceptance and non-destructive anomaly detection)."
