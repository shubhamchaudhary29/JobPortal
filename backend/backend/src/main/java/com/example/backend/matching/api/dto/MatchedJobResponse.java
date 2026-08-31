package com.example.backend.matching.api.dto;

import com.example.backend.job.api.dto.JobResponse;

public record MatchedJobResponse(JobResponse job, JobMatchResponse match) { }
