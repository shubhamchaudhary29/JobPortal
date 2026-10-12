package com.example.backend.integration.ashby;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record AshbyJobBoardResponse(List<AshbyJobDto> jobs) { }
