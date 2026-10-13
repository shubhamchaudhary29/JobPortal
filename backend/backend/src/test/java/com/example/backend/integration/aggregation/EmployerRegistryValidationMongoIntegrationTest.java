package com.example.backend.integration.aggregation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.example.backend.integration.jobs.ExternalJob;
import com.example.backend.integration.jobs.JobSource;
import com.example.backend.integration.reliability.ProviderFailureException;
import com.example.backend.job.infrastructure.JobDocument;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/** Single-entry validation classifies the provider's answer, audits it, and never stores jobs. */
@SpringBootTest
class EmployerRegistryValidationMongoIntegrationTest {
    @Autowired MongoTemplate mongo;

    @BeforeEach
    @AfterEach
    void clear() {
        mongo.remove(new Query(), EmployerRegistryDocument.class);
        mongo.remove(new Query(), EmployerRegistryAuditDocument.class);
    }

    @Test
    void classifiesActiveEmptyInvalidMalformedAndUnreachableBoards() {
        JobSource lever = mock(JobSource.class);
        when(lever.sourceName()).thenReturn("lever");
        EmployerRegistryAdminService service = new EmployerRegistryAdminService(mongo, List.of(lever), Clock.systemUTC());
        String id = service.create("lever", "cred", "CRED", true, "admin@example.com").id();
        long jobsBefore = mongo.count(new Query(), JobDocument.class);
        ExternalJob job = new ExternalJob("1", "Engineer", null, "CRED", "Bengaluru", null, null, null,
                "https://jobs.lever.co/cred/1", null, null, "fingerprint");

        when(lever.fetchWithMetadata(any(), any())).thenReturn(new JobSource.FetchResult(List.of(job, job), 0));
        EmployerRegistryAdminService.ValidationView active = service.validate(id, "admin@example.com");
        when(lever.fetchWithMetadata(any(), any())).thenReturn(new JobSource.FetchResult(List.of(), 0));
        EmployerRegistryAdminService.ValidationView empty = service.validate(id, "admin@example.com");
        assertAll(
                () -> assertEquals(EmployerRegistryAdminService.Classification.ACTIVE, active.classification()),
                () -> assertEquals(2, active.count()),
                () -> assertEquals(EmployerRegistryAdminService.Classification.EMPTY, empty.classification()),
                () -> assertEquals(classify(service, lever, id, ProviderFailureException.Kind.CLIENT_ERROR),
                        EmployerRegistryAdminService.Classification.INVALID),
                () -> assertEquals(classify(service, lever, id, ProviderFailureException.Kind.MALFORMED_RESPONSE),
                        EmployerRegistryAdminService.Classification.MALFORMED),
                () -> assertEquals(classify(service, lever, id, ProviderFailureException.Kind.PAYLOAD_LIMIT),
                        EmployerRegistryAdminService.Classification.MALFORMED),
                () -> assertEquals(classify(service, lever, id, ProviderFailureException.Kind.TIMEOUT),
                        EmployerRegistryAdminService.Classification.UNREACHABLE),
                () -> assertEquals(classify(service, lever, id, ProviderFailureException.Kind.CIRCUIT_OPEN),
                        EmployerRegistryAdminService.Classification.UNREACHABLE),
                () -> assertEquals(jobsBefore, mongo.count(new Query(), JobDocument.class)),
                () -> assertEquals(8, service.audit(id, 0, 20).content().size()));
        verify(lever, atLeastOnce()).fetchWithMetadata(argThat(request -> "cred".equals(request.boardId())
                && "CRED".equals(request.company())), any());
    }

    private EmployerRegistryAdminService.Classification classify(EmployerRegistryAdminService service, JobSource source,
                                                                 String id, ProviderFailureException.Kind kind) {
        doThrow(new ProviderFailureException("lever", kind, false, null, null)).when(source).fetchWithMetadata(any(), any());
        return service.validate(id, "admin@example.com").classification();
    }
}
