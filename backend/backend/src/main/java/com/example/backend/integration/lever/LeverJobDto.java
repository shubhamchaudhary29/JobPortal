package com.example.backend.integration.lever;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
record LeverJobDto(String id, String text, String description, String descriptionPlain, String hostedUrl,
                   Long createdAt, LeverCategoriesDto categories) { }

@JsonIgnoreProperties(ignoreUnknown = true)
record LeverCategoriesDto(String location, String commitment) { }
