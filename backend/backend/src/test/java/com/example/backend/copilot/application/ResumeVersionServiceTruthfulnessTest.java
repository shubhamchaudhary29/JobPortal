package com.example.backend.copilot.application;

import com.example.backend.candidate.application.parsing.SkillNormalizer;
import com.example.backend.copilot.api.dto.CopilotRequests.UpdateResumeVersionRequest;
import com.example.backend.copilot.domain.CopilotModels.ResumeStaleness;
import com.example.backend.copilot.infrastructure.TailoredResumeVersionDocument;
import com.example.backend.copilot.infrastructure.TailoredResumeVersionRepository;
import com.example.backend.shared.error.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ResumeVersionServiceTruthfulnessTest {
    private TailoredResumeVersionRepository versions;
    private ApplicationCopilotAnalysisService analysis;
    private CopilotAccessService access;
    private ApplicationWorkspaceService workspaces;
    private ResumeVersionService service;
    private ApplicationCopilotAnalysisService.AnalysisBundle bundle;

    @BeforeEach
    void setUp() {
        versions = mock(TailoredResumeVersionRepository.class);
        analysis = mock(ApplicationCopilotAnalysisService.class);
        access = mock(CopilotAccessService.class);
        workspaces = mock(ApplicationWorkspaceService.class);
        service = new ResumeVersionService(versions, analysis, access, new SkillNormalizer(), mock(CopilotMetrics.class), workspaces);
        bundle = CopilotTestFixtures.bundle();
        when(analysis.analyze("job-1")).thenReturn(bundle);
        when(access.snapshot(bundle.job())).thenReturn(new com.example.backend.copilot.domain.CopilotModels.JobSnapshot(
                "job-1", "Backend Engineer", "Example Corp", "Remote", "manual", null, null));
        AtomicInteger ids = new AtomicInteger();
        when(versions.save(any())).thenAnswer(invocation -> {
            TailoredResumeVersionDocument value = invocation.getArgument(0);
            if (value.getId() == null) value.setId("version-" + ids.incrementAndGet());
            return value;
        });
    }

    @Test
    void missingSkillsAndMetricsAreNeverFabricatedAndExistingMetricIsPreserved() {
        var originalSkills = bundle.candidate().profile().getSkills().stream().map(value -> value.getName()).toList();
        var result = service.create("job-1", null);
        String generated = result.content().toString();
        assertAll(
                () -> assertFalse(result.content().skills().contains("Kafka")),
                () -> assertFalse(result.content().skills().contains("Kubernetes")),
                () -> assertFalse(generated.contains("30%")),
                () -> assertFalse(generated.contains("40%")),
                () -> assertFalse(generated.toLowerCase().contains("2x")),
                () -> assertTrue(generated.contains("20%")),
                () -> assertEquals("Java", result.content().skills().get(0)),
                () -> assertEquals("JobPortal", result.content().projects().get(0).name()),
                () -> assertEquals(originalSkills, bundle.candidate().profile().getSkills().stream().map(value -> value.getName()).toList()),
                () -> verify(workspaces).linkResume(bundle, result.id())
        );
    }

    @Test
    void regenerationCreatesSeparateHistoryAndDoesNotMutateEarlierVersion() {
        when(versions.countByUserIdAndJobId("user-1", "job-1")).thenReturn(0L, 1L);
        var first = service.create("job-1", null);
        var second = service.create("job-1", null);
        assertAll(
                () -> assertNotEquals(first.id(), second.id()),
                () -> assertEquals(1, first.versionNumber()),
                () -> assertEquals(2, second.versionNumber()),
                () -> assertEquals("Improved API performance", first.content().experience().get(0).description())
        );
    }

    @Test
    void manualEditsPersistButCannotInjectNewSkillsOrChangeEvidenceIdentity() {
        var created = service.create("job-1", null);
        TailoredResumeVersionDocument stored = captureSaved();
        when(access.candidate()).thenReturn(bundle.candidate());
        when(access.active(any())).thenReturn(true);
        when(access.job("job-1")).thenReturn(bundle.job());
        when(versions.findByIdAndUserId(created.id(), "user-1")).thenReturn(Optional.of(stored));
        var request = new UpdateResumeVersionRequest(null, "Edited truthful summary", List.of("Java", "AWS"),
                List.of("Improved API performance", "Reduced latency by 20%"),
                List.of("Containerized the application for deployment.", "Created a writing sample."), null);
        var updated = service.update(created.id(), request);
        assertAll(
                () -> assertEquals("Edited truthful summary", updated.content().summary()),
                () -> assertEquals(List.of("Java", "AWS"), updated.content().skills()),
                () -> assertEquals("Acme", updated.content().experience().get(0).organization()),
                () -> assertThrows(BadRequestException.class, () -> service.update(created.id(),
                        new UpdateResumeVersionRequest(null, null, List.of("Kafka"), null, null, null)))
        );
    }

    @Test
    void profileUpdateMarksHistoricalVersionOutdated() {
        var created = service.create("job-1", null);
        TailoredResumeVersionDocument stored = captureSaved();
        bundle.candidate().profile().setUpdatedAt(Instant.parse("2026-09-01T00:00:00Z"));
        when(access.candidate()).thenReturn(bundle.candidate());
        when(access.job("job-1")).thenReturn(bundle.job());
        when(access.active(bundle.job())).thenReturn(true);
        when(versions.findByIdAndUserId(created.id(), "user-1")).thenReturn(Optional.of(stored));
        assertEquals(ResumeStaleness.OUTDATED, service.get(created.id()).staleness());
    }

    private TailoredResumeVersionDocument captureSaved() {
        var captor = org.mockito.ArgumentCaptor.forClass(TailoredResumeVersionDocument.class);
        verify(versions, atLeastOnce()).save(captor.capture());
        return captor.getAllValues().get(0);
    }
}
