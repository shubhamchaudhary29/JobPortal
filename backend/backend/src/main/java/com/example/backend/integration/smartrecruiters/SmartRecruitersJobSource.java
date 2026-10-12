package com.example.backend.integration.smartrecruiters;

import com.example.backend.integration.aggregation.ExternalJobNormalizer;
import com.example.backend.integration.jobs.ExternalJob;
import com.example.backend.integration.jobs.JobFetchRequest;
import com.example.backend.integration.jobs.JobSource;
import com.example.backend.integration.reliability.ProviderCircuitBreaker;
import com.example.backend.integration.reliability.ProviderFailureException;
import com.example.backend.integration.reliability.ProviderHttpClient;
import com.example.backend.integration.reliability.ProviderReliabilityProperties;
import com.example.backend.integration.reliability.ProviderRequestLimiter;
import com.example.backend.integration.reliability.ProviderRetryExecutor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * SmartRecruiters public Posting API. The posting list is paged with offset/limit and bounded by the provider
 * item limit and a page cap. List items carry no description or public link, so each posting's detail is read
 * once per release and cached; at most {@code max-detail-requests} details are fetched per board run.
 * Postings whose detail is still pending are not emitted and the result is marked incomplete, as is a capped
 * or partially failed listing, so lifecycle missing state never advances on partial coverage.
 */
@Component("smartRecruitersJobSource")
public class SmartRecruitersJobSource implements JobSource {
    private final ProviderHttpClient http;
    private final ProviderRetryExecutor retries;
    private final ProviderReliabilityProperties properties;
    private final ProviderCircuitBreaker circuit;
    private final ProviderRequestLimiter limiter;
    private final SmartRecruitersProperties bounds;
    private final SmartRecruitersDetailCache details;

    public SmartRecruitersJobSource(ProviderHttpClient http, ProviderRetryExecutor retries,
                                    ProviderReliabilityProperties properties, ProviderCircuitBreaker circuit,
                                    ProviderRequestLimiter limiter, SmartRecruitersProperties bounds,
                                    SmartRecruitersDetailCache details) {
        this.http = http;
        this.retries = retries;
        this.properties = properties;
        this.circuit = circuit;
        this.limiter = limiter;
        this.bounds = bounds;
        this.details = details;
    }

    public String sourceName() { return "smartrecruiters"; }
    public List<ExternalJob> fetch(JobFetchRequest request) { return fetchWithMetadata(request).jobs(); }
    public FetchResult fetchWithMetadata(JobFetchRequest request) {
        return fetchWithMetadata(request, () -> true);
    }

    @Override
    public FetchResult fetchWithMetadata(JobFetchRequest request, BooleanSupplier requestValid) {
        String board = request.boardId();
        int retryCount = 0;
        boolean complete = true;
        List<SmartRecruitersPostingDto> postings = new ArrayList<>();
        int offset = 0;
        for (int page = 0; ; page++) {
            if (page >= bounds.maxPages()) {
                complete = false;
                break;
            }
            String url = UriComponentsBuilder.fromUriString(properties.baseUrl(sourceName()))
                    .pathSegment(board, "postings").queryParam("offset", offset)
                    .queryParam("limit", bounds.pageSize()).build().toUriString();
            ProviderRetryExecutor.Result<SmartRecruitersPostingPage> result;
            try {
                result = request(board, requestValid, () -> http.get(sourceName(), url, SmartRecruitersPostingPage.class));
            } catch (ProviderFailureException failure) {
                if (page == 0 || stopsRun(failure)) throw failure;
                complete = false;
                break;
            }
            retryCount += result.retries();
            SmartRecruitersPostingPage body = result.value();
            if (body == null || body.content() == null) {
                if (page == 0) throw failure(ProviderFailureException.Kind.MALFORMED_RESPONSE);
                complete = false;
                break;
            }
            postings.addAll(body.content());
            if (postings.size() > properties.maxItems()) {
                postings = new ArrayList<>(postings.subList(0, properties.maxItems()));
                complete = false;
                break;
            }
            offset += body.content().size();
            boolean lastPage = body.content().size() < bounds.pageSize()
                    || (body.totalFound() != null && offset >= body.totalFound());
            if (body.content().isEmpty() || lastPage) break;
        }

        List<ExternalJob> output = new ArrayList<>();
        int rejected = 0;
        int detailRequests = 0;
        boolean detailsAvailable = true;
        for (SmartRecruitersPostingDto posting : postings) {
            if (posting == null || posting.id() == null || !posting.id().matches("[A-Za-z0-9_-]{1,64}")) {
                rejected++;
                continue;
            }
            Optional<SmartRecruitersDetailCache.Detail> detail = details.find(board, posting.id(), posting.releasedDate());
            if (detail.isEmpty()) {
                if (!detailsAvailable || detailRequests >= bounds.maxDetailRequests()) {
                    complete = false;
                    continue;
                }
                detailRequests++;
                try {
                    ProviderRetryExecutor.Result<SmartRecruitersPostingDetailDto> fetched = fetchDetail(board, posting.id(), requestValid);
                    retryCount += fetched.retries();
                    if (fetched.value() == null) {
                        rejected++;
                        continue;
                    }
                    SmartRecruitersDetailCache.Detail mapped = detail(fetched.value());
                    details.save(board, posting.id(), posting.releasedDate(), mapped);
                    detail = Optional.of(mapped);
                } catch (ProviderFailureException failure) {
                    if (stopsRun(failure)) throw failure;
                    if (failure.kind() == ProviderFailureException.Kind.CIRCUIT_OPEN) detailsAvailable = false;
                    rejected++;
                    continue;
                }
            }
            try {
                SmartRecruitersDetailCache.Detail found = detail.get();
                output.add(ExternalJobNormalizer.normalize(posting.id(), posting.name(), found.description(),
                        request.company(), location(posting.location()),
                        posting.typeOfEmployment() == null ? null : posting.typeOfEmployment().label(),
                        found.postingUrl() != null ? found.postingUrl() : found.applyUrl(), posting.releasedDate()));
            } catch (RuntimeException malformedItem) {
                rejected++;
            }
        }
        return new FetchResult(output, retryCount, rejected, complete);
    }

