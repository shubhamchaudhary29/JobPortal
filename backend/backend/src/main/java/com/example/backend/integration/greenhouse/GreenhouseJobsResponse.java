package com.example.backend.integration.greenhouse;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record GreenhouseJobsResponse(List<GreenhouseJobDto> jobs) { }

@JsonIgnoreProperties(ignoreUnknown = true)
record GreenhouseJobDto(Long id, String title, String content, GreenhouseLocationDto location,
                        String absolute_url, List<GreenhouseMetadataDto> metadata) { }

@JsonIgnoreProperties(ignoreUnknown = true)
record GreenhouseLocationDto(String name) { }

@JsonIgnoreProperties(ignoreUnknown = true)
record GreenhouseMetadataDto(String name, String value) { }
