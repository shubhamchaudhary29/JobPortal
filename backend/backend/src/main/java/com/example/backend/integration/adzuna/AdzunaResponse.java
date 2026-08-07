package com.example.backend.integration.adzuna;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AdzunaResponse(List<AdzunaJob> results) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AdzunaJob(String id, String title, String description,
                            @JsonProperty("redirect_url") String redirectUrl,
                            @JsonProperty("salary_min") Double salaryMin,
                            Company company, Location location) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Company(@JsonProperty("display_name") String displayName) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Location(@JsonProperty("display_name") String displayName) { }
}
