package com.example.backend.copilot.infrastructure;

import com.example.backend.copilot.domain.CopilotModels.JobSnapshot;
import com.example.backend.copilot.domain.CopilotModels.PersonalApplicationStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class CopilotPersistenceMongoIntegrationTest {
    @Autowired TailoredResumeVersionRepository resumes;
    @Autowired CoverLetterVersionRepository letters;
    @Autowired CandidateJobWorkspaceRepository workspaces;
    @Autowired WorkspaceQueryRepository workspaceQuery;
    @Autowired MongoTemplate mongo;

    @AfterEach
    void clean() { resumes.deleteAll(); letters.deleteAll(); workspaces.deleteAll(); }

    @Test
    void versionOwnershipQueriesAreIsolatedAndWorkspaceIsUniquePerCandidateJob() {
        TailoredResumeVersionDocument resume = new TailoredResumeVersionDocument();
        resume.setUserId("candidate-a"); resume.setJobId("job-1"); resume.setCreatedAt(Instant.now()); resume.setUpdatedAt(Instant.now());
        TailoredResumeVersionDocument savedResume = resumes.save(resume);
        CoverLetterVersionDocument letter = new CoverLetterVersionDocument();
        letter.setUserId("candidate-a"); letter.setJobId("job-1"); letter.setCreatedAt(Instant.now()); letter.setUpdatedAt(Instant.now());
        CoverLetterVersionDocument savedLetter = letters.save(letter);
        assertAll(
                () -> assertTrue(resumes.findByIdAndUserId(savedResume.getId(), "candidate-a").isPresent()),
                () -> assertTrue(resumes.findByIdAndUserId(savedResume.getId(), "candidate-b").isEmpty()),
                () -> assertTrue(letters.findByIdAndUserId(savedLetter.getId(), "candidate-a").isPresent()),
                () -> assertTrue(letters.findByIdAndUserId(savedLetter.getId(), "candidate-b").isEmpty())
        );

        CandidateJobWorkspaceDocument first = workspace("candidate-a", "job-1");
        workspaces.save(first);
        assertThrows(DuplicateKeyException.class, () -> workspaces.save(workspace("candidate-a", "job-1")));
        workspaces.save(workspace("candidate-b", "job-1"));
        assertEquals(2, workspaces.count());
    }

    @Test
    void requiredCopilotIndexesExist() {
        assertTrue(mongo.indexOps("candidate_job_workspaces").getIndexInfo().stream()
                .anyMatch(index -> "candidate_workspace_user_job_unique".equals(index.getName()) && index.isUnique()));
        assertTrue(mongo.indexOps("tailored_resume_versions").getIndexInfo().stream()
                .anyMatch(index -> "tailored_resume_user_job_created_idx".equals(index.getName())));
        assertTrue(mongo.indexOps("cover_letter_versions").getIndexInfo().stream()
                .anyMatch(index -> "cover_letter_user_job_created_idx".equals(index.getName())));
    }

    @Test
    void workspaceSearchStageFilterAndPaginationAreBoundedAndDeterministic() {
        CandidateJobWorkspaceDocument older = workspace("candidate-a", "job-1");
        older.setJobSnapshot(new JobSnapshot("job-1", "Backend Engineer", "Example Corp", null, null, null, null));
        older.setUpdatedAt(Instant.parse("2026-08-01T00:00:00Z"));
        CandidateJobWorkspaceDocument newer = workspace("candidate-a", "job-2");
        newer.setJobSnapshot(new JobSnapshot("job-2", "Platform Engineer", "Example Corp", null, null, null, null));
        newer.setUpdatedAt(Instant.parse("2026-09-01T00:00:00Z"));
        CandidateJobWorkspaceDocument other = workspace("candidate-b", "job-3");
        other.setJobSnapshot(new JobSnapshot("job-3", "Backend Engineer", "Example Corp", null, null, null, null));
        workspaces.saveAll(java.util.List.of(older, newer, other));

        WorkspaceQueryRepository.Result first = workspaceQuery.find("candidate-a", PersonalApplicationStage.SAVED,
                "Example", 0, 1);
        WorkspaceQueryRepository.Result second = workspaceQuery.find("candidate-a", PersonalApplicationStage.SAVED,
                "Example", 1, 1);
        assertAll(
                () -> assertEquals(2, first.total()),
                () -> assertEquals("job-2", first.content().get(0).getJobId()),
                () -> assertEquals("job-1", second.content().get(0).getJobId())
        );
    }

    private CandidateJobWorkspaceDocument workspace(String userId, String jobId) {
        CandidateJobWorkspaceDocument value = new CandidateJobWorkspaceDocument();
        value.setUserId(userId); value.setJobId(jobId); value.setStage(PersonalApplicationStage.SAVED);
        value.setJobSnapshot(new JobSnapshot(jobId, "Engineer", "Example", null, null, null, null));
        value.setCreatedAt(Instant.now()); value.setUpdatedAt(Instant.now()); return value;
    }
}
