package com.example.backend.auth.application;

import com.example.backend.auth.infrastructure.RefreshTokenDocument;
import com.example.backend.auth.infrastructure.RefreshTokenRepository;
import com.example.backend.shared.error.UnauthorizedException;
import com.example.backend.user.infrastructure.UserDocument;
import com.example.backend.user.infrastructure.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RefreshTokenServiceTest {
    private RefreshTokenRepository tokens;
    private UserRepository users;
    private RefreshTokenService service;
    private UserDocument user;

    @BeforeEach
    void setUp() {
        tokens = mock(RefreshTokenRepository.class);
        users = mock(UserRepository.class);
        service = new RefreshTokenService(tokens, users, 14, Clock.systemUTC(), new SecureRandom());
        user = new UserDocument(); user.setId("user1"); user.setEmail("user@example.test");
        when(tokens.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void storesOnlyHashAndRotatesEveryUse() {
        var issued = service.issue(user);
        ArgumentCaptor<RefreshTokenDocument> stored = ArgumentCaptor.forClass(RefreshTokenDocument.class);
        verify(tokens).save(stored.capture());
        assertNotEquals(issued.rawToken(), stored.getValue().getTokenHash());
        reset(tokens);
        when(tokens.findByTokenHash(stored.getValue().getTokenHash())).thenReturn(Optional.of(stored.getValue()));
        when(tokens.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(users.findById("user1")).thenReturn(Optional.of(user));
        var rotated = service.rotate(issued.rawToken());
        assertNotEquals(issued.rawToken(), rotated.rawToken());
        assertNotNull(stored.getValue().getRevokedAt());
    }

    @Test
    void reuseRevokesTheActiveTokenFamily() {
        var issued = service.issue(user);
        ArgumentCaptor<RefreshTokenDocument> stored = ArgumentCaptor.forClass(RefreshTokenDocument.class);
        verify(tokens).save(stored.capture());
        stored.getValue().setRevokedAt(java.time.Instant.now());
        when(tokens.findByTokenHash(stored.getValue().getTokenHash())).thenReturn(Optional.of(stored.getValue()));
        when(tokens.findByUserIdAndFamilyIdAndRevokedAtIsNull(any(), any())).thenReturn(java.util.List.of());
        assertThrows(UnauthorizedException.class, () -> service.rotate(issued.rawToken()));
        verify(tokens).findByUserIdAndFamilyIdAndRevokedAtIsNull("user1", stored.getValue().getFamilyId());
    }
}
