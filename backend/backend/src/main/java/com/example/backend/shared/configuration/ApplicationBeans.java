package com.example.backend.shared.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration
public class ApplicationBeans {
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean(destroyMethod = "shutdown", name = "ingestionLeaseScheduler")
    ScheduledExecutorService ingestionLeaseScheduler() { return Executors.newSingleThreadScheduledExecutor(); }

}
