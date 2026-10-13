package com.example.backend.integration.aggregation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class FingerprintMigrationRunnerTest {
    @Test
    void exitCodesDistinguishCleanReviewAndInvalidModes(CapturedOutput output) throws Exception {
        FingerprintMigrationService service = mock(FingerprintMigrationService.class);
        FingerprintMigrationService.Report clean = new FingerprintMigrationService.Report(false);
        clean.scanned = 3;
        FingerprintMigrationService.Report review = new FingerprintMigrationService.Report(true);
        review.collisions = 2;
        when(service.run(false)).thenReturn(clean);
        when(service.run(true)).thenReturn(review);

        FingerprintMigrationRunner dryRun = new FingerprintMigrationRunner(service, "dry-run", false, null);
        dryRun.run(null);
        FingerprintMigrationRunner apply = new FingerprintMigrationRunner(service, "apply", false, null);
        apply.run(null);
        FingerprintMigrationRunner invalid = new FingerprintMigrationRunner(service, "delete-everything", false, null);
        invalid.run(null);

        assertAll(
                () -> assertEquals(0, dryRun.getExitCode()),
                () -> assertEquals(2, apply.getExitCode()),
                () -> assertEquals(3, invalid.getExitCode()),
                () -> assertTrue(output.getOut().contains("FINGERPRINT_MIGRATION_REPORT {\"mode\":\"dry-run\"")
                        || output.getOut().contains("\"mode\":\"dry-run\"")),
                () -> assertTrue(output.getOut().contains("\"collisions\":2")),
                () -> assertTrue(output.getOut().contains("mode must be dry-run or apply")));
        verify(service).run(false);
        verify(service).run(true);
        verifyNoMoreInteractions(service);
    }
}
