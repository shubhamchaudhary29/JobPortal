package com.example.backend.shared.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    @GetMapping("/api/v1/health")
    public HealthResponse health() { return new HealthResponse("UP"); }

    public record HealthResponse(String status) { }
}
