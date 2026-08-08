package com.example.backend.application;

import com.example.backend.application.api.dto.ApplicationResponse;
import com.example.backend.application.api.dto.ApplicationSummaryResponse;
import com.example.backend.application.domain.ApplicationStatus;
import com.example.backend.application.infrastructure.ApplicationDocument;
import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.shared.validation.SafeExternalUrl;

public final class ApplicationMapper {
    private ApplicationMapper() { }

    public static ApplicationResponse toResponse(ApplicationDocument document) {
        return new ApplicationResponse(document.getId(), document.getJobId(), document.getUserId(),
                status(document), document.getAppliedAt());
    }

    public static ApplicationSummaryResponse toSummary(ApplicationDocument application, JobDocument job) {
        return new ApplicationSummaryResponse(application.getId(), status(application), application.getAppliedAt(),
                job.getId(), job.getTitle(), job.getCompany(), job.getLocation(), job.getSalary(), SafeExternalUrl.parse(job.getSourceUrl()).orElse(null));
    }

    private static ApplicationStatus status(ApplicationDocument document) {
        return document.getStatus() == null ? ApplicationStatus.APPLIED : document.getStatus();
    }
}
