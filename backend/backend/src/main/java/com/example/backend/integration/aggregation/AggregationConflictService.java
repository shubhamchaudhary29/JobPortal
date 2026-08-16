package com.example.backend.integration.aggregation;

import com.example.backend.application.infrastructure.ApplicationDocument;
import com.example.backend.job.infrastructure.ImportedSourceListing;
import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.messaging.infrastructure.ConversationDocument;
import com.example.backend.shared.error.BadRequestException;
import com.example.backend.shared.error.ConflictException;
import com.example.backend.shared.error.ResourceNotFoundException;
import com.example.backend.shared.pagination.PageResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bson.Document;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Service
public class AggregationConflictService {
    private static final long RESOLUTION_LEASE_MS = 60_000;
    private final MongoTemplate mongo;
    private final DistributedLeaseLock locks;

    public AggregationConflictService(MongoTemplate mongo, DistributedLeaseLock locks) {
        this.mongo = mongo;
        this.locks = locks;
    }

    public void recordIdentityFingerprint(String identity, String fingerprint,
                                          String identityJobId, String fingerprintJobId,
                                          LocalDateTime observedAt) {
        List<String> jobIds = new ArrayList<>(List.of(identityJobId, fingerprintJobId));
        jobIds.sort(String::compareTo);
        String id = digest(String.join("|", "IDENTITY_FINGERPRINT", identity, fingerprint,
                String.join(",", jobIds)));
        Update update = new Update()
                .setOnInsert("type", AggregationConflictDocument.Type.IDENTITY_FINGERPRINT)
                .setOnInsert("status", AggregationConflictDocument.Status.OPEN)
                .setOnInsert("identity", identity)
                .setOnInsert("fingerprint", fingerprint)
                .setOnInsert("jobIds", new LinkedHashSet<>(jobIds))
                .setOnInsert("firstObservedAt", observedAt)
                .set("lastObservedAt", observedAt)
                .inc("occurrences", 1);
        mongo.findAndModify(Query.query(Criteria.where("_id").is(id)), update,
                FindAndModifyOptions.options().upsert(true).returnNew(true), AggregationConflictDocument.class);
    }

