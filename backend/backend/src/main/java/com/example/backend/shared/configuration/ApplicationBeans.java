package com.example.backend.shared.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class ApplicationBeans {
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

}
