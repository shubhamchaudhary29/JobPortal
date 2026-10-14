package com.example.backend.geo.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.backend.geo.application.MapSnapshotService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class MapSnapshotControllerTest {
    private static final String PATH = "/api/v1/jobs/map/snapshot";
    private static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    @Autowired MockMvc mvc;
    @MockitoBean MapSnapshotService snapshots;
    private MapSnapshotService.Published published;

    @BeforeEach
    void setUp() throws Exception {
        Map<String, Long> unplaced = new LinkedHashMap<>();
        unplaced.put("REMOTE", 2L);
        MapSnapshotService.MapSnapshot snapshot = new MapSnapshotService.MapSnapshot("0123456789abcdef",
                Instant.parse("2026-10-14T00:00:00Z"), 3, unplaced,
                List.of(new MapSnapshotService.MapPlace("1277333", "Bengaluru", "Karnataka", 12.9719, 77.5937, 3)));
        byte[] raw = JSON.writeValueAsBytes(snapshot);
        ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(gzipped)) { gzip.write(raw); }
        published = new MapSnapshotService.Published(snapshot, raw, gzipped.toByteArray(), "\"0123456789abcdef\"");
        when(snapshots.current()).thenReturn(Optional.of(published));
    }

    @Test
    void anonymousGetReturnsTheCachedSnapshot() throws Exception {
        MvcResult result = mvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0123456789abcdef\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, max-age=60, stale-while-revalidate=240"))
                .andExpect(varyIncludesAcceptEncoding())
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_ENCODING))
                .andReturn();
        assertTrue(result.getResponse().getContentType().startsWith("application/json"));
        MapSnapshotService.MapSnapshot body = JSON.readValue(result.getResponse().getContentAsByteArray(),
                MapSnapshotService.MapSnapshot.class);
        assertEquals(published.snapshot(), body);
    }

    @Test
    void matchingEtagsReturnNotModifiedWithoutBody() throws Exception {
        for (String etag : List.of("\"0123456789abcdef\"", "W/\"0123456789abcdef\"", "\"old\", W/\"0123456789abcdef\"")) {
            MvcResult result = mvc.perform(get(PATH).header(HttpHeaders.IF_NONE_MATCH, etag))
                    .andExpect(status().isNotModified())
                    .andExpect(header().string(HttpHeaders.ETAG, "\"0123456789abcdef\""))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, MapSnapshotController.CACHE_CONTROL))
                    .andExpect(varyIncludesAcceptEncoding())
                    .andReturn();
            assertEquals(0, result.getResponse().getContentAsByteArray().length);
        }
        mvc.perform(get(PATH).header(HttpHeaders.IF_NONE_MATCH, "\"stale\"")).andExpect(status().isOk());
    }

    @Test
    void gzipClientsGetThePrecomputedGzipBody() throws Exception {
        MvcResult result = mvc.perform(get(PATH).header(HttpHeaders.ACCEPT_ENCODING, "br, gzip;q=0.8"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_ENCODING, "gzip"))
                .andReturn();
        try (GZIPInputStream unzip = new GZIPInputStream(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            assertArrayEquals(published.raw(), unzip.readAllBytes());
        }
        mvc.perform(get(PATH).header(HttpHeaders.ACCEPT_ENCODING, "gzip;q=0"))
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_ENCODING));
    }

    @Test
    void missingSnapshotIsServiceUnavailable() throws Exception {
        when(snapshots.current()).thenReturn(Optional.empty());

        mvc.perform(get(PATH))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "10"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    @Test
    void postIsNotPermitted() throws Exception {
        int code = mvc.perform(post(PATH)).andReturn().getResponse().getStatus();
        assertTrue(code == 401 || code == 403 || code == 405, "POST returned " + code);
    }

    /** CORS adds its own Vary values (Origin, ...); ours must be among them. */
    private static org.springframework.test.web.servlet.ResultMatcher varyIncludesAcceptEncoding() {
        return result -> assertTrue(result.getResponse().getHeaders(HttpHeaders.VARY).stream()
                .flatMap(value -> java.util.Arrays.stream(value.split(","))).map(String::trim)
                .anyMatch(HttpHeaders.ACCEPT_ENCODING::equalsIgnoreCase),
                "Vary was " + result.getResponse().getHeaders(HttpHeaders.VARY));
    }

    @Test
    void etagMatchingAndGzipNegotiationRules() {
        assertTrue(MapSnapshotController.matches("*", "\"v\""));
        assertFalse(MapSnapshotController.matches(null, "\"v\""));
        assertFalse(MapSnapshotController.matches("\"v2\"", "\"v\""));
        assertTrue(MapSnapshotController.acceptsGzip("gzip, deflate"));
        assertFalse(MapSnapshotController.acceptsGzip("identity"));
        assertFalse(MapSnapshotController.acceptsGzip("gzip; q=0.000"));
    }
}
