package com.example.backend.auth.api;

import com.example.backend.auth.api.dto.AuthResponse;
import com.example.backend.auth.api.dto.LoginRequest;
import com.example.backend.auth.application.AuthService;
import com.example.backend.user.domain.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

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
}
