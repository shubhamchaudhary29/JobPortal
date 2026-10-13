package com.example.backend.shared.provider;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The single list of supported job providers. Adding a provider requires a {@code JobSource} bean, an entry
 * here, and configuration. Lookups, history filters, metric tags, schedules, base URLs, and the ADMIN provider
 * list all derive from this catalog, so input can never introduce a provider that is not listed.
 */
public final class ProviderCatalog {
    public static final String OTHER = "other";

    private static final List<Provider> PROVIDERS = List.of(
            new Provider("adzuna", Kind.SEARCH_API, "Adzuna", null,
                    "adzuna.schedule-delay-ms", 43_200_000L, null, null),
            new Provider("greenhouse", Kind.ATS_BOARD, "Greenhouse", "GREENHOUSE",
                    "job-aggregation.scheduling.greenhouse-delay-ms", 21_600_000L,
                    "job-aggregation.providers.greenhouse-base-url", "https://boards-api.greenhouse.io/v1/boards"),
            new Provider("lever", Kind.ATS_BOARD, "Lever", "LEVER",
                    "job-aggregation.scheduling.lever-delay-ms", 21_600_000L,
                    "job-aggregation.providers.lever-base-url", "https://api.lever.co/v0/postings"),
            new Provider("ashby", Kind.ATS_BOARD, "Ashby", "ASHBY",
                    "job-aggregation.scheduling.ashby-delay-ms", 21_600_000L,
                    "job-aggregation.providers.ashby-base-url", "https://api.ashbyhq.com/posting-api/job-board"),
            new Provider("smartrecruiters", Kind.ATS_BOARD, "SmartRecruiters", "SMARTRECRUITERS",
                    "job-aggregation.scheduling.smartrecruiters-delay-ms", 21_600_000L,
                    "job-aggregation.providers.smartrecruiters-base-url", "https://api.smartrecruiters.com/v1/companies"),
            new Provider("workable", Kind.ATS_BOARD, "Workable", "WORKABLE",
                    "job-aggregation.scheduling.workable-delay-ms", 21_600_000L,
                    "job-aggregation.providers.workable-base-url", "https://apply.workable.com/api/v1/widget/accounts"));

    private static final Map<String, Provider> BY_NAME = index(PROVIDERS);

    private ProviderCatalog() { }

    public static List<Provider> all() { return PROVIDERS; }

    public static List<Provider> ofKind(Kind kind) {
        return PROVIDERS.stream().filter(provider -> provider.kind() == kind).toList();
    }

    public static Set<String> names() { return BY_NAME.keySet(); }

    /** Case-insensitive exact lookup (no trimming); null and unknown names are absent. */
    public static Optional<Provider> find(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(BY_NAME.get(name.toLowerCase(Locale.ROOT)));
    }

    public static Optional<Provider> byRegistrySource(String registrySource) {
        if (registrySource == null) return Optional.empty();
        return PROVIDERS.stream().filter(provider -> registrySource.equals(provider.registrySource())).findFirst();
    }

    /** Fixed, low-cardinality metric tag: a catalog name or {@value #OTHER}, never raw input. */
    public static String metricTag(String name) {
        return find(name).map(Provider::name).orElse(OTHER);
    }

    static Map<String, Provider> index(List<Provider> providers) {
        Map<String, Provider> byName = new LinkedHashMap<>();
        Set<String> registrySources = new HashSet<>();
        for (Provider provider : providers) {
            if (provider.name() == null || !provider.name().matches("[a-z][a-z0-9]{1,29}")
                    || OTHER.equals(provider.name())) {
                throw new IllegalStateException("Invalid provider name: " + provider.name());
            }
            if (provider.kind() == null || provider.displayName() == null || provider.displayName().isBlank()
                    || provider.scheduleProperty() == null || provider.defaultDelayMs() < 1) {
                throw new IllegalStateException("Incomplete provider catalog entry: " + provider.name());
            }
            if (provider.kind() == Kind.ATS_BOARD && (provider.registrySource() == null
                    || provider.baseUrlProperty() == null || provider.defaultBaseUrl() == null)) {
                throw new IllegalStateException("ATS provider requires a registry source and base URL: "
                        + provider.name());
            }
            if (byName.put(provider.name(), provider) != null) {
                throw new IllegalStateException("Duplicate provider: " + provider.name());
            }
            if (provider.registrySource() != null && !registrySources.add(provider.registrySource())) {
                throw new IllegalStateException("Duplicate registry source: " + provider.registrySource());
            }
        }
        return Collections.unmodifiableMap(byName);
    }

    public enum Kind { ATS_BOARD, SEARCH_API }

    /**
     * @param registrySource employer-registry enum constant for ATS boards; {@code null} for search APIs
     * @param scheduleProperty fixed-delay property read by the scheduler
     * @param baseUrlProperty overridable API base URL property for providers on the shared HTTP client
     */
    public record Provider(String name, Kind kind, String displayName, String registrySource,
                           String scheduleProperty, long defaultDelayMs,
                           String baseUrlProperty, String defaultBaseUrl) {
        public boolean employerScoped() { return kind == Kind.ATS_BOARD; }
    }
}
