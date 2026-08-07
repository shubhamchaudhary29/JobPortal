package com.example.backend.controller;

import com.example.backend.dto.AuthResponse;
import com.example.backend.dto.LoginRequest;
import com.example.backend.dto.RegisterRequest;
import com.example.backend.dto.RegisterResponse;
import com.example.backend.entity.User;
import com.example.backend.entity.UserRole;
import com.example.backend.security.JwtUtil;
import com.example.backend.service.LoginRateLimiter;
import com.example.backend.service.RefreshTokenService;
import com.example.backend.service.UserService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private static final String COOKIE_NAME = "refresh_token";
    private final UserService userService;
    private final JwtUtil jwtUtil;
    private final RefreshTokenService refreshTokens;
    private final LoginRateLimiter limiter;
    private final boolean cookieSecure;
    private final String sameSite;
    private final long refreshDays;

    public AuthController(UserService userService, JwtUtil jwtUtil, RefreshTokenService refreshTokens,
                          LoginRateLimiter limiter,
                          @Value("${security.refresh-cookie-secure:true}") boolean cookieSecure,
                          @Value("${security.refresh-cookie-same-site:Strict}") String sameSite,
                          @Value("${security.refresh-token-days:14}") long refreshDays) {
        this.userService = userService;
        this.jwtUtil = jwtUtil;
        this.refreshTokens = refreshTokens;
        this.limiter = limiter;
        this.cookieSecure = cookieSecure;
        this.sameSite = sameSite;
        this.refreshDays = refreshDays;
    }

    @PostMapping("/register")
    public RegisterResponse registerCandidate(@Valid @RequestBody RegisterRequest request) {
        return response(userService.register(request, UserRole.USER));
    }

    @PostMapping("/register/recruiter")
    public RegisterResponse registerRecruiter(@Valid @RequestBody RegisterRequest request) {
        return response(userService.register(request, UserRole.RECRUITER));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String key = clientIp(httpRequest) + ":" + userService.normalizeEmail(request.email());
        limiter.check(key);
        User user = userService.authenticate(request.email(), request.password());
        limiter.reset(key);
        var refresh = refreshTokens.issue(user);
        return withCookie(user, refresh.rawToken());
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(HttpServletRequest request) {
        limiter.check(clientIp(request) + ":refresh");
        var rotated = refreshTokens.rotate(cookie(request));
        return withCookie(rotated.user(), rotated.rawToken());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        refreshTokens.revoke(cookie(request));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, clearCookie().toString()).build();
    }

    private ResponseEntity<AuthResponse> withCookie(User user, String rawRefresh) {
        String access = jwtUtil.generateToken(user.getEmail(), user.getRole().name());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(rawRefresh).toString())
                .body(new AuthResponse(access, user.getRole(), user.getEmail()));
    }

    private ResponseCookie refreshCookie(String value) {
        return ResponseCookie.from(COOKIE_NAME, value).httpOnly(true).secure(cookieSecure)
                .sameSite(sameSite).path("/auth").maxAge(Duration.ofDays(refreshDays)).build();
    }

    private ResponseCookie clearCookie() {
        return ResponseCookie.from(COOKIE_NAME, "").httpOnly(true).secure(cookieSecure)
                .sameSite(sameSite).path("/auth").maxAge(Duration.ZERO).build();
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

    private RegisterResponse response(User user) {
        return new RegisterResponse(user.getId(), user.getEmail(), user.getFullName(), user.getRole());
    }
}
