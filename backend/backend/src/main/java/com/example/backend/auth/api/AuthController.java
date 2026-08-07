package com.example.backend.auth.api;

import com.example.backend.auth.api.dto.AuthResponse;
import com.example.backend.auth.api.dto.LoginRequest;
import com.example.backend.auth.api.dto.RegisterRequest;
import com.example.backend.auth.api.dto.RegisterResponse;
import com.example.backend.auth.application.AuthService;
import com.example.backend.user.domain.UserRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Duration;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Access-token and rotating refresh-cookie sessions")
public class AuthController {
    private static final String COOKIE_NAME = "refresh_token";
    private static final String COOKIE_PATH = "/api/v1/auth";
    private final AuthService auth;
    private final boolean cookieSecure;
    private final String sameSite;
    private final long refreshDays;

    public AuthController(AuthService auth,
                          @Value("${security.refresh-cookie-secure:true}") boolean cookieSecure,
                          @Value("${security.refresh-cookie-same-site:Strict}") String sameSite,
                          @Value("${security.refresh-token-days:14}") long refreshDays) {
        this.auth = auth;
        this.cookieSecure = cookieSecure;
        this.sameSite = sameSite;
        this.refreshDays = refreshDays;
    }

    @PostMapping("/registrations")
    @Operation(summary = "Register a candidate")
    public ResponseEntity<RegisterResponse> registerCandidate(@Valid @RequestBody RegisterRequest request) {
        RegisterResponse response = auth.register(request, UserRole.USER);
        return ResponseEntity.created(URI.create("/api/v1/users/" + response.id())).body(response);
    }

    @PostMapping("/recruiter-registrations")
    @Operation(summary = "Register a recruiter", description = "Public recruiter registration retained for current product compatibility")
    public ResponseEntity<RegisterResponse> registerRecruiter(@Valid @RequestBody RegisterRequest request) {
        RegisterResponse response = auth.register(request, UserRole.RECRUITER);
        return ResponseEntity.created(URI.create("/api/v1/users/" + response.id())).body(response);
    }

    @PostMapping("/sessions")
    @Operation(summary = "Create an authenticated session", description = "Returns a short-lived bearer token and sets an HttpOnly refresh cookie")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return session(auth.login(request, clientIp(httpRequest)));
    }

    @PostMapping("/sessions/refresh")
    @SecurityRequirement(name = "refreshCookie")
    @Operation(summary = "Rotate the refresh token", description = "Uses and replaces the HttpOnly refresh cookie")
    public ResponseEntity<AuthResponse> refresh(HttpServletRequest request) {
        return session(auth.refresh(cookie(request), clientIp(request)));
    }

    @DeleteMapping("/sessions/current")
    @SecurityRequirement(name = "refreshCookie")
    @Operation(summary = "Log out the active refresh session")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        auth.logout(cookie(request));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, clearCookie().toString()).build();
    }

    private ResponseEntity<AuthResponse> session(AuthService.Session session) {
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, refreshCookie(session.refreshToken()).toString())
                .body(session.response());
    }

    private ResponseCookie refreshCookie(String value) {
        return ResponseCookie.from(COOKIE_NAME, value).httpOnly(true).secure(cookieSecure).sameSite(sameSite)
                .path(COOKIE_PATH).maxAge(Duration.ofDays(refreshDays)).build();
    }

    private ResponseCookie clearCookie() {
        return ResponseCookie.from(COOKIE_NAME, "").httpOnly(true).secure(cookieSecure).sameSite(sameSite)
                .path(COOKIE_PATH).maxAge(Duration.ZERO).build();
    }

    private String cookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) if (COOKIE_NAME.equals(cookie.getName())) return cookie.getValue();
        return null;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded == null || forwarded.isBlank() ? request.getRemoteAddr() : forwarded.split(",", 2)[0].trim();
    }
}
