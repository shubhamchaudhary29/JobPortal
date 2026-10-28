package com.example.backend.integration.greenhouse;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record GreenhouseJobsResponse(List<GreenhouseJobDto> jobs) { }

@JsonIgnoreProperties(ignoreUnknown = true)
record GreenhouseJobDto(Long id, String title, String content, GreenhouseLocationDto location,
                        String absolute_url, List<GreenhouseMetadataDto> metadata, List<GreenhouseOfficeDto> offices) {
    GreenhouseJobDto(Long id, String title, String content, GreenhouseLocationDto location, String absolute_url,
                     List<GreenhouseMetadataDto> metadata) {
        this(id, title, content, location, absolute_url, metadata, null);
    }
}

/** An office the job is posted to (GEO-10E); {@code location} is its address text, often city-level. */
@JsonIgnoreProperties(ignoreUnknown = true)
record GreenhouseOfficeDto(String name, String location) { }

@JsonIgnoreProperties(ignoreUnknown = true)
record GreenhouseLocationDto(String name) { }

@JsonIgnoreProperties(ignoreUnknown = true)
record GreenhouseMetadataDto(String name, String value) { }
