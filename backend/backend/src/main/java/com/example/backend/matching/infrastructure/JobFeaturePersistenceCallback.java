package com.example.backend.matching.infrastructure;

import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.matching.application.JobFeatureService;
import org.springframework.data.mongodb.core.mapping.event.BeforeConvertCallback;
import org.springframework.stereotype.Component;

@Component
public class JobFeaturePersistenceCallback implements BeforeConvertCallback<JobDocument> {
    private final JobFeatureService features;
    public JobFeaturePersistenceCallback(JobFeatureService features) { this.features = features; }

    @Override
    public JobDocument onBeforeConvert(JobDocument entity, String collection) {
        features.prepare(entity);
        return entity;
    }
}
