package com.example.backend.integration.aggregation;

import java.time.Instant;
import java.time.Clock;
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
    private final Clock clock;
    public DistributedLeaseLock(MongoTemplate mongo, Clock clock) { this.mongo = mongo; this.clock = clock; }
    public String acquire(String name, long leaseMs) {
        if (leaseMs < 1_000) throw new IllegalArgumentException("lease duration must be at least 1000ms");
        Instant now = clock.instant(); String owner = UUID.randomUUID().toString();
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
    public boolean renew(String name, String owner, long leaseMs) {
        if (leaseMs < 1_000) throw new IllegalArgumentException("lease duration must be at least 1000ms");
        Instant now = clock.instant();
        return mongo.updateFirst(Query.query(Criteria.where("_id").is(name).and("owner").is(owner).and("expiresAt").gt(now)),
                new Update().set("expiresAt", now.plusMillis(leaseMs)), Lease.class).getModifiedCount() == 1;
    }
    private Lease newLease(String name, String owner, Instant now, long leaseMs) { Lease lease=new Lease(); lease.name=name; lease.owner=owner; lease.acquiredAt=now; lease.expiresAt=now.plusMillis(leaseMs); return lease; }
    @Document("ingestion_locks") static class Lease { @Id String name; String owner; Instant acquiredAt; Instant expiresAt; }
}
