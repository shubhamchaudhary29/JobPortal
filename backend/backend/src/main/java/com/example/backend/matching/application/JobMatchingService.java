package com.example.backend.matching.application;

import com.example.backend.candidate.infrastructure.CandidateProfileDocument;
import com.example.backend.candidate.infrastructure.CandidateProfileRepository;
import com.example.backend.job.JobMapper;
import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.job.infrastructure.JobRepository;
import com.example.backend.job.infrastructure.JobSearchRepository;
import com.example.backend.matching.api.dto.JobMatchResponse;
import com.example.backend.matching.api.dto.MatchedJobResponse;
import com.example.backend.matching.config.MatchingProperties;
import com.example.backend.matching.domain.JobMatchResult;
import com.example.backend.job.domain.RoleFamily;
import com.example.backend.job.domain.WorkMode;
import com.example.backend.matching.extraction.RoleNormalizer;
import com.example.backend.matching.extraction.WorkAttributeNormalizer;
import com.example.backend.matching.infrastructure.JobFeatureStore;
import com.example.backend.shared.error.BadRequestException;
import com.example.backend.shared.error.ForbiddenException;
import com.example.backend.shared.error.ResourceNotFoundException;
import com.example.backend.shared.pagination.PageResponse;
import com.example.backend.shared.pagination.SortResponse;
import com.example.backend.shared.security.CurrentUserProvider;
import com.example.backend.user.domain.UserRole;
import com.example.backend.user.infrastructure.UserDocument;
import com.example.backend.user.infrastructure.UserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class JobMatchingService {
    private static final int MAX_PAGE_SIZE = 100;
    private final JobRepository jobs;
    private final JobSearchRepository search;
    private final UserRepository users;
    private final CandidateProfileRepository profiles;
    private final CurrentUserProvider currentUser;
    private final JobFeatureService features;
    private final JobFeatureStore featureStore;
    private final JobMatchEngine engine;
    private final MatchingMetrics metrics;
    private final MatchingProperties properties;
    private final RoleNormalizer roles;
    private final WorkAttributeNormalizer workAttributes;

    public JobMatchingService(JobRepository jobs, JobSearchRepository search, UserRepository users,
                              CandidateProfileRepository profiles, CurrentUserProvider currentUser,
                              JobFeatureService features, JobFeatureStore featureStore,
                              JobMatchEngine engine, MatchingMetrics metrics,
                              MatchingProperties properties, RoleNormalizer roles,
                              WorkAttributeNormalizer workAttributes) {
        this.jobs = jobs;
        this.search = search;
        this.users = users;
        this.profiles = profiles;
        this.currentUser = currentUser;
        this.features = features;
        this.featureStore = featureStore;
        this.engine = engine;
        this.metrics = metrics;
        this.properties = properties;
        this.roles = roles;
        this.workAttributes = workAttributes;
    }

    public JobMatchResponse match(String jobId) {
        CandidateContext candidate = candidate();
        JobDocument job = jobs.findById(jobId).filter(this::visible)
                .orElseThrow(() -> new ResourceNotFoundException("Job not found"));
        prepare(job);
        return JobMatchResponse.from(calculate(candidate.profile(), job));
    }

    public PageResponse<MatchedJobResponse> matched(int page, int size, double minMatch, String location,
                                                     String source, String employmentType, String workMode,
                                                     String role, String sort) {
        validate(page, size, minMatch, location, source);
        CandidateProfileDocument profile = candidate().profile();
        String normalizedEmployment = normalizeEmployment(employmentType);
        WorkMode normalizedMode = normalizeWorkMode(workMode);
        RoleFamily normalizedRole = normalizeRole(role);
        SortChoice sortChoice = SortChoice.parse(sort);

        List<ScoredJob> scored = new ArrayList<>();
        List<JobDocument> extracted = new ArrayList<>();
        for (JobDocument job : search.matchingCandidates(location, source, properties.getCandidateWindow())) {
            if (features.prepare(job)) extracted.add(job);
            var jobFeatures = job.getMatchFeatures();
            if (jobFeatures == null && (normalizedEmployment != null || normalizedMode != null || normalizedRole != null)) continue;
            if (normalizedEmployment != null && !normalizedEmployment.equals(jobFeatures.getEmploymentType())) continue;
            if (normalizedMode != null && normalizedMode != jobFeatures.getWorkMode()) continue;
            if (normalizedRole != null && normalizedRole != jobFeatures.getRoleFamily()) continue;
            JobMatchResult result = calculate(profile, job);
            if (result.overallScore() >= minMatch) scored.add(new ScoredJob(job, result));
        }
        featureStore.persistAllIfCurrent(extracted);
        scored.sort(sortChoice.comparator());
        int total = scored.size();
        int from = (int) Math.min(total, (long) page * size);
        int to = Math.min(total, from + size);
        List<MatchedJobResponse> content = scored.subList(from, to).stream()
                .map(value -> new MatchedJobResponse(JobMapper.toResponse(value.job()), JobMatchResponse.from(value.match())))
                .toList();
        int totalPages = total == 0 ? 0 : (int) Math.ceil(total / (double) size);
        return new PageResponse<>(content, page, size, total, totalPages, page == 0,
                totalPages == 0 || page >= totalPages - 1,
                List.of(new SortResponse(sortChoice.property(), sortChoice.direction())));
    }

    private CandidateContext candidate() {
        UserDocument user = users.findByEmail(currentUser.email())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (user.getRole() != UserRole.USER) throw new ForbiddenException("Candidate matching is available to candidates only");
        return new CandidateContext(profiles.findByUserId(user.getId()).orElse(null));
    }

    private void prepare(JobDocument job) {
        if (features.prepare(job)) featureStore.persistIfCurrent(job);
    }

    private JobMatchResult calculate(CandidateProfileDocument profile, JobDocument job) {
        long started = System.nanoTime();
        try {
            JobMatchResult result = engine.calculate(profile, job);
            metrics.matchCalculation("success", System.nanoTime() - started);
            return result;
        } catch (RuntimeException failure) {
            metrics.matchCalculation("failure", System.nanoTime() - started);
            throw failure;
        }
    }

    private boolean visible(JobDocument job) {
        return !Boolean.FALSE.equals(job.getActive()) && job.getReconciliationTargetId() == null
                && job.getReconciliationConflictId() == null;
    }

    private void validate(int page, int size, double minMatch, String location, String source) {
        if (page < 0) throw new BadRequestException("page must be at least 0");
        if (size < 1 || size > MAX_PAGE_SIZE) throw new BadRequestException("size must be between 1 and 100");
        if (!Double.isFinite(minMatch) || minMatch < 0 || minMatch > 100)
            throw new BadRequestException("minMatch must be between 0 and 100");
        if (location != null && location.length() > 100) throw new BadRequestException("location filter is too long");
        if (source != null && source.length() > 30) throw new BadRequestException("source filter is too long");
        if (source != null && !source.isBlank()
                && !List.of("manual", "adzuna", "greenhouse", "lever").contains(source.trim().toLowerCase(Locale.ROOT)))
            throw new BadRequestException("Unsupported source filter");
    }

    private String normalizeEmployment(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = workAttributes.normalizeEmploymentPreference(value);
        if (!List.of("INTERNSHIP", "FULL_TIME", "PART_TIME", "CONTRACT").contains(normalized))
            throw new BadRequestException("Unsupported employmentType filter");
        return normalized;
    }

    private WorkMode normalizeWorkMode(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            WorkMode mode = WorkMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
            if (mode == WorkMode.UNKNOWN) throw new IllegalArgumentException();
            return mode;
        } catch (IllegalArgumentException failure) {
            throw new BadRequestException("Unsupported workMode filter");
        }
    }

    private RoleFamily normalizeRole(String value) {
        if (value == null || value.isBlank()) return null;
        RoleFamily normalized;
        try {
            normalized = RoleFamily.valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_'));
        } catch (IllegalArgumentException ignored) {
            normalized = roles.normalize(value);
        }
        if (normalized == RoleFamily.UNKNOWN) throw new BadRequestException("Unsupported role filter");
        return normalized;
    }

    private static LocalDateTime date(JobDocument job) {
        return job.getPublishedAt() == null ? job.getCreatedAt() : job.getPublishedAt();
    }

    private enum SortChoice {
        MATCH_SCORE("matchScore", "DESC"), NEWEST("newest", "DESC"), OLDEST("oldest", "ASC");
        private final String property;
        private final String direction;
        SortChoice(String property, String direction) { this.property = property; this.direction = direction; }
        String property() { return property; }
        String direction() { return direction; }
        static SortChoice parse(String raw) {
            String value = raw == null ? "matchScore" : raw.trim().toLowerCase(Locale.ROOT).split(",", 2)[0];
            return switch (value) {
                case "matchscore" -> MATCH_SCORE;
                case "newest" -> NEWEST;
                case "oldest" -> OLDEST;
                default -> throw new BadRequestException("sort must be matchScore, newest, or oldest");
            };
        }
        Comparator<ScoredJob> comparator() {
            Comparator<ScoredJob> newest = Comparator.comparing((ScoredJob value) -> date(value.job()),
                    Comparator.nullsLast(Comparator.reverseOrder()));
            Comparator<ScoredJob> id = Comparator.comparing(value -> value.job().getId(), Comparator.nullsLast(String::compareTo));
            if (this == OLDEST) return Comparator.comparing((ScoredJob value) -> date(value.job()),
                    Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(id);
            if (this == NEWEST) return newest.thenComparing(id);
            return Comparator.comparingDouble((ScoredJob value) -> value.match().overallScore()).reversed()
                    .thenComparing(newest).thenComparing(id);
        }
    }

    private record CandidateContext(CandidateProfileDocument profile) { }
    private record ScoredJob(JobDocument job, JobMatchResult match) { }
}
