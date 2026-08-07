package com.example.backend.job.application;

import com.example.backend.job.JobMapper;
import com.example.backend.job.api.dto.CreateJobRequest;
import com.example.backend.job.api.dto.JobResponse;
import com.example.backend.job.api.dto.UpdateJobRequest;
import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.job.infrastructure.JobRepository;
import com.example.backend.job.infrastructure.JobSearchRepository;
import com.example.backend.shared.error.BadRequestException;
import com.example.backend.shared.error.ForbiddenException;
import com.example.backend.shared.error.ResourceNotFoundException;
import com.example.backend.shared.pagination.PageResponse;
import com.example.backend.shared.security.CurrentUserProvider;
import com.example.backend.user.domain.UserRole;
import com.example.backend.user.infrastructure.UserDocument;
import com.example.backend.user.infrastructure.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class JobService {
    private final JobRepository jobs;
    private final JobSearchRepository search;
    private final UserRepository users;
    private final CurrentUserProvider currentUser;

    public JobService(JobRepository jobs, JobSearchRepository search, UserRepository users,
                      CurrentUserProvider currentUser) {
        this.jobs = jobs;
        this.search = search;
        this.users = users;
        this.currentUser = currentUser;
    }

    public JobResponse create(CreateJobRequest request) {
        UserDocument recruiter = requireRecruiter();
        JobDocument document = JobMapper.fromCreate(request);
        document.setRecruiterId(recruiter.getId());
        document.setSource("manual");
        return JobMapper.toResponse(jobs.save(document));
    }

    public PageResponse<JobResponse> search(String q, String location, String source, Pageable pageable) {
        validateFilter(q, "q", 100);
        validateFilter(location, "location", 100);
        validateFilter(source, "source", 30);
        Page<JobResponse> result = search.search(q, location, source, pageable).map(JobMapper::toResponse);
        return PageResponse.from(result);
    }

    public JobResponse get(String id) { return JobMapper.toResponse(requireJob(id)); }

    public PageResponse<JobResponse> mine(Pageable pageable) {
        UserDocument recruiter = requireRecruiter();
        return PageResponse.from(jobs.findByRecruiterId(recruiter.getId(), pageable).map(JobMapper::toResponse));
    }

    public JobResponse update(String id, UpdateJobRequest request) {
        JobDocument document = requireOwned(id);
        JobMapper.applyUpdate(request, document);
        return JobMapper.toResponse(jobs.save(document));
    }

    public void delete(String id) { jobs.delete(requireOwned(id)); }

    public JobDocument requireJob(String id) {
        return jobs.findById(id).orElseThrow(() -> new ResourceNotFoundException("Job not found"));
    }

    public JobDocument requireOwnedJob(String id, String email) {
        UserDocument recruiter = users.findByEmail(email).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        JobDocument job = requireJob(id);
        if (recruiter.getRole() != UserRole.RECRUITER || !recruiter.getId().equals(job.getRecruiterId()))
            throw new ForbiddenException("Not authorized to manage this job");
        return job;
    }

    private JobDocument requireOwned(String id) { return requireOwnedJob(id, currentUser.email()); }

    private UserDocument requireRecruiter() {
        UserDocument user = users.findByEmail(currentUser.email())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (user.getRole() != UserRole.RECRUITER) throw new ForbiddenException("Only recruiters can manage jobs");
        return user;
    }

    private void validateFilter(String value, String name, int max) {
        if (value != null && value.length() > max) throw new BadRequestException(name + " filter is too long");
    }
}
