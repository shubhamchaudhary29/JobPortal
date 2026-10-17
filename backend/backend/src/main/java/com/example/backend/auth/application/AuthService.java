package com.example.backend.auth.application;

import com.example.backend.auth.api.dto.AuthResponse;
import com.example.backend.auth.api.dto.LoginRequest;
import com.example.backend.auth.api.dto.RegisterRequest;
import com.example.backend.auth.api.dto.RegisterResponse;
import com.example.backend.shared.security.JwtUtil;
import com.example.backend.user.application.UserService;
import com.example.backend.user.application.RegisterUserCommand;
import com.example.backend.user.domain.UserRole;
import com.example.backend.user.infrastructure.UserDocument;
import com.example.backend.shared.error.UnauthorizedException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class AuthService {
    private final UserService users;
    private final JwtUtil jwt;
    private final RefreshTokenService refreshTokens;
    private final LoginRateLimiter limiter;
    private final LoginRateLimiter refreshLimiter;

    public AuthService(UserService users, JwtUtil jwt, RefreshTokenService refreshTokens,
                       @Qualifier("loginRateLimiter") LoginRateLimiter limiter,
                       @Qualifier("refreshRateLimiter") LoginRateLimiter refreshLimiter) {
        this.users = users;
        this.jwt = jwt;
        this.refreshTokens = refreshTokens;
        this.limiter = limiter;
        this.refreshLimiter = refreshLimiter;
    }

    public RegisterResponse register(RegisterRequest request, UserRole role) {
        return registration(users.register(new RegisterUserCommand(
                request.fullName(), request.email(), request.password()), role));
    }

    public Session login(LoginRequest request, String clientIp) {
        String key = clientIp + ":" + users.normalizeEmail(request.email());
        limiter.check(key);
        UserDocument user = users.authenticate(request.email(), request.password());
        limiter.reset(key);
        return session(user, refreshTokens.issue(user).rawToken());
    }

    /**
     * Refresh is limited per refresh token, not per IP (GEO-7F): many users share one IP behind campus Wi-Fi or
     * carrier-grade NAT, and a per-IP limit let them log each other out. The token is 384-bit random, so the key
     * cannot be varied without a valid token; IP floods are nginx's {@code refresh} zone.
     */
    public Session refresh(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) throw new UnauthorizedException("Invalid refresh token");
        refreshLimiter.check("refresh:" + sessionKey(rawToken));
        var rotated = refreshTokens.rotate(rawToken);
        return session(rotated.user(), rotated.rawToken());
    }

    public void logout(String rawToken) { refreshTokens.revoke(rawToken); }

    private Session session(UserDocument user, String refreshToken) {
        String access = jwt.generateToken(user.getEmail(), user.getRole().name());
        return new Session(new AuthResponse(access, user.getRole(), user.getEmail()), refreshToken);
    }

    private RegisterResponse registration(UserDocument user) {
        return new RegisterResponse(user.getId(), user.getEmail(), user.getFullName(), user.getRole());
    }

    /** First 16 hex characters of SHA-256(raw token): a limiter key that never holds the token or its stored hash. */
    static String sessionKey(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
    }

    public record Session(AuthResponse response, String refreshToken) { }
}