    private ProviderRetryExecutor.Result<SmartRecruitersPostingDetailDto> fetchDetail(
            String board, String postingId, BooleanSupplier requestValid) {
        String url = UriComponentsBuilder.fromUriString(properties.baseUrl(sourceName()))
                .pathSegment(board, "postings", postingId).build().toUriString();
        return request(board, requestValid, () -> http.get(sourceName(), url, SmartRecruitersPostingDetailDto.class));
    }

    private <T> ProviderRetryExecutor.Result<T> request(String board, BooleanSupplier requestValid, Supplier<T> call) {
        return retries.execute(requestValid, () -> circuit.execute(sourceName(), board, () -> {
            limiter.acquire(sourceName(), board, requestValid);
            ensureValid(requestValid);
            return call.get();
        }));
    }

    static SmartRecruitersDetailCache.Detail detail(SmartRecruitersPostingDetailDto dto) {
        StringBuilder description = new StringBuilder();
        if (dto.jobAd() != null && dto.jobAd().sections() != null) {
            SmartRecruitersSectionsDto sections = dto.jobAd().sections();
            for (SmartRecruitersSectionDto section : new SmartRecruitersSectionDto[] {
                    sections.jobDescription(), sections.qualifications(), sections.additionalInformation()}) {
                if (section == null || section.text() == null || section.text().isBlank()) continue;
                if (section.title() != null) description.append("<h3>").append(section.title()).append("</h3>");
                description.append(section.text());
            }
        }
        return new SmartRecruitersDetailCache.Detail(description.isEmpty() ? null : description.toString(),
                dto.postingUrl(), dto.applyUrl());
    }

    static String location(SmartRecruitersLocationDto location) {
        if (location == null) return null;
        String country = country(location.country());
        if (Boolean.TRUE.equals(location.remote())) return country == null ? "Remote" : "Remote - " + country;
        List<String> parts = new ArrayList<>();
        for (String part : new String[] {location.city(), location.region(), country}) {
            if (part != null && !part.isBlank() && !parts.contains(part.trim())) parts.add(part.trim());
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static String country(String code) {
        if (code == null || code.isBlank()) return null;
        String upper = code.trim().toUpperCase(Locale.ROOT);
        String name = new Locale("", upper).getDisplayCountry(Locale.ENGLISH);
        return name == null || name.isBlank() || name.equals(upper) ? upper : name;
    }

    private static boolean stopsRun(ProviderFailureException failure) {
        return failure.kind() == ProviderFailureException.Kind.CANCELLED
                || failure.kind() == ProviderFailureException.Kind.INTERRUPTED;
    }

    private ProviderFailureException failure(ProviderFailureException.Kind kind) {
        return new ProviderFailureException(sourceName(), kind, false, null, null);
    }

    private void ensureValid(BooleanSupplier requestValid) {
        if (!requestValid.getAsBoolean()) throw failure(ProviderFailureException.Kind.CANCELLED);
    }
}
