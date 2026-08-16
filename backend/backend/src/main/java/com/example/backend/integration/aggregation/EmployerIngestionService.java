package com.example.backend.integration.aggregation;

import com.example.backend.integration.adzuna.AdzunaJobStore;
import com.example.backend.integration.jobs.ExternalJob;
import com.example.backend.integration.jobs.JobFetchRequest;
import com.example.backend.integration.jobs.JobSource;
import com.example.backend.job.infrastructure.JobDocument;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import com.example.backend.shared.error.BadRequestException;

@Service
public class EmployerIngestionService {
    private static final Logger log = LoggerFactory.getLogger(EmployerIngestionService.class);
    private final Map<EmployerRegistryProperties.Source, JobSource> sources;
    private final AdzunaJobStore store;
    private final EmployerRegistryProperties registry;
    private final ImportedJobLifecycleService lifecycle;

    public EmployerIngestionService(@Qualifier("greenhouseJobSource") JobSource greenhouse,
            @Qualifier("leverJobSource") JobSource lever, AdzunaJobStore store,
            EmployerRegistryProperties registry, ImportedJobLifecycleService lifecycle) {
        this.sources = Map.of(EmployerRegistryProperties.Source.GREENHOUSE, greenhouse,
                EmployerRegistryProperties.Source.LEVER, lever);
        this.store = store;
        this.registry = registry;
        this.lifecycle = lifecycle;
    }

    public Result sync() { return sync(null, () -> true); }
    public Result sync(EmployerRegistryProperties.Source selected) { return sync(selected, () -> true); }

    public Result sync(EmployerRegistryProperties.Source selected, BooleanSupplier leaseValid) {
        return sync(selected, null, leaseValid);
    }

    public Result sync(EmployerRegistryProperties.Source selected, String selectedEmployer,
                       BooleanSupplier leaseValid) {
        String employerBoard = requireEnabledEmployer(selected, selectedEmployer);
        int inserted = 0, updated = 0, unchanged = 0, failed = 0, rejected = 0, attemptedEmployers = 0, retries = 0;
        long lifecycleMatched = 0, lifecycleModified = 0;
        for (var employer : registry.employers()) {
            if (!leaseValid.getAsBoolean()) break;
            if (!employer.enabled() || (selected != null && employer.source() != selected)
                    || (employerBoard != null && !employer.boardId().equalsIgnoreCase(employerBoard))) continue;
            attemptedEmployers++;
            JobSource source = sources.get(employer.source());
            Set<String> seenIdentities = new HashSet<>();
            boolean complete = true;
            LocalDateTime observedAt = LocalDateTime.now();
            try {
                JobFetchRequest request = new JobFetchRequest(null, 1, employer.boardId(), employer.company());
                JobSource.FetchResult fetch = source.fetchWithMetadata(request);
                if (fetch == null) fetch = new JobSource.FetchResult(source.fetch(request), 0);
                retries += fetch.retries();
                List<ExternalJob> response = fetch.jobs();
                if (response.isEmpty()) log.info("event=employer_board_empty employer={}", employer.company());
                for (ExternalJob external : response) {
                    if (!leaseValid.getAsBoolean()) {
                        complete = false;
                        break;
                    }
                    try {
                        if (!valid(external)) {
                            rejected++;
                            complete = false;
                            continue;
                        }
                        JobDocument job = map(source, employer, external);
                        switch (store.upsert(job, observedAt, employer.boardId())) {
                            case INSERTED -> inserted++;
                            case UPDATED -> updated++;
                            case UNCHANGED -> unchanged++;
                        }
                        seenIdentities.add(job.getSource() + ":" + job.getExternalId());
                    } catch (RuntimeException itemFailure) {
                        rejected++;
                        complete = false;
                        log.warn("event=employer_job_rejected employer={}", employer.company());
                    }
                }
                if (complete && leaseValid.getAsBoolean()) {
                    ImportedJobLifecycleService.Result lifecycleResult = lifecycle.completeSuccessfulRun(
                            source.sourceName(), employer.boardId(), seenIdentities, observedAt);
                    if (lifecycleResult != null) {
                        lifecycleMatched += lifecycleResult.matchedJobs();
                        lifecycleModified += lifecycleResult.modifiedJobs();
                    }
                }
            } catch (RuntimeException employerFailure) {
                failed++;
                log.warn("event=employer_board_failed employer={}", employer.company());
            }
        }
        return new Result(inserted, updated, unchanged, rejected, failed,
                lifecycleMatched, lifecycleModified, attemptedEmployers, retries);
    }

    String requireEnabledEmployer(EmployerRegistryProperties.Source source, String employer) {
        if (employer == null || employer.isBlank()) return null;
        if (source == null) throw new BadRequestException("Provider is required for employer synchronization");
        String normalized = employer.trim();
        if (normalized.length() > 100 || !normalized.matches("[A-Za-z0-9_-]+")) {
            throw new BadRequestException("Invalid employer");
        }
        return registry.employers().stream()
                .filter(candidate -> candidate.source() == source
                        && candidate.boardId().equalsIgnoreCase(normalized) && candidate.enabled())
                .map(EmployerRegistryProperties.Employer::boardId)
                .findFirst().orElseThrow(() -> new BadRequestException("Employer is not enabled for provider"));
    }

    private boolean valid(ExternalJob job) {
        return job != null && job.externalId() != null && job.title() != null
                && job.applicationUrl() != null && job.fingerprint() != null;
    }

    private JobDocument map(JobSource source, EmployerRegistryProperties.Employer employer, ExternalJob external) {
        JobDocument job = new JobDocument();
        job.setSource(source.sourceName());
        job.setExternalId(employer.boardId() + ":" + external.externalId());
        job.setTitle(external.title());
        job.setDescription(external.description());
        job.setCompany(external.company());
        job.setLocation(external.location());
        job.setEmploymentType(external.employmentType());
        job.setApplicationUrl(external.applicationUrl());
        job.setSourceUrl(external.applicationUrl());
        job.setPublishedAt(external.publishedAt());
        job.setFingerprint(external.fingerprint());
        return job;
    }

    public record Result(int inserted, int updated, int unchanged, int rejected, int failedEmployers,
                         long lifecycleMatched, long lifecycleModified, int attemptedEmployers, int retries) {
        public Result(int inserted, int updated, int unchanged, int rejected, int failedEmployers) {
            this(inserted, updated, unchanged, rejected, failedEmployers, 0, 0, 0, 0);
        }
        public Result(int inserted, int updated, int unchanged, int rejected, int failedEmployers,
                      long lifecycleMatched, long lifecycleModified, int attemptedEmployers) {
            this(inserted, updated, unchanged, rejected, failedEmployers,
                    lifecycleMatched, lifecycleModified, attemptedEmployers, 0);
        }
    }
}
