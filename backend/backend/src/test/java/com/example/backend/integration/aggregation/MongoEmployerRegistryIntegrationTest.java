package com.example.backend.integration.aggregation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.example.backend.integration.adzuna.AdzunaJobStore;
import com.example.backend.integration.adzuna.UpsertOutcome;
import com.example.backend.integration.jobs.ExternalJob;
import com.example.backend.integration.jobs.JobSource;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@SpringBootTest
class MongoEmployerRegistryIntegrationTest {
    @Autowired MongoTemplate mongo;
    @Autowired EmployerRegistry liveRegistry;

    private final EmployerRegistryProperties seed = new EmployerRegistryProperties(List.of(
            new EmployerRegistryProperties.Employer("Razorpay", EmployerRegistryProperties.Source.GREENHOUSE,
                    "razorpaysoftwareprivatelimited", true),
            new EmployerRegistryProperties.Employer("CRED", EmployerRegistryProperties.Source.LEVER, "cred", true),
            new EmployerRegistryProperties.Employer("Old Co", EmployerRegistryProperties.Source.LEVER, "oldco", false)));

    @BeforeEach
    @AfterEach
    void clear() { mongo.remove(new Query(), EmployerRegistryDocument.class); }

    @Test
    void importIsIdempotentAndNeverOverwritesAdminEdits() {
        EmployerRegistryImporter importer = importer(seed);

        EmployerRegistryImporter.Result first = importer.importSeed();
        mongo.updateFirst(Query.query(Criteria.where("boardKey").is("cred")),
                new Update().set("enabled", false).set("disabledReason", "paused by admin").set("updatedBy", "admin@example.com"),
                EmployerRegistryDocument.class);
        EmployerRegistryImporter.Result second = importer.importSeed();

        EmployerRegistryDocument cred = mongo.findOne(Query.query(Criteria.where("boardKey").is("cred")), EmployerRegistryDocument.class);
        assertAll(
                () -> assertEquals(new EmployerRegistryImporter.Result(3, 0, 0), first),
                () -> assertEquals(new EmployerRegistryImporter.Result(0, 3, 0), second),
                () -> assertEquals(3, mongo.count(new Query(), EmployerRegistryDocument.class)),
                () -> assertFalse(cred.isEnabled()),
                () -> assertEquals("paused by admin", cred.getDisabledReason()),
                () -> assertEquals(EmployerRegistryDocument.Origin.PROPERTIES, cred.getOrigin()),
                () -> assertEquals("properties-import", cred.getCreatedBy()));
    }

    @Test
    void boardsAreUniquePerProviderAndEnabledCompaniesAreUnique() {
        importer(seed).importSeed();

        assertThrows(DuplicateKeyException.class, () -> mongo.insert(document("Another", EmployerRegistryProperties.Source.LEVER, "CRED", false)));
        assertThrows(DuplicateKeyException.class, () -> mongo.insert(document(" razorpay ", EmployerRegistryProperties.Source.ASHBY, "razorpay", true)));
        mongo.insert(document("Razorpay", EmployerRegistryProperties.Source.ASHBY, "razorpay", false));
        mongo.insert(document("CRED", EmployerRegistryProperties.Source.GREENHOUSE, "cred", false));

        EmployerRegistryImporter.Result conflicting = importer(new EmployerRegistryProperties(List.of(
                new EmployerRegistryProperties.Employer("CRED", EmployerRegistryProperties.Source.ASHBY, "cred-ashby", true))))
                .importSeed();
        assertEquals(new EmployerRegistryImporter.Result(0, 0, 1), conflicting);
        assertEquals(5, mongo.count(new Query(), EmployerRegistryDocument.class));
    }

    @Test
    void emptyCollectionFallsBackToTheSeedAndMongoThenServesOnlyEnabledBoardsInStableOrder() {
        MongoEmployerRegistry registry = new MongoEmployerRegistry(mongo, seed, 2_000);
        assertEquals(seed.employers(), registry.employers());

        importer(seed).importSeed();
        mongo.insert(document("Sarvam AI", EmployerRegistryProperties.Source.ASHBY, "sarvam", true));

        // Stable order: provider (enum name), then board key.
        assertEquals(List.of("sarvam", "razorpaysoftwareprivatelimited", "cred"),
                registry.employers().stream().map(EmployerRegistryProperties.Employer::boardId).toList());
        assertEquals(List.of("sarvam", "razorpaysoftwareprivatelimited"), new MongoEmployerRegistry(mongo, seed, 2).employers()
                .stream().map(EmployerRegistryProperties.Employer::boardId).toList());
        assertThrows(IllegalArgumentException.class, () -> new MongoEmployerRegistry(mongo, seed, 0));
        assertSame(MongoEmployerRegistry.class, liveRegistry.getClass());
    }

    @Test
    void ingestionAndEmployerScopedValidationReadAMongoOnlyBoard() {
        mongo.insert(document("Sarvam AI", EmployerRegistryProperties.Source.ASHBY, "sarvam", true));
        JobSource ashby = mock(JobSource.class);
        when(ashby.sourceName()).thenReturn("ashby");
        when(ashby.fetchWithMetadata(any(), any())).thenReturn(new JobSource.FetchResult(List.of(
                new ExternalJob("1", "Engineer", null, "Sarvam AI", "Bengaluru", null, null, null,
                        "https://jobs.ashbyhq.com/sarvam/1", null, null, "fingerprint")), 0));
        AdzunaJobStore store = mock(AdzunaJobStore.class);
        when(store.upsert(any(), any(), any())).thenReturn(UpsertOutcome.INSERTED);
        EmployerIngestionService ingestion = new EmployerIngestionService(List.of(ashby), store,
                new MongoEmployerRegistry(mongo, seed, 2_000), mock(ImportedJobLifecycleService.class));

        EmployerIngestionService.Result result = ingestion.sync(EmployerRegistryProperties.Source.ASHBY, "SARVAM", () -> true);

        assertEquals(1, result.inserted());
        verify(ashby).fetchWithMetadata(argThat(request -> "sarvam".equals(request.boardId())), any());
        assertThrows(com.example.backend.shared.error.BadRequestException.class,
                () -> ingestion.requireEnabledEmployer(EmployerRegistryProperties.Source.LEVER, "cred"));
    }

    private EmployerRegistryImporter importer(EmployerRegistryProperties properties) {
        return new EmployerRegistryImporter(mongo, properties, Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"), java.time.ZoneOffset.UTC));
    }

    private static EmployerRegistryDocument document(String company, EmployerRegistryProperties.Source source, String board,
                                                     boolean enabled) {
        EmployerRegistryDocument document = new EmployerRegistryDocument();
        document.setCompany(company.trim());
        document.setCompanyKey(EmployerRegistryDocument.companyKey(company));
        document.setSource(source);
        document.setBoardId(board);
        document.setBoardKey(EmployerRegistryDocument.boardKey(board));
        document.setEnabled(enabled);
        document.setOrigin(EmployerRegistryDocument.Origin.ADMIN);
        return document;
    }
}
