package com.example.backend.integration.search;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Rotation state in {@code search_query_rotation}, one document per provider: the plan hash, the next group
 * index, whether the current cycle had a failure, and the identities seen so far in the cycle. A changed plan
 * (for example new keywords or cities) restarts the cycle. Runs of one provider are serialized by its lease.
 */
@Component
public class MongoQueryRotation implements QueryRotation {
    static final String COLLECTION = "search_query_rotation";
    private final MongoTemplate mongo;
    private final Clock clock;

    public MongoQueryRotation(MongoTemplate mongo, Clock clock) {
        this.mongo = mongo;
        this.clock = clock;
    }

    @Override
    public Slice next(String provider, SearchQueryPlan plan, int groupsPerRun) {
        String hash = hash(plan);
        Document state = state(provider, hash);
        int total = plan.groups().size();
        int start = total == 0 ? 0 : Math.floorMod(state.getInteger("cursor", 0), total);
        int count = Math.max(1, Math.min(groupsPerRun, total - start));
        return new Slice(hash, start, List.copyOf(plan.groups().subList(start, start + count)), total);
    }

    @Override
    public CycleResult record(String provider, Slice slice, int processedGroups, Set<String> seenIdentities,
                              boolean failed) {
        Document state = state(provider, slice.planHash());
        if (state.getInteger("cursor", 0) != slice.start()) {
            return null; // the plan or cursor changed under this run; ignore its partial evidence
        }
        Set<String> seen = new LinkedHashSet<>(state.getList("seen", String.class, List.of()));
        seen.addAll(seenIdentities);
        // Groups cut off by a budget stop are retried by the next run, so they do not fail the cycle.
        boolean cycleFailed = state.getBoolean("failed", false) || failed;
        int next = slice.start() + Math.max(0, processedGroups);
        Document update = new Document("planHash", slice.planHash()).append("updatedAt", clock.instant());
        CycleResult finished = null;
        if (next >= slice.planGroups()) {
            finished = new CycleResult(!cycleFailed, Set.copyOf(seen));
            update.append("cursor", 0).append("failed", false).append("seen", List.of())
                    .append("cycleStartedAt", clock.instant()).append("lastCycleComplete", !cycleFailed);
        } else {
            update.append("cursor", next).append("failed", cycleFailed).append("seen", new ArrayList<>(seen));
        }
        mongo.getCollection(COLLECTION).updateOne(new Document("_id", provider), new Document("$set", update));
        return finished;
    }

    private Document state(String provider, String hash) {
        Document state = mongo.findOne(Query.query(Criteria.where("_id").is(provider)), Document.class, COLLECTION);
        if (state == null || !hash.equals(state.getString("planHash"))) {
            state = new Document("_id", provider).append("planHash", hash).append("cursor", 0).append("failed", false)
                    .append("seen", List.of()).append("cycleStartedAt", clock.instant());
            mongo.getCollection(COLLECTION).replaceOne(new Document("_id", provider), state,
                    new com.mongodb.client.model.ReplaceOptions().upsert(true));
        }
        return state;
    }

    static String hash(SearchQueryPlan plan) {
        StringBuilder key = new StringBuilder();
        for (SearchQueryPlan.Group group : plan.groups()) {
            key.append(group.keyword()).append('\u0000').append(group.location()).append('\u0000')
                    .append(group.pages()).append('\n');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
