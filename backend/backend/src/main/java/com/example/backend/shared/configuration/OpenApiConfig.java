package com.example.backend.shared.configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI jobPortalOpenApi() {
        return new OpenAPI().info(new Info().title("JobPortal API").version("v1")
                        .description("Versioned JobPortal REST API. Public job search is MongoDB-backed. "
                                + "ADMIN aggregation endpoints expose bounded history, provider/employer sync, "
                                + "status, and conflict reconciliation. Access tokens use Bearer authentication; "
                                + "refresh sessions use an HttpOnly cookie."))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme().type(SecurityScheme.Type.HTTP)
                                .scheme("bearer").bearerFormat("JWT"))
                        .addSecuritySchemes("refreshCookie", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE).name("refresh_token")));
    }
}
