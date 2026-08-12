package com.example.backend.integration.aggregation;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class ExternalJobNormalizerTest {
 @Test void sanitizesHtmlParsesDatesAndRejectsUnsafeUrls(){var job=ExternalJobNormalizer.normalize("1","  Engineer ","<script>x</script><p>Hello &amp; welcome</p>"," Acme "," Pune ","Full Time","https://example.test/jobs/1","2026-01-02T03:04:05Z");assertEquals("Hello & welcome",job.description());assertEquals("Engineer",job.title());assertNotNull(job.publishedAt());assertNotNull(job.fingerprint());assertNull(ExternalJobNormalizer.normalize("1","x","x","c","l",null,"javascript:bad",null).applicationUrl());}
 @Test void acceptsEpochDerivedIsoAndMissingOptionalFields(){var job=ExternalJobNormalizer.normalize("1","x",null,"c",null,null,"https://example.test/a",java.time.Instant.ofEpochMilli(0).toString());assertEquals(java.time.LocalDateTime.of(1970,1,1,0,0),job.publishedAt());assertNull(job.description());}
}
