package com.example.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.example.backend.integration.aggregation.EmployerRegistryProperties;
import com.example.backend.matching.config.MatchingProperties;

@SpringBootApplication
@EnableConfigurationProperties({EmployerRegistryProperties.class, MatchingProperties.class})
public class BackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}

}
