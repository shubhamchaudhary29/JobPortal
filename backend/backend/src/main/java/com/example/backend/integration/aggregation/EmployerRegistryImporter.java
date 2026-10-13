package com.example.backend.integration.aggregation;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Seeds {@code employer_registry} from the properties registry at startup. Only boards missing from Mongo are
 * inserted; existing entries (including ADMIN edits) are never changed, and a properties entry that would break
 * a uniqueness rule is skipped and logged. Running it again inserts nothing.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
@ConditionalOnProperty(name = "job-aggregation.registry.import-on-startup", havingValue = "true", matchIfMissing = true)
public class EmployerRegistryImporter implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(EmployerRegistryImporter.class);
    private final MongoTemplate mongo;
    private final EmployerRegistryProperties seed;
    private final Clock clock;

    public EmployerRegistryImporter(MongoTemplate mongo, EmployerRegistryProperties seed, Clock clock) {
        this.mongo = mongo;
        this.seed = seed;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) { importSeed(); }

    public Result importSeed() {
        int inserted = 0, existing = 0, skipped = 0;
        for (EmployerRegistryProperties.Employer employer : seed.employers()) {
            String boardKey = EmployerRegistryDocument.boardKey(employer.boardId());
            boolean present = mongo.exists(Query.query(Criteria.where("source").is(employer.source())
                    .and("boardKey").is(boardKey)), EmployerRegistryDocument.class);
            if (present) {
                existing++;
                continue;
            }
            EmployerRegistryDocument document = new EmployerRegistryDocument();
            document.setSource(employer.source());
            document.setBoardId(employer.boardId().trim());
            document.setBoardKey(boardKey);
            document.setCompany(employer.company().trim());
            document.setCompanyKey(EmployerRegistryDocument.companyKey(employer.company()));
            document.setEnabled(employer.enabled());
            document.setOrigin(EmployerRegistryDocument.Origin.PROPERTIES);
            document.setCreatedAt(clock.instant());
            document.setCreatedBy("properties-import");
            document.setUpdatedAt(document.getCreatedAt());
            document.setUpdatedBy("properties-import");
            try {
                mongo.insert(document);
                inserted++;
            } catch (DuplicateKeyException conflict) {
                skipped++;
                log.warn("event=employer_registry_import_skipped source={} board={} reason=duplicate",
                        employer.source(), boardKey);
            }
        }
        log.info("event=employer_registry_import inserted={} existing={} skipped={}", inserted, existing, skipped);
        return new Result(inserted, existing, skipped);
    }

    public record Result(int inserted, int existing, int skipped) { }
}
