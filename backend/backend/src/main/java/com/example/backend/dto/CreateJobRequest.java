package com.example.backend.dto;

import lombok.Data;

@Data
public class CreateJobRequest {
    private String title;
    private String description;
    private String location;
    private String company;
    private double salary;
    private double experience;
}
