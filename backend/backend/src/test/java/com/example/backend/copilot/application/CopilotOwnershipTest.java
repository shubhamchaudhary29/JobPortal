package com.example.backend.copilot.application;

import com.example.backend.candidate.application.parsing.SkillNormalizer;
import com.example.backend.copilot.infrastructure.CandidateJobWorkspaceRepository;
import com.example.backend.copilot.infrastructure.CoverLetterVersionRepository;
import com.example.backend.copilot.infrastructure.TailoredResumeVersionRepository;
import com.example.backend.copilot.infrastructure.WorkspaceQueryRepository;
import com.example.backend.application.infrastructure.ApplicationRepository;
import com.example.backend.job.infrastructure.JobRepository;
import com.example.backend.shared.error.ResourceNotFoundException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class CopilotOwnershipTest {
    @Test
    void candidateCannotAccessAnotherCandidatesResumeCoverLetterOrWorkspace() {
        var bundle = CopilotTestFixtures.bundle();
        CopilotAccessService access = mock(CopilotAccessService.class);
        when(access.candidate()).thenReturn(bundle.candidate());

        TailoredResumeVersionRepository resumes = mock(TailoredResumeVersionRepository.class);
        ResumeVersionService resumeService = new ResumeVersionService(resumes, mock(ApplicationCopilotAnalysisService.class),
                access, new SkillNormalizer(), mock(CopilotMetrics.class), mock(ApplicationWorkspaceService.class));
        when(resumes.findByIdAndUserId("foreign-resume", "user-1")).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> resumeService.get("foreign-resume"));
        verify(resumes).findByIdAndUserId("foreign-resume", "user-1");

        CoverLetterVersionRepository letters = mock(CoverLetterVersionRepository.class);
        CoverLetterService letterService = new CoverLetterService(letters, mock(ApplicationCopilotAnalysisService.class),
                access, new SkillNormalizer(), mock(CopilotMetrics.class), mock(ApplicationWorkspaceService.class));
        when(letters.findByIdAndUserId("foreign-letter", "user-1")).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> letterService.get("foreign-letter"));
        verify(letters).findByIdAndUserId("foreign-letter", "user-1");

        CandidateJobWorkspaceRepository workspaces = mock(CandidateJobWorkspaceRepository.class);
        ApplicationWorkspaceService workspaceService = new ApplicationWorkspaceService(workspaces,
                mock(WorkspaceQueryRepository.class), mock(ApplicationRepository.class), mock(JobRepository.class),
                access, mock(ApplicationCopilotAnalysisService.class));
        when(workspaces.findByUserIdAndJobId("user-1", "foreign-job")).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> workspaceService.get("foreign-job"));
        verify(workspaces).findByUserIdAndJobId("user-1", "foreign-job");
    }
}
