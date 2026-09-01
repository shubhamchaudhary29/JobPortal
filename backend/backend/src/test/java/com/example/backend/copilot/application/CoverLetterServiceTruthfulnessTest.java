package com.example.backend.copilot.application;

import com.example.backend.candidate.application.parsing.SkillNormalizer;
import com.example.backend.candidate.infrastructure.CandidateProfileDocument;
import com.example.backend.copilot.api.dto.CopilotRequests.UpdateCoverLetterRequest;
import com.example.backend.copilot.infrastructure.CoverLetterVersionDocument;
import com.example.backend.copilot.infrastructure.CoverLetterVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CoverLetterServiceTruthfulnessTest {
    private CoverLetterVersionRepository versions;
    private ApplicationCopilotAnalysisService analysis;
    private CopilotAccessService access;
    private ApplicationWorkspaceService workspaces;
    private CoverLetterService service;
    private ApplicationCopilotAnalysisService.AnalysisBundle bundle;

    @BeforeEach
    void setUp() {
        versions = mock(CoverLetterVersionRepository.class);
        analysis = mock(ApplicationCopilotAnalysisService.class);
        access = mock(CopilotAccessService.class);
        workspaces = mock(ApplicationWorkspaceService.class);
        service = new CoverLetterService(versions, analysis, access, new SkillNormalizer(), mock(CopilotMetrics.class), workspaces);
        bundle = CopilotTestFixtures.bundle();
        when(analysis.analyze("job-1")).thenReturn(bundle);
        when(access.snapshot(bundle.job())).thenReturn(new com.example.backend.copilot.domain.CopilotModels.JobSnapshot(
                "job-1", "Backend Engineer", "Example Corp", "Remote", "manual", null, null));
        AtomicInteger ids = new AtomicInteger();
        when(versions.save(any())).thenAnswer(invocation -> {
            CoverLetterVersionDocument value = invocation.getArgument(0);
            if (value.getId() == null) value.setId("letter-" + ids.incrementAndGet());
            return value;
        });
    }

    @Test
    void draftUsesJobAndCandidateEvidenceWithoutMissingSkillsCompanyResearchOrMetrics() {
        var result = service.create("job-1", null);
        assertAll(
                () -> assertTrue(result.content().contains("Backend Engineer")),
                () -> assertTrue(result.content().contains("Example Corp")),
                () -> assertTrue(result.content().contains("Improved API performance")),
                () -> assertFalse(result.content().contains("Kafka")),
                () -> assertFalse(result.content().contains("Kubernetes")),
                () -> assertFalse(result.content().contains("30%")),
                () -> assertFalse(result.content().contains("40%")),
                () -> assertFalse(result.content().toLowerCase().contains("industry-leading")),
                () -> verify(workspaces).linkCoverLetter(bundle, result.id())
        );
    }

    @Test
    void existingMetricIsPreservedVerbatim() {
        CandidateProfileDocument profile = bundle.candidate().profile();
        profile.setExperience(List.of(new CandidateProfileDocument.Experience("Acme", "Backend Engineer", null, null,
                "2024-01", null, true, "Reduced latency by 20%", List.of("Java"))));
        assertTrue(service.create("job-1", null).content().contains("20%"));
    }

    @Test
    void candidateEditsPersistAndRegenerationCreatesSeparateVersion() {
        when(versions.countByUserIdAndJobId("user-1", "job-1")).thenReturn(0L, 1L);
        var first = service.create("job-1", null);
        var captor = org.mockito.ArgumentCaptor.forClass(CoverLetterVersionDocument.class);
        verify(versions, atLeastOnce()).save(captor.capture());
        CoverLetterVersionDocument stored = captor.getAllValues().get(0);
        when(access.candidate()).thenReturn(bundle.candidate());
        when(access.job("job-1")).thenReturn(bundle.job()); when(access.active(bundle.job())).thenReturn(true);
        when(versions.findByIdAndUserId(first.id(), "user-1")).thenReturn(Optional.of(stored));
        var edited = service.update(first.id(), new UpdateCoverLetterRequest(null, "Candidate-authored final draft"));
        var second = service.create("job-1", null);
        assertAll(
                () -> assertEquals("Candidate-authored final draft", edited.content()),
                () -> assertNotEquals(first.id(), second.id()),
                () -> assertEquals(2, second.versionNumber())
        );
    }
}
