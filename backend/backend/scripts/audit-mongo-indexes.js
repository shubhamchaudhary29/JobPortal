// Run with placeholders only: mongosh "$MONGODB_URI" backend/scripts/audit-mongo-indexes.js
// Never paste production credentials into this file or shell history.
const duplicates = db.jobs.aggregate([
  {$match: {source: {$type: "string"}, externalId: {$type: "string"}}},
  {$group: {_id: {source: "$source", externalId: "$externalId"}, ids: {$push: "$_id"}, count: {$sum: 1}}},
  {$match: {count: {$gt: 1}}}
]).toArray();
printjson({duplicateSourceExternalIds: duplicates});
printjson({jobIndexes: db.jobs.getIndexes()});
if (duplicates.length) quit(2);