    public PageResponse<ConflictView> list(String rawStatus, int page, int size) {
        if (page < 0) throw new BadRequestException("Page must not be negative");
        if (size < 1 || size > 100) throw new BadRequestException("Size must be between 1 and 100");
        AggregationConflictDocument.Status status = parseStatus(rawStatus);
        Query query = new Query();
        if (status != null) query.addCriteria(Criteria.where("status").is(status));
        long total = mongo.count(Query.of(query).limit(-1).skip(-1), AggregationConflictDocument.class);
        PageRequest pageable = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("lastObservedAt"), Sort.Order.asc("id")));
        query.with(pageable);
        List<ConflictView> content = mongo.find(query, AggregationConflictDocument.class).stream()
                .map(ConflictView::from).toList();
        return PageResponse.from(new PageImpl<>(content, pageable, total));
    }

    public ConflictView resolve(String conflictId, String canonicalJobId, String duplicateJobId,
                                String resolvedBy) {
        if (canonicalJobId == null || canonicalJobId.isBlank()
                || duplicateJobId == null || duplicateJobId.isBlank()
                || canonicalJobId.equals(duplicateJobId)) {
            throw new BadRequestException("Distinct canonical and duplicate job ids are required");
        }
        String lockName = "conflict-reconciliation:" + conflictId;
        String owner = locks.acquire(lockName, RESOLUTION_LEASE_MS);
        if (owner == null) throw new ConflictException("Conflict reconciliation is already running");
        try {
            return resolveOwned(conflictId, canonicalJobId, duplicateJobId, resolvedBy);
        } finally {
            locks.release(lockName, owner);
        }
    }

    private ConflictView resolveOwned(String conflictId, String canonicalJobId, String duplicateJobId,
                                      String resolvedBy) {
        AggregationConflictDocument conflict = mongo.findById(conflictId, AggregationConflictDocument.class);
        if (conflict == null) throw new ResourceNotFoundException("Aggregation conflict not found");
        if (!conflict.getJobIds().contains(canonicalJobId) || !conflict.getJobIds().contains(duplicateJobId)) {
            throw new BadRequestException("Resolution jobs must belong to the conflict");
        }
        if (conflict.getStatus() == AggregationConflictDocument.Status.RESOLVED) {
            if (canonicalJobId.equals(conflict.getCanonicalJobId())
                    && duplicateJobId.equals(conflict.getDuplicateJobId())) return ConflictView.from(conflict);
            throw new ConflictException("Conflict was already resolved differently");
        }
        JobDocument canonical = mongo.findById(canonicalJobId, JobDocument.class);
        if (canonical == null) throw new ResourceNotFoundException("Canonical job not found");
        JobDocument duplicate = mongo.findById(duplicateJobId, JobDocument.class);
        if (duplicate == null && !(canonicalJobId.equals(conflict.getCanonicalJobId())
                && duplicateJobId.equals(conflict.getDuplicateJobId()))) {
            throw new ResourceNotFoundException("Duplicate job not found");
        }
        if (duplicate != null && duplicate.getReconciliationTargetId() != null
                && !canonicalJobId.equals(duplicate.getReconciliationTargetId())) {
            throw new ConflictException("Duplicate job belongs to another reconciliation");
        }
        if (duplicate != null && hasApplicationCollision(canonicalJobId, duplicateJobId)) {
            recordResolutionFailure(conflictId, "APPLICATION_REFERENCE_COLLISION");
            throw new ConflictException("Applications for the same candidate reference both jobs");
        }
        if (conflict.getCanonicalJobId() != null
                && (!canonicalJobId.equals(conflict.getCanonicalJobId())
                || !duplicateJobId.equals(conflict.getDuplicateJobId()))) {
            throw new ConflictException("Conflict reconciliation already started with another choice");
        }
        if (duplicate != null) {
            mongo.updateFirst(Query.query(Criteria.where("_id").is(conflictId).and("status")
                            .is(AggregationConflictDocument.Status.OPEN)),
                    new Update().set("canonicalJobId", canonicalJobId).set("duplicateJobId", duplicateJobId)
                            .set("reconciliationStartedAt", LocalDateTime.now()),
                    AggregationConflictDocument.class);
            markDuplicatePending(conflictId, canonicalJobId, duplicate);
            mergeJobs(canonical, duplicate);
        }
        rewriteReferences(canonicalJobId, duplicateJobId);
        mongo.remove(Query.query(Criteria.where("_id").is(duplicateJobId)
                .and("reconciliationTargetId").is(canonicalJobId)), JobDocument.class);
        LocalDateTime resolvedAt = LocalDateTime.now();
        AggregationConflictDocument resolved = mongo.findAndModify(
                Query.query(Criteria.where("_id").is(conflictId).and("status")
                        .is(AggregationConflictDocument.Status.OPEN)),
                new Update().set("status", AggregationConflictDocument.Status.RESOLVED)
                        .set("canonicalJobId", canonicalJobId).set("duplicateJobId", duplicateJobId)
                        .set("resolvedAt", resolvedAt).set("resolvedBy", bounded(resolvedBy, 200))
                        .unset("resolutionFailure"),
                FindAndModifyOptions.options().returnNew(true), AggregationConflictDocument.class);
        if (resolved == null) {
            resolved = mongo.findById(conflictId, AggregationConflictDocument.class);
        }
        return ConflictView.from(resolved);
    }

    private boolean hasApplicationCollision(String canonicalJobId, String duplicateJobId) {
        List<Document> collisions = mongo.getCollection("applications").aggregate(List.of(
                new Document("$match", new Document("jobId", new Document("$in",
                        List.of(canonicalJobId, duplicateJobId)))),
                new Document("$group", new Document("_id", "$userId")
                        .append("jobIds", new Document("$addToSet", "$jobId"))),
                new Document("$match", new Document("jobIds.1", new Document("$exists", true))),
                new Document("$limit", 1))).into(new ArrayList<>());
        return !collisions.isEmpty();
    }

    private void markDuplicatePending(String conflictId, String canonicalJobId, JobDocument duplicate) {
        Update update = new Update().set("reconciliationTargetId", canonicalJobId)
                .set("reconciliationConflictId", conflictId)
                .unset("fingerprint").unset("sourceIdentities").unset("source")
                .unset("externalId").set("active", false);
        if (duplicate.getFingerprint() != null) {
            update.set("reconciliationOriginalFingerprint", duplicate.getFingerprint());
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is(duplicate.getId())), update, JobDocument.class);
    }

    private void mergeJobs(JobDocument canonical, JobDocument duplicate) {
        Map<String, ImportedSourceListing> listings = new LinkedHashMap<>();
        addListings(listings, canonical);
        addListings(listings, duplicate);
        List<ImportedSourceListing> merged = new ArrayList<>(listings.values());
        merged.sort(Comparator.comparing(ImportedSourceListing::getProvider, Comparator.nullsLast(String::compareTo))
                .thenComparing(ImportedSourceListing::getIdentity, Comparator.nullsLast(String::compareTo))
                .thenComparing(ImportedSourceListing::getApplicationUrl, Comparator.nullsLast(String::compareTo)));
        if (merged.isEmpty()) throw new ConflictException("Conflict jobs have no source listings to reconcile");
        ImportedSourceListing primary = merged.stream().filter(ImportedSourceListing::isActive)
                .findFirst().orElse(merged.get(0));
        JobDocument contentOwner = owns(canonical, primary.getIdentity()) ? canonical : duplicate;
        LinkedHashSet<String> identities = new LinkedHashSet<>();
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        for (ImportedSourceListing listing : merged) {
            identities.add(listing.getIdentity());
            if (listing.getApplicationUrl() != null) urls.add(listing.getApplicationUrl());
        }
        Update update = new Update().set("sourceListings", merged).set("sourceIdentities", identities)
                .set("applicationUrls", urls).set("source", primary.getProvider())
                .set("externalId", primary.getExternalId()).set("applicationUrl", primary.getApplicationUrl())
                .set("sourceUrl", primary.getApplicationUrl()).set("active", merged.stream().anyMatch(ImportedSourceListing::isActive))
                .set("title", contentOwner.getTitle()).set("description", contentOwner.getDescription())
                .set("company", contentOwner.getCompany()).set("location", contentOwner.getLocation())
                .set("salary", contentOwner.getSalary()).set("experience", contentOwner.getExperience())
                .set("employmentType", contentOwner.getEmploymentType()).set("salaryMin", contentOwner.getSalaryMin())
                .set("salaryMax", contentOwner.getSalaryMax()).set("publishedAt", contentOwner.getPublishedAt())
                .set("expiresAt", contentOwner.getExpiresAt()).set("fingerprint", effectiveFingerprint(contentOwner));
        mongo.updateFirst(Query.query(Criteria.where("_id").is(canonical.getId())), update, JobDocument.class);
    }

    private String effectiveFingerprint(JobDocument job) {
        return job.getFingerprint() != null ? job.getFingerprint() : job.getReconciliationOriginalFingerprint();
    }

    private void addListings(Map<String, ImportedSourceListing> target, JobDocument job) {
        if (job.getSourceListings() == null) return;
        for (ImportedSourceListing candidate : job.getSourceListings()) {
            target.merge(candidate.getIdentity(), copy(candidate), this::mergeListing);
        }
    }

    private ImportedSourceListing mergeListing(ImportedSourceListing left, ImportedSourceListing right) {
        ImportedSourceListing latest = later(left, right) == right ? copy(right) : copy(left);
        latest.setFirstSeenAt(earlier(left.getFirstSeenAt(), right.getFirstSeenAt()));
        latest.setLastSeenAt(later(left.getLastSeenAt(), right.getLastSeenAt()));
        latest.setActive(left.isActive() || right.isActive());
        latest.setConsecutiveMissingRuns(latest.isActive() ? 0
                : Math.max(left.getConsecutiveMissingRuns(), right.getConsecutiveMissingRuns()));
        return latest;
    }

    private ImportedSourceListing copy(ImportedSourceListing source) {
        ImportedSourceListing copy = new ImportedSourceListing();
        copy.setIdentity(source.getIdentity()); copy.setProvider(source.getProvider());
        copy.setEmployer(source.getEmployer()); copy.setExternalId(source.getExternalId());
        copy.setApplicationUrl(source.getApplicationUrl()); copy.setFirstSeenAt(source.getFirstSeenAt());
        copy.setLastSeenAt(source.getLastSeenAt()); copy.setActive(source.isActive());
        copy.setConsecutiveMissingRuns(source.getConsecutiveMissingRuns());
        return copy;
    }

    private ImportedSourceListing later(ImportedSourceListing left, ImportedSourceListing right) {
        return Objects.compare(left.getLastSeenAt(), right.getLastSeenAt(),
                Comparator.nullsFirst(LocalDateTime::compareTo)) >= 0 ? left : right;
    }

    private LocalDateTime later(LocalDateTime left, LocalDateTime right) {
        return Objects.compare(left, right, Comparator.nullsFirst(LocalDateTime::compareTo)) >= 0 ? left : right;
    }

    private LocalDateTime earlier(LocalDateTime left, LocalDateTime right) {
        return Objects.compare(left, right, Comparator.nullsLast(LocalDateTime::compareTo)) <= 0 ? left : right;
    }

    private boolean owns(JobDocument job, String identity) {
        return job.getSourceListings() != null && job.getSourceListings().stream()
                .anyMatch(listing -> identity.equals(listing.getIdentity()));
    }

    private void rewriteReferences(String canonicalJobId, String duplicateJobId) {
        mongo.updateMulti(Query.query(Criteria.where("jobId").is(duplicateJobId)),
                new Update().set("jobId", canonicalJobId), ApplicationDocument.class);
        mongo.updateMulti(Query.query(Criteria.where("jobId").is(duplicateJobId)),
                new Update().set("jobId", canonicalJobId), ConversationDocument.class);
    }

    private void recordResolutionFailure(String conflictId, String failure) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(conflictId)),
                new Update().set("resolutionFailure", failure), AggregationConflictDocument.class);
    }

    private AggregationConflictDocument.Status parseStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) return null;
        try { return AggregationConflictDocument.Status.valueOf(rawStatus.toUpperCase()); }
        catch (IllegalArgumentException invalid) { throw new BadRequestException("Unsupported conflict status"); }
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String bounded(String value, int maximum) {
        if (value == null) return "unknown";
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    public record ConflictView(String id, AggregationConflictDocument.Type type,
            AggregationConflictDocument.Status status, String identity, String fingerprint,
            Set<String> jobIds, LocalDateTime firstObservedAt, LocalDateTime lastObservedAt,
            long occurrences, String canonicalJobId, String duplicateJobId,
            LocalDateTime resolvedAt, String resolvedBy, String resolutionFailure) {
        static ConflictView from(AggregationConflictDocument document) {
            return new ConflictView(document.getId(), document.getType(), document.getStatus(),
                    document.getIdentity(), document.getFingerprint(), Set.copyOf(document.getJobIds()),
                    document.getFirstObservedAt(), document.getLastObservedAt(), document.getOccurrences(),
                    document.getCanonicalJobId(), document.getDuplicateJobId(), document.getResolvedAt(),
                    document.getResolvedBy(), document.getResolutionFailure());
        }
    }
}
