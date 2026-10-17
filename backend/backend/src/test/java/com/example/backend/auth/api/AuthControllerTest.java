package com.example.backend.auth.api;

import com.example.backend.auth.api.dto.AuthResponse;
import com.example.backend.auth.api.dto.LoginRequest;
import com.example.backend.auth.application.AuthService;
import com.example.backend.auth.application.LoginRateLimiter;
import com.example.backend.auth.application.RefreshTokenService;
import com.example.backend.shared.error.RateLimitException;
import com.example.backend.shared.error.UnauthorizedException;
import com.example.backend.shared.security.JwtUtil;
import com.example.backend.user.application.UserService;
import com.example.backend.user.infrastructure.UserDocument;
import com.example.backend.user.domain.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthControllerTest {
    @Test
    void loginReturnsAccessTokenAndVersionedSecureCookieWithoutRefreshInBody() {
        AuthService auth = mock(AuthService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        LoginRequest login = new LoginRequest("user@example.test", "password");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(auth.login(login, "127.0.0.1")).thenReturn(new AuthService.Session(
                new AuthResponse("short-access", UserRole.USER, "user@example.test"), "opaque-refresh"));
        AuthController controller = new AuthController(auth, true, "Strict", 14);

        var response = controller.login(login, request);

        assertEquals("short-access", response.getBody().accessToken());
        assertFalse(response.getBody().toString().contains("opaque-refresh"));
        String cookie = response.getHeaders().getFirst("Set-Cookie");
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("Secure"));
        assertTrue(cookie.contains("SameSite=Strict"));
        assertTrue(cookie.contains("Path=/api/v1/auth"));
    }

    @Test
    void spoofedForwardedForDoesNotEscapeTheLoginLimiter() {
        UserService users = mock(UserService.class);
        when(users.normalizeEmail(anyString())).thenAnswer(call -> call.getArgument(0, String.class).toLowerCase());
        when(users.authenticate(anyString(), anyString())).thenThrow(new UnauthorizedException("Invalid credentials"));
        int limit = 5;
        AuthService auth = new AuthService(users, mock(JwtUtil.class), mock(RefreshTokenService.class),
                new LoginRateLimiter(limit, 60, 100, Clock.systemUTC()), new LoginRateLimiter(20, 60, 100, Clock.systemUTC()));
        AuthController controller = new AuthController(auth, true, "Strict", 14);
        LoginRequest login = new LoginRequest("victim@example.test", "guess");

        for (int attempt = 0; attempt < limit; attempt++) {
            MockHttpServletRequest request = proxied("10.9.8." + attempt);
            assertThrows(UnauthorizedException.class, () -> controller.login(login, request));
        }
        MockHttpServletRequest request = proxied("10.9.8.99");
        assertThrows(RateLimitException.class, () -> controller.login(login, request));
    }

    @Test
    void refreshIsLimitedPerSessionNotPerIp() {
        RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
        UserDocument user = new UserDocument(); user.setEmail("user@example.test"); user.setRole(UserRole.USER);
        when(refreshTokens.rotate(anyString())).thenAnswer(call -> new RefreshTokenService.RotatedToken(user, "rotated"));
        AuthService auth = new AuthService(mock(UserService.class), mock(JwtUtil.class), refreshTokens,
                new LoginRateLimiter(5, 60, 100, Clock.systemUTC()), new LoginRateLimiter(20, 60, 100, Clock.systemUTC()));
        AuthController controller = new AuthController(auth, true, "Strict", 14);

        // Five sessions behind one shared IP (campus Wi-Fi / CGNAT), five refreshes each: no shared bucket.
        for (int session = 0; session < 5; session++) {
            for (int attempt = 0; attempt < 5; attempt++) {
                assertEquals(200, controller.refresh(withCookie(token(session))).getStatusCode().value());
            }
        }
        // One cookie replayed: 20 per minute, then 429 for that session only.
        for (int attempt = 5; attempt < 20; attempt++) controller.refresh(withCookie(token(0)));
        assertThrows(RateLimitException.class, () -> controller.refresh(withCookie(token(0))));
        assertEquals(200, controller.refresh(withCookie(token(1))).getStatusCode().value());
    }

    @Test
    void missingRefreshCookieIs401WithoutTouchingTheLimiter() {
        LoginRateLimiter refreshLimiter = mock(LoginRateLimiter.class);
        AuthService auth = new AuthService(mock(UserService.class), mock(JwtUtil.class), mock(RefreshTokenService.class),
                new LoginRateLimiter(5, 60, 100, Clock.systemUTC()), refreshLimiter);
        AuthController controller = new AuthController(auth, true, "Strict", 14);

        assertThrows(UnauthorizedException.class, () -> controller.refresh(proxied("1.1.1.1")));
        verifyNoInteractions(refreshLimiter);
    }

    private static String token(int session) { return ("session-" + session + "-").repeat(8); }

    private static MockHttpServletRequest withCookie(String token) {
        MockHttpServletRequest request = proxied("6.6.6.6");
        request.setCookies(new jakarta.servlet.http.Cookie("refresh_token", token));
        return request;
    }

    /** A request as nginx forwards it: X-Real-IP is the visitor, X-Forwarded-For carries a fresh forged value. */
    private static MockHttpServletRequest proxied(String forgedForwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.18.0.4");
        request.addHeader("X-Real-IP", "198.51.100.23");
        request.addHeader("X-Forwarded-For", forgedForwardedFor + ", 198.51.100.23");
        return request;
    }
}
