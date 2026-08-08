package com.example.backend.job;

import com.example.backend.job.api.dto.CreateJobRequest;
import com.example.backend.job.api.dto.JobResponse;
import com.example.backend.job.api.dto.UpdateJobRequest;
import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.shared.validation.SafeExternalUrl;

public final class JobMapper {
    private JobMapper() { }

    public static JobDocument fromCreate(CreateJobRequest request) {
        JobDocument document = new JobDocument();
        apply(request.title(), request.description(), request.location(), request.company(), request.salary(),
                request.experience(), document);
        return document;
    }

    public static void applyUpdate(UpdateJobRequest request, JobDocument document) {
        apply(request.title(), request.description(), request.location(), request.company(), request.salary(),
                request.experience(), document);
    }

    public static JobResponse toResponse(JobDocument document) {
        return new JobResponse(document.getId(), document.getTitle(), document.getDescription(), document.getLocation(),
                document.getCompany(), document.getSalary(), document.getExperience(), document.getCreatedAt(),
                SafeExternalUrl.parse(document.getSourceUrl()).orElse(null), document.getSource());
    }

    private static void apply(String title, String description, String location, String company, double salary,
                              double experience, JobDocument document) {
        document.setTitle(title.trim());
        document.setDescription(description.trim());
        document.setLocation(location.trim());
        document.setCompany(company.trim());
        document.setSalary(salary);
        document.setExperience(experience);
    }
}
