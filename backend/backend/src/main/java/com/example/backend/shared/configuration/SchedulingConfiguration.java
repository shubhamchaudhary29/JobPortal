package com.example.backend.shared.configuration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** One global switch for every external or maintenance scheduler. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "job-aggregation.scheduling", name = "enabled", havingValue = "true")
public class SchedulingConfiguration { }
