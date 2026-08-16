package com.example.backend.integration.reliability;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Component
public class ProviderHttpClient {
    private final RestTemplate http;
    private final Clock clock;
    public ProviderHttpClient(@Qualifier("providerRestTemplate") RestTemplate http, Clock clock) {
        this.http = http;
        this.clock = clock;
    }
    public <T> T get(String provider, String url, Class<T> responseType) {
        try {
            return http.getForObject(url, responseType);
        } catch (RuntimeException failure) {
            throw classify(provider, failure);
        }
    }
    public <T> T get(String provider, String url, ParameterizedTypeReference<T> responseType) {
        try {
            return http.exchange(url, HttpMethod.GET, null, responseType).getBody();
        } catch (RuntimeException failure) {
            throw classify(provider, failure);
        }
    }
    private ProviderFailureException classify(String provider, RuntimeException failure) {
        if (failure instanceof ProviderFailureException providerFailure) return providerFailure;
        if (failure instanceof HttpStatusCodeException statusFailure) {
            int status = statusFailure.getStatusCode().value();
            if (status == 429) return new ProviderFailureException(provider,
                    ProviderFailureException.Kind.RATE_LIMITED, true,
                    retryAfter(statusFailure.getResponseHeaders()), statusFailure);
            if (status >= 500) return new ProviderFailureException(provider,
                    ProviderFailureException.Kind.SERVER_ERROR, true, null, statusFailure);
            return new ProviderFailureException(provider, ProviderFailureException.Kind.CLIENT_ERROR,
                    false, null, statusFailure);
        }
        if (failure instanceof ResourceAccessException) {
            return new ProviderFailureException(provider, ProviderFailureException.Kind.TIMEOUT,
                    true, null, failure);
        }
        if (failure instanceof RestClientException) {
            return new ProviderFailureException(provider, ProviderFailureException.Kind.MALFORMED_RESPONSE,
                    false, null, failure);
        }
        return new ProviderFailureException(provider, ProviderFailureException.Kind.MALFORMED_RESPONSE,
                false, null, failure);
    }
    private Duration retryAfter(HttpHeaders headers) {
        if (headers == null) return null;
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null || value.isBlank()) return null;
        try { return Duration.ofSeconds(Math.max(0, Long.parseLong(value.trim()))); }
        catch (NumberFormatException ignored) {
            try {
                Duration duration = Duration.between(clock.instant(),
                        ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
                return duration.isNegative() ? Duration.ZERO : duration;
            } catch (RuntimeException malformed) { return null; }
        }
    }
}
