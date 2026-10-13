package com.example.backend.integration.jooble;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

record JoobleRequest(String keywords, String location, String page,
                     @JsonProperty("ResultOnPage") int resultOnPage, String companysearch) { }

@JsonIgnoreProperties(ignoreUnknown = true)
record JoobleResponse(Integer totalCount, List<JoobleJobDto> jobs) { }

@JsonIgnoreProperties(ignoreUnknown = true)
record JoobleJobDto(String id, String title, String location, String snippet, String salary, String source,
                    String type, String link, String company, String updated) { }
