package com.example.backend.matching.application;

import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.matching.extraction.JobFeatureExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class JobFeatureService {
    private static final Logger log = LoggerFactory.getLogger(JobFeatureService.class);
    private final JobFeatureExtractor extractor;
    private final MatchingMetrics metrics;

    public JobFeatureService(JobFeatureExtractor extractor, MatchingMetrics metrics) {
        this.extractor = extractor;
        this.metrics = metrics;
    }

    public boolean prepare(JobDocument job) {
        if (job == null || !extractor.stale(job)) return false;
        try {
            job.setMatchFeatures(extractor.extract(job));
            metrics.featureExtraction("success");
            return true;
        } catch (RuntimeException failure) {
            metrics.featureExtraction("failure");
            log.warn("event=job_feature_extraction_failed");
            return false;
        }
    }
}
