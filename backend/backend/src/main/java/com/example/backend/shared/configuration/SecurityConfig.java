package com.example.backend.shared.configuration;

import com.example.backend.shared.security.JwtFilter;
import com.example.backend.shared.security.ProblemAccessDeniedHandler;
import com.example.backend.shared.security.ProblemAuthenticationEntryPoint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.RegexRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private final JwtFilter jwtFilter;
    private final ProblemAuthenticationEntryPoint authenticationEntryPoint;
    private final ProblemAccessDeniedHandler accessDeniedHandler;
    private final String allowedOrigins;

    public SecurityConfig(JwtFilter jwtFilter, ProblemAuthenticationEntryPoint authenticationEntryPoint,
                          ProblemAccessDeniedHandler accessDeniedHandler,
                          @Value("${app.cors.allowed-origins}") String allowedOrigins) {
        this.jwtFilter = jwtFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.allowedOrigins = allowedOrigins;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        config.setAllowedOrigins(Arrays.stream(allowedOrigins.split(",")).map(String::trim)
                .filter(origin -> !origin.isEmpty()).toList());
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.cors(Customizer.withDefaults()).csrf(csrf -> csrf.disable())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/registrations",
                                "/api/v1/auth/recruiter-registrations", "/api/v1/auth/sessions",
                                "/api/v1/auth/sessions/refresh").permitAll()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/auth/sessions/current").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/jobs", "/api/v1/health",
                                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/candidate-profile/**", "/api/v1/candidate-profile").hasRole("USER")
                        .requestMatchers("/api/v1/application-workspace/**", "/api/v1/application-workspace",
                                "/api/v1/resume-versions/**", "/api/v1/cover-letters/**",
                                "/api/v1/jobs/*/application-readiness", "/api/v1/jobs/*/tailoring-plan",
                                "/api/v1/jobs/*/resume-versions", "/api/v1/jobs/*/cover-letters").hasRole("USER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/jobs/matched", "/api/v1/jobs/*/match").hasRole("USER")
                        .requestMatchers(new RegexRequestMatcher("^/api/v1/jobs/(?!mine$)[^/]+$", "GET")).permitAll()
                        .requestMatchers("/ws/**").permitAll()
                        .anyRequest().authenticated())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class).build();
    }
}
