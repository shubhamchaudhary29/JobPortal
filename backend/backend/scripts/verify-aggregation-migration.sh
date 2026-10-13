#!/usr/bin/env bash
set -euo pipefail

: "${MONGODB_URI:?Set MONGODB_URI to a disposable local Mongo server URI; this harness drops its test database}"
db_name="jobportal_m1f_verify"
script_dir=$(cd "$(dirname "$0")" && pwd)
audit_script="$script_dir/audit-mongo-indexes.js"

mongosh "$MONGODB_URI/$db_name" --quiet --eval '
db.jobs.drop();
db.aggregation_conflicts.drop();
db.applications.drop();
const first = ISODate("2026-01-01T00:00:00Z");
const last = ISODate("2026-02-01T00:00:00Z");
db.jobs.insertMany([
  {_id:"valid", recruiterId:null, source:"greenhouse", externalId:"board:1", fingerprint:"valid-fingerprint", fingerprintVersion:2,
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
grep -Fq 'fingerprintVersionMismatches: []' <<<"$clean_output"
grep -Fq 'malformedConflicts: []' <<<"$clean_output"

# A recorded fingerprint-migration conflict is a valid conflict type, and an application-referenced job with an
# old fingerprint version is informational only: the audit still accepts the rollout.
mongosh "$MONGODB_URI/$db_name" --quiet --eval '
const first = ISODate("2026-01-01T00:00:00Z");
const last = ISODate("2026-02-01T00:00:00Z");
db.aggregation_conflicts.insertOne({_id:"migration-conflict",type:"FINGERPRINT_MIGRATION",status:"OPEN",
  fingerprint:"v2-fingerprint",jobIds:["valid","held"],firstObservedAt:first,lastObservedAt:last,occurrences:1});
db.jobs.insertOne({_id:"held", recruiterId:null, source:"lever", externalId:"held", fingerprint:"held-v1",
  applicationUrl:"https://lever.test/held", active:true, sourceIdentities:["lever:held"],
  applicationUrls:["https://lever.test/held"],
  sourceListings:[{identity:"lever:held",provider:"lever",externalId:"held",applicationUrl:"https://lever.test/held",
    firstSeenAt:first,lastSeenAt:last,active:true,consecutiveMissingRuns:0}]});
db.applications.insertOne({_id:"application-held", jobId:"held", userId:"candidate-1"});' >/dev/null
held_before=$(snapshot)
held_output=$(mongosh "$MONGODB_URI/$db_name" --quiet "$audit_script")
test "$held_before" = "$(snapshot)"
grep -Fq 'fingerprintVersionMismatches: []' <<<"$held_output"
grep -Fq 'malformedConflicts: []' <<<"$held_output"
grep -Fq 'applicationHeldFingerprintVersionCount: 1' <<<"$held_output"
grep -Fq "held" <<<"$held_output"

mongosh "$MONGODB_URI/$db_name" --quiet --eval '
db.jobs.updateOne({_id:"valid"},{$unset:{fingerprintVersion:""}});
db.jobs.insertOne({_id:"stale-version", recruiterId:null, source:"lever", externalId:"stale", fingerprint:"stale-v1",
  fingerprintVersion:1, applicationUrl:"https://lever.test/stale", active:true, sourceIdentities:["lever:stale"],
  applicationUrls:["https://lever.test/stale"],
  sourceListings:[{identity:"lever:stale",provider:"lever",externalId:"stale",applicationUrl:"https://lever.test/stale",
    firstSeenAt:ISODate("2026-01-01T00:00:00Z"),lastSeenAt:ISODate("2026-02-01T00:00:00Z"),active:true,
    consecutiveMissingRuns:0}]});' >/dev/null
version_before=$(snapshot)
set +e
version_output=$(mongosh "$MONGODB_URI/$db_name" --quiet "$audit_script" 2>&1)
version_status=$?
set -e
test "$version_status" -eq 2
test "$version_before" = "$(snapshot)"
grep -Fq "fingerprintVersionMismatchCount: 2" <<<"$version_output"
grep -Fq "stale-version" <<<"$version_output"
grep -Fq "fingerprintVersion: 1" <<<"$version_output"
mongosh "$MONGODB_URI/$db_name" --quiet --eval '
db.jobs.deleteMany({_id:{$in:["held","stale-version"]}});
db.applications.deleteMany({});
db.aggregation_conflicts.deleteOne({_id:"migration-conflict"});
db.jobs.updateOne({_id:"valid"},{$set:{fingerprintVersion:2}});' >/dev/null

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
