package com.example.backend.integration.workable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record WorkableAccountResponse(String name, List<WorkableJobDto> jobs) { }

@JsonIgnoreProperties(ignoreUnknown = true)
record WorkableJobDto(String shortcode, String title, String description,
                      @JsonProperty("employment_type") String employmentType, Boolean telecommuting,
                      String city, String state, String country,
                      @JsonProperty("published_on") String publishedOn, String url,
                      @JsonProperty("application_url") String applicationUrl) { }
