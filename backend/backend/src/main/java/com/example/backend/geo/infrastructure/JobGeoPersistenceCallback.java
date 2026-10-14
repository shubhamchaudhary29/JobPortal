package com.example.backend.geo.infrastructure;

import com.example.backend.geo.application.GeoResolver;
import com.example.backend.job.infrastructure.JobDocument;
import org.springframework.data.mongodb.core.mapping.event.BeforeConvertCallback;
import org.springframework.stereotype.Component;

/** Resolves top-level {@code geo} for jobs without source listings; imported listings resolve in the store. */
@Component
public class JobGeoPersistenceCallback implements BeforeConvertCallback<JobDocument> {
    private final GeoResolver resolver;
    public JobGeoPersistenceCallback(GeoResolver resolver) { this.resolver = resolver; }

    @Override
    public JobDocument onBeforeConvert(JobDocument entity, String collection) {
        if (entity.getSourceListings() == null || entity.getSourceListings().isEmpty()) {
            entity.setGeo(resolver.resolve(entity.getLocation(), null));
        }
        return entity;
    }
}
