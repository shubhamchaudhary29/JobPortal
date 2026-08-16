package com.example.backend.integration.aggregation;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;

/** Mongo lease; expiry makes a crashed process recoverable. */
@Service
public class DistributedLeaseLock {
    private final MongoTemplate mongo;
    public DistributedLeaseLock(MongoTemplate mongo) { this.mongo = mongo; }
    public String acquire(String name, long leaseMs) {
        Instant now = Instant.now(); String owner = UUID.randomUUID().toString();
        Query query = Query.query(Criteria.where("_id").is(name).and("expiresAt").lt(now));
        Update update = new Update().set("owner", owner).set("acquiredAt", now).set("expiresAt", now.plusMillis(leaseMs));
        Lease lease = mongo.findAndModify(query, update, FindAndModifyOptions.options().returnNew(true), Lease.class);
        if (lease != null) return owner;
        try { mongo.insert(newLease(name, owner, now, leaseMs)); return owner; }
        catch (DuplicateKeyException alreadyHeld) { return null; }
    }
    public void release(String name, String owner) {
        mongo.remove(Query.query(Criteria.where("_id").is(name).and("owner").is(owner)), Lease.class);
    }
    private Lease newLease(String name, String owner, Instant now, long leaseMs) { Lease lease=new Lease(); lease.name=name; lease.owner=owner; lease.acquiredAt=now; lease.expiresAt=now.plusMillis(leaseMs); return lease; }
    @Document("ingestion_locks") static class Lease { @Id String name; String owner; Instant acquiredAt; Instant expiresAt; }
}
