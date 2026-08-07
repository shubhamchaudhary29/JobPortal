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
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final UserService users;
    private final JwtUtil jwt;
    private final RefreshTokenService refreshTokens;
    private final LoginRateLimiter limiter;

    public AuthService(UserService users, JwtUtil jwt, RefreshTokenService refreshTokens, LoginRateLimiter limiter) {
        this.users = users;
        this.jwt = jwt;
        this.refreshTokens = refreshTokens;
        this.limiter = limiter;
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

    public Session refresh(String rawToken, String clientIp) {
        limiter.check(clientIp + ":refresh");
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

    public record Session(AuthResponse response, String refreshToken) { }
}
