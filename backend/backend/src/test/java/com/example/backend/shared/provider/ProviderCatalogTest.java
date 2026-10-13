package com.example.backend.shared.provider;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProviderCatalogTest {
    @Test
    void listsEveryProviderOnceInStableOrderWithKinds() {
        assertEquals(List.of("adzuna", "greenhouse", "lever", "ashby", "smartrecruiters", "workable"),
                ProviderCatalog.all().stream().map(ProviderCatalog.Provider::name).toList());
        assertEquals(Set.of("adzuna", "greenhouse", "lever", "ashby", "smartrecruiters", "workable"),
                ProviderCatalog.names());
        assertEquals(List.of("adzuna"), ProviderCatalog.ofKind(ProviderCatalog.Kind.SEARCH_API).stream()
                .map(ProviderCatalog.Provider::name).toList());
        assertEquals(List.of("greenhouse", "lever", "ashby", "smartrecruiters", "workable"), ProviderCatalog.ofKind(ProviderCatalog.Kind.ATS_BOARD).stream()
                .map(ProviderCatalog.Provider::name).toList());
    }

    @Test
    void lookupIsCaseInsensitiveAndRejectsUnknownOrBlankNames() {
        assertEquals("greenhouse", ProviderCatalog.find("GreenHouse").orElseThrow().name());
        assertTrue(ProviderCatalog.find(" greenhouse").isEmpty());
        assertTrue(ProviderCatalog.find("unknown").isEmpty());
        assertTrue(ProviderCatalog.find("").isEmpty());
        assertTrue(ProviderCatalog.find(null).isEmpty());
        assertTrue(ProviderCatalog.find("other").isEmpty());
        assertEquals("lever", ProviderCatalog.byRegistrySource("LEVER").orElseThrow().name());
        assertTrue(ProviderCatalog.byRegistrySource("lever").isEmpty());
    }

    @Test
    void preservesExistingSchedulesAndBaseUrls() {
        ProviderCatalog.Provider adzuna = ProviderCatalog.find("adzuna").orElseThrow();
        assertFalse(adzuna.employerScoped());
        assertEquals("adzuna.schedule-delay-ms", adzuna.scheduleProperty());
        assertEquals(43_200_000L, adzuna.defaultDelayMs());
        ProviderCatalog.Provider greenhouse = ProviderCatalog.find("greenhouse").orElseThrow();
        assertTrue(greenhouse.employerScoped());
        assertEquals("job-aggregation.scheduling.greenhouse-delay-ms", greenhouse.scheduleProperty());
        assertEquals(21_600_000L, greenhouse.defaultDelayMs());
        assertEquals("job-aggregation.providers.greenhouse-base-url", greenhouse.baseUrlProperty());
        assertEquals("https://boards-api.greenhouse.io/v1/boards", greenhouse.defaultBaseUrl());
        ProviderCatalog.Provider lever = ProviderCatalog.find("lever").orElseThrow();
        assertEquals("job-aggregation.scheduling.lever-delay-ms", lever.scheduleProperty());
        assertEquals("https://api.lever.co/v0/postings", lever.defaultBaseUrl());
        ProviderCatalog.Provider ashby = ProviderCatalog.find("ashby").orElseThrow();
        assertTrue(ashby.employerScoped());
        assertEquals("ASHBY", ashby.registrySource());
        assertEquals("job-aggregation.scheduling.ashby-delay-ms", ashby.scheduleProperty());
        assertEquals(21_600_000L, ashby.defaultDelayMs());
        assertEquals("job-aggregation.providers.ashby-base-url", ashby.baseUrlProperty());
        assertEquals("https://api.ashbyhq.com/posting-api/job-board", ashby.defaultBaseUrl());
        ProviderCatalog.Provider smartRecruiters = ProviderCatalog.find("SmartRecruiters").orElseThrow();
        assertEquals("SMARTRECRUITERS", smartRecruiters.registrySource());
        assertEquals("job-aggregation.scheduling.smartrecruiters-delay-ms", smartRecruiters.scheduleProperty());
        assertEquals("job-aggregation.providers.smartrecruiters-base-url", smartRecruiters.baseUrlProperty());
        assertEquals("https://api.smartrecruiters.com/v1/companies", smartRecruiters.defaultBaseUrl());
        ProviderCatalog.Provider workable = ProviderCatalog.find("workable").orElseThrow();
        assertEquals("WORKABLE", workable.registrySource());
        assertEquals("job-aggregation.scheduling.workable-delay-ms", workable.scheduleProperty());
        assertEquals("https://apply.workable.com/api/v1/widget/accounts", workable.defaultBaseUrl());
    }

    @Test
    void metricTagsAreCatalogNamesOrOtherNeverRawInput() {
        assertEquals("lever", ProviderCatalog.metricTag("LEVER"));
        assertEquals("other", ProviderCatalog.metricTag("untrusted-employer-name"));
        assertEquals("other", ProviderCatalog.metricTag("https://example.test/?key=secret"));
        assertEquals("other", ProviderCatalog.metricTag(null));
        assertEquals("other", ProviderCatalog.metricTag("lever "));
    }

    @Test
    void indexRejectsDuplicateInvalidAndIncompleteEntries() {
        ProviderCatalog.Provider ats = new ProviderCatalog.Provider("acme", ProviderCatalog.Kind.ATS_BOARD, "Acme",
                "ACME", "schedule", 1, "base-url", "https://example.test");
        assertEquals(1, ProviderCatalog.index(List.of(ats)).size());
        assertThrows(IllegalStateException.class, () -> ProviderCatalog.index(List.of(ats, ats)));
        assertThrows(IllegalStateException.class, () -> ProviderCatalog.index(List.of(ats,
                new ProviderCatalog.Provider("other2", ProviderCatalog.Kind.ATS_BOARD, "Other", "ACME",
                        "schedule", 1, "base-url", "https://example.test"))));
        for (String invalidName : new String[] {null, "Acme", "other", "a", "has-dash"}) {
            assertThrows(IllegalStateException.class, () -> ProviderCatalog.index(List.of(
                    new ProviderCatalog.Provider(invalidName, ProviderCatalog.Kind.SEARCH_API, "X", null,
                            "schedule", 1, null, null))));
        }
        assertThrows(IllegalStateException.class, () -> ProviderCatalog.index(List.of(
                new ProviderCatalog.Provider("acme", ProviderCatalog.Kind.ATS_BOARD, "Acme", null,
                        "schedule", 1, "base-url", "https://example.test"))));
        assertThrows(IllegalStateException.class, () -> ProviderCatalog.index(List.of(
                new ProviderCatalog.Provider("acme", ProviderCatalog.Kind.SEARCH_API, "Acme", null,
                        "schedule", 0, null, null))));
    }
}
