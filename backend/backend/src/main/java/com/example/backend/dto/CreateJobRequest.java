package com.example.backend.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateJobRequest(
        @NotBlank @Size(max = 150) String title,
        @NotBlank @Size(max = 10000) String description,
        @NotBlank @Size(max = 150) String location,
        @NotBlank @Size(max = 150) String company,
        @DecimalMin("0.0") double salary,
        @DecimalMin("0.0") double experience) { }
