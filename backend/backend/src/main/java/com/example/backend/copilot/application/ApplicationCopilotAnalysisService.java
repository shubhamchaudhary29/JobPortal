package com.example.backend.copilot.application;

import com.example.backend.copilot.domain.CopilotModels.KeywordAnalysis;
import com.example.backend.copilot.domain.CopilotModels.ReadinessResult;
import com.example.backend.copilot.domain.CopilotModels.TailoringPlan;
import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.matching.application.JobMatchEngine;
import com.example.backend.matching.domain.JobMatchResult;
import org.springframework.stereotype.Service;

@Service
public class ApplicationCopilotAnalysisService {
    private final CopilotAccessService access;
    private final CandidateEvidenceCatalog evidence;
    private final JobMatchEngine matching;
    private final ApplicationReadinessEngine readiness;
    private final TailoringPlanEngine plans;
    private final CopilotMetrics metrics;

    public ApplicationCopilotAnalysisService(CopilotAccessService access, CandidateEvidenceCatalog evidence,
                                             JobMatchEngine matching, ApplicationReadinessEngine readiness,
                                             TailoringPlanEngine plans, CopilotMetrics metrics) {
        this.access = access;
        this.evidence = evidence;
        this.matching = matching;
        this.readiness = readiness;
        this.plans = plans;
        this.metrics = metrics;
    }

    public AnalysisBundle analyze(String jobId) {
        var candidate = access.candidate();
        JobDocument job = access.job(jobId);
        JobMatchResult match = matching.calculate(candidate.profile(), job);
        CandidateEvidenceCatalog.Analysis catalog = evidence.analyze(candidate.profile(), job.getMatchFeatures());
        ReadinessResult result = readiness.calculate(candidate.profile(), job, match, catalog.keywords(), access.active(job));
        TailoringPlan plan = plans.create(candidate.profile(), catalog.keywords());
        metrics.readinessCalculated();
        return new AnalysisBundle(candidate, job, match, catalog.keywords(), result, plan);
    }

    public ReadinessResult readiness(String jobId) { return analyze(jobId).readiness(); }
    public TailoringPlan tailoringPlan(String jobId) { return analyze(jobId).tailoringPlan(); }

    public record AnalysisBundle(CopilotAccessService.CandidateContext candidate, JobDocument job,
                                 JobMatchResult match, KeywordAnalysis keywords,
                                 ReadinessResult readiness, TailoringPlan tailoringPlan) { }
}
