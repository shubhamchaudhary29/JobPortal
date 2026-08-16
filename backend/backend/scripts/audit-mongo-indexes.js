// Run with placeholders only: mongosh "$MONGODB_URI" backend/backend/scripts/audit-mongo-indexes.js
// Never paste production credentials into this file or shell history.
const DIAGNOSTIC_LIMIT = 20;
function addDiagnostic(target, value) {
  if (target.length < DIAGNOSTIC_LIMIT) target.push(value);
}
function stringSet(value) {
  if (!Array.isArray(value)) return null;
  return Array.from(new Set(value.filter(item => typeof item === "string"))).sort();
}
function equalSets(left, right) {
  return left !== null && right !== null && left.length === right.length
    && left.every((value, index) => value === right[index]);
}
const duplicates = db.jobs.aggregate([
  {$match: {source: {$type: "string"}, externalId: {$type: "string"}}},
  {$group: {_id: {source: "$source", externalId: "$externalId"}, ids: {$push: "$_id"}, count: {$sum: 1}}},
  {$match: {count: {$gt: 1}}},
  {$project: {ids: {$slice: ["$ids", DIAGNOSTIC_LIMIT]}, count: 1}},
  {$limit: DIAGNOSTIC_LIMIT}
]).toArray();
const fingerprintDuplicates = db.jobs.aggregate([
  {$match: {fingerprint: {$type: "string"}, recruiterId: null}},
  {$group: {_id: "$fingerprint", ids: {$push: "$_id"}, count: {$sum: 1}}},
  {$match: {count: {$gt: 1}}},
  {$project: {ids: {$slice: ["$ids", DIAGNOSTIC_LIMIT]}, count: 1}},
  {$limit: DIAGNOSTIC_LIMIT}
]).toArray();
const crossDocumentSourceIdentityDuplicates = db.jobs.aggregate([
  {$match: {recruiterId: null, sourceIdentities: {$type: "array"}}},
  {$unwind: "$sourceIdentities"},
  {$match: {sourceIdentities: {$type: "string"}}},
  {$group: {_id: {identity: "$sourceIdentities", jobId: "$_id"}}},
  {$group: {_id: "$_id.identity", ids: {$push: "$_id.jobId"}, count: {$sum: 1}}},
  {$match: {count: {$gt: 1}}},
  {$project: {ids: {$slice: ["$ids", DIAGNOSTIC_LIMIT]}, count: 1}},
  {$limit: DIAGNOSTIC_LIMIT}
]).toArray();
const importedJobs = db.jobs.find({recruiterId: null, $or: [
  {source: {$type: "string"}}, {sourceIdentities: {$type: "array"}}, {sourceListings: {$type: "array"}}
]}).toArray();
const malformedSourceListings = [];
const duplicateListingIdentities = [];
const sourceIdentityCompatibilityMismatches = [];
const applicationUrlCompatibilityMismatches = [];
const canonicalMismatches = [];
const lifecycleMismatches = [];
for (const job of importedJobs) {
  const listings = Array.isArray(job.sourceListings) ? job.sourceListings : [];
  if (!listings.length) {
    addDiagnostic(malformedSourceListings, {_id: job._id, reason: "missing_source_listings"});
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
    if (!valid) addDiagnostic(malformedSourceListings,
      {_id: job._id, identity: listing && listing.identity, reason: "invalid_listing"});
    if (listing && identities.has(listing.identity)) addDiagnostic(duplicateListingIdentities,
      {_id: job._id, identity: listing.identity});
    if (listing) identities.add(listing.identity);
  }
  const listingIdentities = stringSet(listings.map(listing => listing && listing.identity)
    .filter(identity => identity != null));
  const compatibilityIdentities = stringSet(job.sourceIdentities);
  if (!equalSets(compatibilityIdentities, listingIdentities)) {
    addDiagnostic(sourceIdentityCompatibilityMismatches, {_id: job._id});
  }
  const listingUrls = stringSet(listings.map(listing => listing && listing.applicationUrl)
    .filter(url => url != null));
  const compatibilityUrls = stringSet(job.applicationUrls);
  if (!equalSets(compatibilityUrls, listingUrls)) {
    addDiagnostic(applicationUrlCompatibilityMismatches, {_id: job._id});
  }
  const candidates = listings.filter(item => item && item.active === true);
  const ordered = (candidates.length ? candidates : listings).slice().sort((left, right) =>
    [left.provider || "", left.identity || "", left.applicationUrl || ""].join("\u0000")
      .localeCompare([right.provider || "", right.identity || "", right.applicationUrl || ""].join("\u0000")));
  const primary = ordered[0];
  if (primary && (job.source !== primary.provider || job.externalId !== primary.externalId
      || (job.applicationUrl || null) !== (primary.applicationUrl || null))) {
    addDiagnostic(canonicalMismatches, {_id: job._id, expectedIdentity: primary.identity});
  }
  const expectedActive = listings.some(item => item && item.active === true);
  const effectiveJobActive = job.active !== false;
  if (effectiveJobActive !== expectedActive || (!expectedActive && !(job.inactiveAt instanceof Date))) {
    addDiagnostic(lifecycleMismatches, {_id: job._id, expectedActive});
  }
}
const malformedConflicts = db.getCollection("aggregation_conflicts").find({$or: [
  {type: {$ne: "IDENTITY_FINGERPRINT"}},
  {status: {$nin: ["OPEN", "RESOLVED"]}},
  {jobIds: {$not: {$type: "array"}}}
]}).limit(DIAGNOSTIC_LIMIT).toArray();
const orphanedReconciliations = db.jobs.find({reconciliationTargetId: {$exists: true}, $or: [
  {reconciliationConflictId: {$exists: false}},
  {reconciliationTargetId: {$eq: null}}
]}).limit(DIAGNOSTIC_LIMIT).toArray();
printjson({duplicateSourceExternalIds: duplicates});
printjson({duplicateImportedFingerprints: fingerprintDuplicates});
printjson({crossDocumentSourceIdentityDuplicates});
printjson({malformedSourceListings});
printjson({duplicateListingIdentities});
printjson({sourceIdentityCompatibilityMismatches});
printjson({applicationUrlCompatibilityMismatches});
printjson({canonicalMismatches});
printjson({lifecycleMismatches});
printjson({malformedConflicts});
printjson({orphanedReconciliations});
printjson({jobIndexes: db.jobs.getIndexes()});
if (duplicates.length || fingerprintDuplicates.length || crossDocumentSourceIdentityDuplicates.length
    || malformedSourceListings.length || duplicateListingIdentities.length
    || sourceIdentityCompatibilityMismatches.length || applicationUrlCompatibilityMismatches.length
    || canonicalMismatches.length || lifecycleMismatches.length
    || malformedConflicts.length || orphanedReconciliations.length) quit(2);
