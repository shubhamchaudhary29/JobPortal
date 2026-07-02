package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserProfileDTO {
    private String id;
    private String email;
    private String fullName;
    private String role;
    private int totalApplications;
    private int acceptedApplications;
    private int rejectedApplications;
    private int pendingApplications;
}
