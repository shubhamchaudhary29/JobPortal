package com.example.backend.controller;

import com.example.backend.dto.LoginRequest;
import com.example.backend.entity.User;
import com.example.backend.entity.UserRole;
import com.example.backend.security.JwtUtil;
import com.example.backend.service.LoginRateLimiter;
import com.example.backend.service.RefreshTokenService;
import com.example.backend.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {
    @Mock UserService users; @Mock JwtUtil jwt; @Mock RefreshTokenService refresh; @Mock LoginRateLimiter limiter;
    @Mock HttpServletRequest request;

    @Test
    void loginReturnsAccessTokenAndSecureHttpOnlyCookieWithoutRefreshInBody() {
        User user = new User(); user.setId("u1"); user.setEmail("user@example.test"); user.setRole(UserRole.USER);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(users.normalizeEmail("user@example.test")).thenReturn("user@example.test");
        when(users.authenticate(any(), any())).thenReturn(user);
        when(refresh.issue(user)).thenReturn(new RefreshTokenService.IssuedToken("opaque-refresh-value"));
        when(jwt.generateToken(any(), any())).thenReturn("short-access-token");
        AuthController controller = new AuthController(users, jwt, refresh, limiter, true, "Strict", 14);
        var response = controller.login(new LoginRequest("user@example.test", "password"), request);
        assertEquals("short-access-token", response.getBody().accessToken());
        assertFalse(response.getBody().toString().contains("opaque-refresh-value"));
        String cookie = response.getHeaders().getFirst("Set-Cookie");
        assertTrue(cookie.contains("HttpOnly")); assertTrue(cookie.contains("Secure"));
        assertTrue(cookie.contains("SameSite=Strict")); assertTrue(cookie.contains("Path=/auth"));
    }
}
