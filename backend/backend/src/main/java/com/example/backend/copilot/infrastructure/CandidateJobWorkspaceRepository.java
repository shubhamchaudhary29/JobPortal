package com.example.backend.copilot.infrastructure;

import com.example.backend.copilot.domain.CopilotModels.PersonalApplicationStage;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface CandidateJobWorkspaceRepository extends MongoRepository<CandidateJobWorkspaceDocument, String> {
    Optional<CandidateJobWorkspaceDocument> findByUserIdAndJobId(String userId, String jobId);
    long countByUserIdAndStage(String userId, PersonalApplicationStage stage);
}
