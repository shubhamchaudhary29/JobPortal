package com.example.backend.copilot.application;

import com.example.backend.application.domain.ApplicationStatus;
import com.example.backend.application.infrastructure.ApplicationDocument;
import com.example.backend.application.infrastructure.ApplicationRepository;
import com.example.backend.copilot.api.dto.CopilotRequests.UpdateWorkspaceRequest;
import com.example.backend.copilot.domain.CopilotModels.JobSnapshot;
import com.example.backend.copilot.domain.CopilotModels.PersonalApplicationStage;
import com.example.backend.copilot.infrastructure.CandidateJobWorkspaceDocument;
import com.example.backend.copilot.infrastructure.CandidateJobWorkspaceRepository;
import com.example.backend.copilot.infrastructure.WorkspaceQueryRepository;
import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.job.infrastructure.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ApplicationWorkspaceServiceTest {
    private CandidateJobWorkspaceRepository workspaces;
    private WorkspaceQueryRepository query;
    private ApplicationRepository applications;
    private JobRepository jobs;
    private CopilotAccessService access;
    private ApplicationCopilotAnalysisService analysis;
    private ApplicationWorkspaceService service;
    private ApplicationCopilotAnalysisService.AnalysisBundle bundle;

    @BeforeEach
    void setUp() {
        workspaces = mock(CandidateJobWorkspaceRepository.class); query = mock(WorkspaceQueryRepository.class);
        applications = mock(ApplicationRepository.class); jobs = mock(JobRepository.class);
        access = mock(CopilotAccessService.class); analysis = mock(ApplicationCopilotAnalysisService.class);
        service = new ApplicationWorkspaceService(workspaces, query, applications, jobs, access, analysis);
        bundle = CopilotTestFixtures.bundle();
        when(analysis.analyze("job-1")).thenReturn(bundle);
        when(access.snapshot(bundle.job())).thenReturn(new JobSnapshot("job-1", "Backend Engineer", "Example Corp",
                "Remote", "manual", null, null));
        when(access.active(bundle.job())).thenReturn(true);
        when(workspaces.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void savesOncePerCandidateJobAndTracksExternalApplicationNotesAndFollowUp() {
        when(workspaces.findByUserIdAndJobId("user-1", "job-1")).thenReturn(Optional.empty());
        Instant followUp = Instant.now().plusSeconds(86400);
        var result = service.update("job-1", new UpdateWorkspaceRequest(PersonalApplicationStage.APPLIED,
                "Ask about the backend team", followUp, true));
        assertAll(
                () -> assertEquals(PersonalApplicationStage.APPLIED, result.stage()),
                () -> assertTrue(result.appliedExternally()),
                () -> assertNotNull(result.appliedAt()),
                () -> assertEquals("Ask about the backend team", result.notes()),
                () -> assertEquals("UPCOMING", result.followUpStatus()),
                () -> assertEquals("user-1", captured().getUserId()),
                () -> assertEquals("job-1", captured().getJobId())
        );
    }

    @Test
    void inPlatformApplicationStatusRemainsAuthoritativeAndSeparate() {
        when(workspaces.findByUserIdAndJobId("user-1", "job-1")).thenReturn(Optional.empty());
        ApplicationDocument application = new ApplicationDocument(); application.setJobId("job-1");
        application.setStatus(ApplicationStatus.SHORTLISTED);
        when(applications.findByUserIdAndJobId("candidate@example.test", "job-1")).thenReturn(Optional.of(application));
        var result = service.update("job-1", new UpdateWorkspaceRequest(PersonalApplicationStage.PREPARING,
                null, null, true));
        assertAll(
                () -> assertEquals(ApplicationStatus.SHORTLISTED, result.recruiterStatus()),
                () -> assertEquals(PersonalApplicationStage.APPLIED, result.stage()),
                () -> assertFalse(result.appliedExternally())
        );
    }

    @Test
    void inactiveOrDeletedJobRetainsHistoricalWorkspace() {
        CandidateJobWorkspaceDocument workspace = workspace();
        when(access.candidate()).thenReturn(bundle.candidate());
        when(workspaces.findByUserIdAndJobId("user-1", "job-1")).thenReturn(Optional.of(workspace));
        JobDocument inactive = bundle.job(); inactive.setActive(false);
        when(jobs.findById("job-1")).thenReturn(Optional.of(inactive));
        when(access.active(inactive)).thenReturn(false);
        var result = service.get("job-1");
        assertFalse(result.active());
        assertEquals("Backend Engineer", result.job().title());
    }

    @Test
    void analyticsHandlesZeroAndCalculatesHonestRates() {
        when(access.candidate()).thenReturn(bundle.candidate());
        var zero = service.analytics();
        assertAll(() -> assertNull(zero.responseRate()), () -> assertNotNull(zero.message()));
        when(workspaces.countByUserIdAndStage("user-1", PersonalApplicationStage.APPLIED)).thenReturn(2L);
        when(workspaces.countByUserIdAndStage("user-1", PersonalApplicationStage.INTERVIEW)).thenReturn(1L);
        when(workspaces.countByUserIdAndStage("user-1", PersonalApplicationStage.OFFER)).thenReturn(1L);
        when(workspaces.countByUserIdAndStage("user-1", PersonalApplicationStage.REJECTED)).thenReturn(1L);
        var populated = service.analytics();
        assertAll(
                () -> assertEquals(5, populated.applied()),
                () -> assertEquals(60.0, populated.responseRate()),
                () -> assertEquals(40.0, populated.interviewRate()),
                () -> assertEquals(20.0, populated.offerRate())
        );
    }

    @Test
    void listUsesBoundedQueryAndBatchJobAndApplicationLookups() {
        when(access.candidate()).thenReturn(bundle.candidate());
        CandidateJobWorkspaceDocument workspace = workspace();
        when(query.find("user-1", PersonalApplicationStage.SAVED, "Example", 0, 20))
                .thenReturn(new WorkspaceQueryRepository.Result(List.of(workspace), 1));
        when(jobs.findAllById(any())).thenReturn(List.of(bundle.job()));
        when(applications.findByUserIdAndJobIdIn(eq("candidate@example.test"), any())).thenReturn(List.of());
        var page = service.list(0, 20, PersonalApplicationStage.SAVED, "Example");
        assertAll(() -> assertEquals(1, page.totalElements()), () -> assertEquals(1, page.content().size()));
        verify(jobs).findAllById(any());
        verify(applications).findByUserIdAndJobIdIn(eq("candidate@example.test"), any());
    }

    private CandidateJobWorkspaceDocument workspace() {
        CandidateJobWorkspaceDocument value = new CandidateJobWorkspaceDocument();
        value.setUserId("user-1"); value.setJobId("job-1"); value.setJobSnapshot(new JobSnapshot("job-1",
                "Backend Engineer", "Example Corp", "Remote", "manual", null, null));
        value.setStage(PersonalApplicationStage.SAVED); value.setCreatedAt(Instant.now()); value.setUpdatedAt(Instant.now());
        return value;
    }
    private CandidateJobWorkspaceDocument captured() {
        var captor = org.mockito.ArgumentCaptor.forClass(CandidateJobWorkspaceDocument.class);
        verify(workspaces, atLeastOnce()).save(captor.capture()); return captor.getAllValues().get(0);
    }
}
