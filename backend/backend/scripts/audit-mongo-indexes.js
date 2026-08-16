// Run with placeholders only: mongosh "$MONGODB_URI" backend/backend/scripts/audit-mongo-indexes.js
// Never paste production credentials into this file or shell history.
const duplicates = db.jobs.aggregate([
  {$match: {source: {$type: "string"}, externalId: {$type: "string"}}},
  {$group: {_id: {source: "$source", externalId: "$externalId"}, ids: {$push: "$_id"}, count: {$sum: 1}}},
  {$match: {count: {$gt: 1}}}
]).toArray();
const fingerprintDuplicates = db.jobs.aggregate([
  {$match: {fingerprint: {$type: "string"}, recruiterId: null}},
  {$group: {_id: "$fingerprint", ids: {$push: "$_id"}, count: {$sum: 1}}},
  {$match: {count: {$gt: 1}}}
]).toArray();
const importedJobs = db.jobs.find({recruiterId: null, source: {$type: "string"}, externalId: {$type: "string"}}).toArray();
const malformedSourceListings = [];
const duplicateListingIdentities = [];
const canonicalMismatches = [];
const lifecycleMismatches = [];
for (const job of importedJobs) {
  const listings = Array.isArray(job.sourceListings) ? job.sourceListings : [];
  if (!listings.length) {
    malformedSourceListings.push({_id: job._id, reason: "missing_source_listings"});
    continue;
  }
  const identities = new Set();
  for (const listing of listings) {
    const valid = listing && typeof listing.identity === "string" && listing.identity.length > 0
      && typeof listing.provider === "string" && listing.provider.length > 0
      && typeof listing.externalId === "string" && listing.externalId.length > 0
      && listing.firstSeenAt instanceof Date && listing.lastSeenAt instanceof Date
      && typeof listing.active === "boolean" && Number.isInteger(listing.consecutiveMissingRuns)
      && listing.consecutiveMissingRuns >= 0;
    if (!valid) malformedSourceListings.push({_id: job._id, identity: listing && listing.identity, reason: "invalid_listing"});
    if (listing && identities.has(listing.identity)) duplicateListingIdentities.push({_id: job._id, identity: listing.identity});
    if (listing) identities.add(listing.identity);
  }
  const candidates = listings.filter(item => item && item.active === true);
  const ordered = (candidates.length ? candidates : listings).slice().sort((left, right) =>
    [left.provider || "", left.identity || "", left.applicationUrl || ""].join("\u0000")
      .localeCompare([right.provider || "", right.identity || "", right.applicationUrl || ""].join("\u0000")));
  const primary = ordered[0];
  if (primary && (job.source !== primary.provider || job.externalId !== primary.externalId
      || (job.applicationUrl || null) !== (primary.applicationUrl || null))) {
    canonicalMismatches.push({_id: job._id, expectedIdentity: primary.identity});
  }
  const expectedActive = listings.some(item => item && item.active === true);
  const effectiveJobActive = job.active !== false;
  if (effectiveJobActive !== expectedActive || (!expectedActive && !(job.inactiveAt instanceof Date))) {
    lifecycleMismatches.push({_id: job._id, expectedActive});
  }
}
const malformedConflicts = db.getCollection("aggregation_conflicts").find({$or: [
  {type: {$ne: "IDENTITY_FINGERPRINT"}},
  {status: {$nin: ["OPEN", "RESOLVED"]}},
  {jobIds: {$not: {$type: "array"}}}
]}).toArray();
const orphanedReconciliations = db.jobs.find({reconciliationTargetId: {$exists: true}, $or: [
  {reconciliationConflictId: {$exists: false}},
  {reconciliationTargetId: {$eq: null}}
]}).toArray();
printjson({duplicateSourceExternalIds: duplicates});
printjson({duplicateImportedFingerprints: fingerprintDuplicates});
printjson({malformedSourceListings});
printjson({duplicateListingIdentities});
printjson({canonicalMismatches});
printjson({lifecycleMismatches});
printjson({malformedConflicts});
printjson({orphanedReconciliations});
printjson({jobIndexes: db.jobs.getIndexes()});
if (duplicates.length || fingerprintDuplicates.length || malformedSourceListings.length
    || duplicateListingIdentities.length || canonicalMismatches.length || lifecycleMismatches.length
    || malformedConflicts.length || orphanedReconciliations.length) quit(2);
