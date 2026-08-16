#!/usr/bin/env bash
set -euo pipefail
: "${MONGODB_URI:?Set MONGODB_URI to a disposable test database}"
db="jobportal_m1a_verify"
mongosh "$MONGODB_URI/$db" --quiet --eval 'db.jobs.drop(); db.jobs.insertMany([{_id:"legacy",source:"adzuna",externalId:"1",applicationUrl:"https://e.test/1",recruiterId:null},{_id:"manual",source:"manual",externalId:"m",recruiterId:"u"},{_id:"amb",source:"adzuna",externalId:"2",sourceIdentities:["adzuna:2","lever:2"],recruiterId:null}])'
script_dir=$(cd "$(dirname "$0")" && pwd)
mongosh "$MONGODB_URI/$db" --quiet "$script_dir/backfill-source-listings.js" | grep 'changed: 0'
BACKFILL_APPLY=true mongosh "$MONGODB_URI/$db" --quiet "$script_dir/backfill-source-listings.js" | grep 'changed: 1'
BACKFILL_APPLY=true mongosh "$MONGODB_URI/$db" --quiet "$script_dir/backfill-source-listings.js" | grep 'changed: 0'
mongosh "$MONGODB_URI/$db" --quiet --eval 'if(db.jobs.findOne({_id:"legacy"}).sourceListings.length!==1||db.jobs.findOne({_id:"manual"}).sourceListings||db.jobs.findOne({_id:"amb"}).sourceListings) quit(2)'
