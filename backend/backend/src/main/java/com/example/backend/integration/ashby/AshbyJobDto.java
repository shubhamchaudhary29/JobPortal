package com.example.backend.integration.ashby;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record AshbyJobDto(String id, String title, String location, Boolean isListed, Boolean isRemote,
                   String descriptionHtml, String descriptionPlain, String publishedAt, String employmentType,
                   String jobUrl, String applyUrl, AshbyCompensationDto compensation) { }

@JsonIgnoreProperties(ignoreUnknown = true)
record AshbyCompensationDto(List<AshbyCompensationComponentDto> summaryComponents) { }

@JsonIgnoreProperties(ignoreUnknown = true)
record AshbyCompensationComponentDto(String compensationType, String interval, String currencyCode,
                                     Double minValue, Double maxValue) { }
