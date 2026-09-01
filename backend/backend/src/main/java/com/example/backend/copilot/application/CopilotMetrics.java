package com.example.backend.copilot.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class CopilotMetrics {
    private final Counter readiness;
    private final Counter resumes;
    private final Counter coverLetters;

    public CopilotMetrics(MeterRegistry registry) {
        readiness = Counter.builder("application_readiness_calculation_total").register(registry);
        resumes = Counter.builder("tailored_resume_created_total").register(registry);
        coverLetters = Counter.builder("cover_letter_created_total").register(registry);
    }
    public void readinessCalculated() { readiness.increment(); }
    public void resumeCreated() { resumes.increment(); }
    public void coverLetterCreated() { coverLetters.increment(); }
}
