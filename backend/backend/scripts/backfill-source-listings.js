// Usage: BACKFILL_APPLY=true mongosh "$MONGODB_URI" backend/backend/scripts/backfill-source-listings.js
// Default is dry-run. It never deletes, merges, or guesses ambiguous records.
// mongosh does not pass arbitrary trailing arguments to scripts; use a portable env flag.
const apply = typeof process !== 'undefined' && process.env.BACKFILL_APPLY === 'true';
const query = {recruiterId: null, source: {$type: 'string'}, externalId: {$type: 'string'}};
let candidates=0, ambiguous=0, changed=0;
db.jobs.find(query).forEach(job => {
  const identity = `${job.source}:${job.externalId}`;
  if ((job.sourceListings || []).some(x => x.identity === identity)) return;
  if ((job.sourceIdentities || []).length > 1) { ambiguous++; printjson({ambiguous: job._id, identities:job.sourceIdentities}); return; }
  candidates++;
  const listing={identity,provider:job.source,externalId:job.externalId,applicationUrl:job.applicationUrl || job.sourceUrl || null,firstSeenAt:job.firstSeenAt || job.createdAt || new Date(),lastSeenAt:job.lastSeenAt || job.fetchedAt || new Date(),active:job.active !== false,consecutiveMissingRuns:0};
  if (apply) { db.jobs.updateOne({_id:job._id}, {$addToSet:{sourceListings:listing}}); changed++; }
});
printjson({mode: apply ? 'apply' : 'dry-run', candidates, changed, ambiguous});
