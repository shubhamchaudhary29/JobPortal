package com.example.backend.copilot.application;

import com.example.backend.candidate.infrastructure.CandidateProfileDocument;
import com.example.backend.candidate.infrastructure.CandidateProfileRepository;
import com.example.backend.copilot.domain.CopilotModels.JobSnapshot;
import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.job.infrastructure.JobRepository;
import com.example.backend.matching.application.JobFeatureService;
import com.example.backend.matching.infrastructure.JobFeatureStore;
import com.example.backend.shared.error.BadRequestException;
import com.example.backend.shared.error.ForbiddenException;
import com.example.backend.shared.error.ResourceNotFoundException;
import com.example.backend.shared.security.CurrentUserProvider;
import com.example.backend.shared.validation.SafeExternalUrl;
import com.example.backend.user.domain.UserRole;
import com.example.backend.user.infrastructure.UserDocument;
import com.example.backend.user.infrastructure.UserRepository;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class CopilotAccessService {
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,100}$");
    private final CurrentUserProvider currentUser;
    private final UserRepository users;
    private final CandidateProfileRepository profiles;
    private final JobRepository jobs;
    private final JobFeatureService features;
    private final JobFeatureStore featureStore;

    public CopilotAccessService(CurrentUserProvider currentUser, UserRepository users,
                                CandidateProfileRepository profiles, JobRepository jobs,
                                JobFeatureService features, JobFeatureStore featureStore) {
        this.currentUser = currentUser;
        this.users = users;
        this.profiles = profiles;
        this.jobs = jobs;
        this.features = features;
        this.featureStore = featureStore;
    }

    public CandidateContext candidate() {
        UserDocument user = users.findByEmail(currentUser.email().strip().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (user.getRole() != UserRole.USER)
            throw new ForbiddenException("Application Copilot is available to candidates only");
        CandidateProfileDocument profile = profiles.findByUserId(user.getId()).orElseGet(() -> {
            CandidateProfileDocument empty = new CandidateProfileDocument();
            empty.setUserId(user.getId());
            return empty;
        });
        return new CandidateContext(user, profile);
    }

    public JobDocument job(String jobId) {
        validateId(jobId);
        JobDocument job = jobs.findById(jobId)
                .filter(value -> value.getReconciliationTargetId() == null && value.getReconciliationConflictId() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Job not found"));
        if (features.prepare(job)) featureStore.persistIfCurrent(job);
        return job;
    }

    public boolean active(JobDocument job) { return !Boolean.FALSE.equals(job.getActive()); }

    public JobSnapshot snapshot(JobDocument job) {
        String url = SafeExternalUrl.parse(job.getApplicationUrl()).orElseGet(() ->
                SafeExternalUrl.parse(job.getSourceUrl()).orElse(null));
        return new JobSnapshot(job.getId(), job.getTitle(), job.getCompany(), job.getLocation(), job.getSource(),
                job.getEmploymentType(), url);
    }

    public void validateId(String id) {
        if (id == null || !SAFE_ID.matcher(id).matches()) throw new BadRequestException("Malformed resource id");
    }

    public record CandidateContext(UserDocument user, CandidateProfileDocument profile) { }
}
