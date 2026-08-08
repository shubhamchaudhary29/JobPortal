package com.example.backend.integration.adzuna;

import com.example.backend.job.infrastructure.JobDocument;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdzunaJobStoreTest {
    @Test
    void usesOneAtomicSourceAndExternalIdUpsertForReplayedProviderJobs() {
        MongoTemplate mongo = mock(MongoTemplate.class); AdzunaJobStore store = new AdzunaJobStore(mongo); JobDocument job = new JobDocument();
        job.setSource("adzuna"); job.setExternalId("provider-1"); job.setTitle("Engineer"); job.setDescription("x"); job.setCompany("c"); job.setLocation("l");
        store.upsert(job, LocalDateTime.of(2026, 1, 1, 0, 0)); store.upsert(job, LocalDateTime.of(2026, 1, 1, 0, 1));
        verify(mongo, times(2)).findAndModify(argThat(q -> q.getQueryObject().toJson().contains("provider-1") && q.getQueryObject().toJson().contains("adzuna")), any(Update.class), argThat(options -> options.isUpsert()), eq(JobDocument.class));
    }
}
