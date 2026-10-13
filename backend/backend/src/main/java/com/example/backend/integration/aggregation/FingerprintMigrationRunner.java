package com.example.backend.integration.aggregation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * One-shot command: {@code --job-aggregation.fingerprint-migration.mode=dry-run|apply}. It prints one JSON
 * report line and exits {@code 0} when nothing needs review, {@code 2} when collisions or skipped jobs need an
 * operator, and {@code 3} for an invalid mode. See {@code scripts/migrate-fingerprints.sh}.
 */
@Component
@ConditionalOnProperty(prefix = "job-aggregation.fingerprint-migration", name = "mode")
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.LOWEST_PRECEDENCE)
class FingerprintMigrationRunner implements ApplicationRunner, ExitCodeGenerator {
    private final FingerprintMigrationService migration;
    private final String mode;
    private final ConfigurableApplicationContext context;
    private final boolean exitWhenDone;
    private int exitCode = 3;

    FingerprintMigrationRunner(FingerprintMigrationService migration,
            @org.springframework.beans.factory.annotation.Value("${job-aggregation.fingerprint-migration.mode}") String mode,
            @org.springframework.beans.factory.annotation.Value("${job-aggregation.fingerprint-migration.exit:true}") boolean exitWhenDone,
            ConfigurableApplicationContext context) {
        this.migration = migration;
        this.mode = mode;
        this.exitWhenDone = exitWhenDone;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try {
            execute();
        } finally {
            // Lease and scheduling executors are non-daemon, so a one-shot command must exit explicitly.
            if (exitWhenDone) System.exit(SpringApplication.exit(context, this));
        }
    }

    void execute() throws Exception {
        if (!"dry-run".equals(mode) && !"apply".equals(mode)) {
            System.out.println("FINGERPRINT_MIGRATION_REPORT {\"error\":\"mode must be dry-run or apply\"}");
            return;
        }
        FingerprintMigrationService.Report report = migration.run("apply".equals(mode));
        Map<String, Object> output = new LinkedHashMap<>(report.summary());
        output.put("plannedIds", report.plannedIds);
        output.put("collisionIds", report.collisionIds);
        output.put("skippedApplicationIds", report.skippedApplicationIds);
        output.put("unfingerprintableIds", report.unfingerprintableIds);
        System.out.println("FINGERPRINT_MIGRATION_REPORT " + new ObjectMapper().writeValueAsString(output));
        exitCode = report.needsReview() ? 2 : 0;
    }

    @Override
    public int getExitCode() { return exitCode; }
}
