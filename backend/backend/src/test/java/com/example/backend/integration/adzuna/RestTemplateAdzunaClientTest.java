package com.example.backend.integration.adzuna;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import java.net.URI;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RestTemplateAdzunaClientTest {
    @Test
    void mapsAuthenticationFailureToSafeNonRetryableErrorWithoutCredentialLeakage() {
        RestTemplate http = mock(RestTemplate.class);
        when(http.getForObject(any(URI.class), eq(AdzunaResponse.class))).thenThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "unauthorized", HttpHeaders.EMPTY, new byte[0], null));
        var client = new RestTemplateAdzunaClient(http, AdzunaServiceTest.properties(1, "java"));
        AdzunaProviderException error = assertThrows(AdzunaProviderException.class, () -> client.fetchPage("java", 1));
        assertFalse(error.retryable()); assertFalse(error.getMessage().contains("key-not-secret")); assertFalse(error.getMessage().contains("app_key"));
    }

    @Test
    void mapsTimeoutToRetryableSafeError() {
        RestTemplate http = mock(RestTemplate.class);
        when(http.getForObject(any(URI.class), eq(AdzunaResponse.class))).thenThrow(new ResourceAccessException("timed out while connecting"));
        AdzunaProviderException error = assertThrows(AdzunaProviderException.class,
                () -> new RestTemplateAdzunaClient(http, AdzunaServiceTest.properties(1, "java")).fetchPage("java", 1));
        assertTrue(error.retryable()); assertEquals("Adzuna connection failed", error.getMessage());
    }
}
