package com.example.backend.matching.application;

import com.example.backend.candidate.infrastructure.CandidateProfileDocument;
import com.example.backend.candidate.infrastructure.CandidateProfileRepository;
import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.job.infrastructure.JobRepository;
import com.example.backend.job.infrastructure.JobSearchRepository;
import com.example.backend.job.domain.JobMatchFeatures;
import com.example.backend.job.domain.RoleFamily;
import com.example.backend.job.domain.WorkMode;
import com.example.backend.matching.config.MatchingProperties;
import com.example.backend.matching.domain.*;
import com.example.backend.matching.extraction.RoleNormalizer;
import com.example.backend.matching.extraction.WorkAttributeNormalizer;
import com.example.backend.matching.infrastructure.JobFeatureStore;
import com.example.backend.shared.error.BadRequestException;
import com.example.backend.shared.error.ForbiddenException;
import com.example.backend.shared.error.ResourceNotFoundException;
import com.example.backend.shared.security.CurrentUserProvider;
import com.example.backend.user.domain.UserRole;
import com.example.backend.user.infrastructure.UserDocument;
import com.example.backend.user.infrastructure.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JobMatchingServiceTest {
    private JobRepository jobs;
    private JobSearchRepository search;
    private UserRepository users;
    private CandidateProfileRepository profiles;
    private JobFeatureService features;
    private JobFeatureStore featureStore;
    private JobMatchEngine engine;
    private JobMatchingService service;

    @BeforeEach
    void setUp() {
        jobs = mock(JobRepository.class); search = mock(JobSearchRepository.class); users = mock(UserRepository.class);
        profiles = mock(CandidateProfileRepository.class); features = mock(JobFeatureService.class);
        featureStore = mock(JobFeatureStore.class);
        engine = mock(JobMatchEngine.class); MatchingMetrics metrics = mock(MatchingMetrics.class);
        CurrentUserProvider current = mock(CurrentUserProvider.class);
        when(current.email()).thenReturn("candidate@example.test");
        when(users.findByEmail(anyString())).thenReturn(Optional.of(user("candidate-1", UserRole.USER)));
        when(profiles.findByUserId("candidate-1")).thenReturn(Optional.of(new CandidateProfileDocument()));
        service = new JobMatchingService(jobs, search, users, profiles, current, features, featureStore, engine, metrics,
                new MatchingProperties(), new RoleNormalizer(), new WorkAttributeNormalizer());
    }

    @Test
    void derivesCandidateFromAuthenticationAndLazilyPersistsOldJobFeatures() {
        JobDocument job = job("job-1", 80, LocalDateTime.now());
        when(jobs.findById("job-1")).thenReturn(Optional.of(job));
        when(features.prepare(job)).thenReturn(true);
        when(engine.calculate(any(), eq(job))).thenReturn(result("job-1", 80));
        assertEquals("job-1", service.match("job-1").jobId());
        verify(profiles).findByUserId("candidate-1");
        verify(featureStore).persistIfCurrent(job);
        verify(jobs, never()).save(job);
    }

    @Test
    void missingProfileReturnsLowDataResultInsteadOfFailing() {
        when(profiles.findByUserId("candidate-1")).thenReturn(Optional.empty());
        JobDocument job = job("job-1", 0, LocalDateTime.now());
        when(jobs.findById("job-1")).thenReturn(Optional.of(job));
        when(engine.calculate(isNull(), eq(job))).thenReturn(result("job-1", 0));
        assertEquals(MatchLevel.LOW_DATA, service.match("job-1").matchLevel());
    }

    @Test
    void recruiterIsRejectedAndInactiveOrConflictedJobsRemainHidden() {
        when(users.findByEmail(anyString())).thenReturn(Optional.of(user("r1", UserRole.RECRUITER)));
        assertThrows(ForbiddenException.class, () -> service.match("job-1"));
        when(users.findByEmail(anyString())).thenReturn(Optional.of(user("candidate-1", UserRole.USER)));
        JobDocument inactive = job("inactive", 0, LocalDateTime.now()); inactive.setActive(false);
        when(jobs.findById("inactive")).thenReturn(Optional.of(inactive));
        assertThrows(ResourceNotFoundException.class, () -> service.match("inactive"));
        JobDocument conflict = job("conflict", 0, LocalDateTime.now()); conflict.setReconciliationConflictId("conflict-1");
        when(jobs.findById("conflict")).thenReturn(Optional.of(conflict));
        assertThrows(ResourceNotFoundException.class, () -> service.match("conflict"));
    }

    @Test
    void personalizedFeedRanksByScoreThenFreshnessAndPaginates() {
        JobDocument olderHigh = job("a", 90, LocalDateTime.of(2026, 1, 1, 0, 0));
        JobDocument newerHigh = job("b", 90, LocalDateTime.of(2026, 2, 1, 0, 0));
        JobDocument low = job("c", 30, LocalDateTime.of(2026, 3, 1, 0, 0));
        when(search.matchingCandidates(null, null, 500)).thenReturn(List.of(olderHigh, low, newerHigh));
        when(engine.calculate(any(), any())).thenAnswer(invocation -> {
            JobDocument value = invocation.getArgument(1);
            return result(value.getId(), Double.parseDouble(value.getDescription()));
        });
        var page = service.matched(0, 1, 50, null, null, null, null, null, "matchScore");
        assertEquals(2, page.totalElements());
        assertEquals("b", page.content().get(0).job().id());
        assertEquals(2, page.totalPages());
        assertFalse(page.last());
    }

    @Test
    void lazilyExtractedFeedJobsArePersistedInOneBoundedBatch() {
        JobDocument first = job("a", 80, LocalDateTime.now());
        JobDocument second = job("b", 70, LocalDateTime.now());
        when(search.matchingCandidates(null, null, 500)).thenReturn(List.of(first, second));
        when(features.prepare(any())).thenReturn(true);
        when(engine.calculate(any(), any())).thenReturn(result("job", 80));
        service.matched(0, 20, 0, null, null, null, null, null, "matchScore");
        verify(featureStore).persistAllIfCurrent(List.of(first, second));
        verify(jobs, never()).saveAll(any());
    }

    @Test
    void filtersEmploymentWorkModeRoleAndSupportsNewestOldest() {
        JobDocument job = job("job-1", 80, LocalDateTime.of(2026, 1, 1, 0, 0));
        job.getMatchFeatures().setEmploymentType("FULL_TIME");
        job.getMatchFeatures().setWorkMode(WorkMode.REMOTE);
        job.getMatchFeatures().setRoleFamily(RoleFamily.BACKEND);
        when(search.matchingCandidates("Pune", "manual", 500)).thenReturn(List.of(job));
        when(engine.calculate(any(), eq(job))).thenReturn(result("job-1", 80));
        assertEquals(1, service.matched(0, 20, 0, "Pune", "manual", "full-time", "remote",
                "Backend Developer", "newest").totalElements());
        assertDoesNotThrow(() -> service.matched(0, 20, 0, "Pune", "manual", null, null,
                "BACKEND", "matchScore"));
        assertDoesNotThrow(() -> service.matched(0, 20, 0, "Pune", "manual", null, null, null, "oldest"));
    }

    @Test
    void invalidPaginationScoreAndFiltersAreRejectedBeforeMongo() {
        assertThrows(BadRequestException.class, () -> service.matched(-1, 20, 0, null, null, null, null, null, null));
        assertThrows(BadRequestException.class, () -> service.matched(0, 101, 0, null, null, null, null, null, null));
        assertThrows(BadRequestException.class, () -> service.matched(0, 20, 101, null, null, null, null, null, null));
        assertThrows(BadRequestException.class, () -> service.matched(0, 20, 0, null, null, "volunteer", null, null, null));
        assertThrows(BadRequestException.class, () -> service.matched(0, 20, 0, null, "unknown", null, null, null, null));
        assertThrows(BadRequestException.class, () -> service.matched(0, 20, 0, null, null, null, "somewhere", null, null));
        assertThrows(BadRequestException.class, () -> service.matched(0, 20, 0, null, null, null, null, "Wizard", null));
        assertThrows(BadRequestException.class, () -> service.matched(0, 20, 0, null, null, null, null, null, "salary"));
        verifyNoInteractions(search);
    }

    private JobDocument job(String id, double score, LocalDateTime created) {
        JobDocument job = new JobDocument(); job.setId(id); job.setTitle("Backend Engineer");
        job.setDescription(Double.toString(score)); job.setLocation("Pune"); job.setCompany("Company");
        job.setCreatedAt(created); job.setActive(true); job.setMatchFeatures(new JobMatchFeatures());
        return job;
    }

    private JobMatchResult result(String id, double score) {
        return new JobMatchResult(id, score, score >= 75 ? MatchLevel.STRONG : MatchLevel.LOW_DATA,
                score >= 75 ? DataConfidence.HIGH : DataConfidence.LOW, List.of(), List.of(), List.of(),
                null, null, null, null, null, null, Map.of(), List.of(), List.of(), List.of(),
                MatchingProperties.SCORING_VERSION);
    }

    private UserDocument user(String id, UserRole role) {
        UserDocument user = new UserDocument(); user.setId(id); user.setRole(role); return user;
    }
}
