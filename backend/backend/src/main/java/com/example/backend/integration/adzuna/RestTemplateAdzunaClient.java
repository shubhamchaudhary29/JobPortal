package com.example.backend.integration.adzuna;

import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.beans.factory.annotation.Qualifier;

import java.net.URI;

@Component
class RestTemplateAdzunaClient implements AdzunaClient {
    private final RestTemplate http;
    private final AdzunaProperties properties;
    RestTemplateAdzunaClient(@Qualifier("restTemplate") RestTemplate adzunaRestTemplate,
                             AdzunaProperties properties) { this.http = adzunaRestTemplate; this.properties = properties; }
    @Override public AdzunaResponse fetchPage(String keyword, int page) {
        try {
            URI uri = UriComponentsBuilder.fromUriString("https://api.adzuna.com/v1/api/jobs/in/search/{page}")
                    .queryParam("app_id", properties.appId()).queryParam("app_key", properties.appKey())
                    .queryParam("results_per_page", properties.resultsPerPage()).queryParam("what", keyword)
                    .queryParam("content-type", "application/json").buildAndExpand(page).encode().toUri();
            AdzunaResponse response = http.getForObject(uri, AdzunaResponse.class);
            if (response == null) throw new AdzunaProviderException("Adzuna returned an empty response", true, null);
            return response;
        } catch (AdzunaProviderException ex) { throw ex;
        } catch (RestClientResponseException ex) {
            HttpStatusCode status = ex.getStatusCode();
            boolean retryable = status.value() == 429 || status.is5xxServerError();
            throw new AdzunaProviderException("Adzuna request failed with HTTP " + status.value(), retryable, ex);
        } catch (ResourceAccessException ex) {
            throw new AdzunaProviderException("Adzuna connection failed", true, ex);
        } catch (RuntimeException ex) {
            throw new AdzunaProviderException("Adzuna response could not be processed", false, ex);
        }
    }
}
