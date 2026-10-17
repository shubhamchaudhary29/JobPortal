package com.example.backend.auth.application;

import com.example.backend.auth.infrastructure.RefreshTokenDocument;
import com.example.backend.auth.infrastructure.RefreshTokenRepository;
import com.example.backend.shared.error.UnauthorizedException;
import com.example.backend.user.infrastructure.UserDocument;
import com.example.backend.user.infrastructure.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.OptimisticLockingFailureException;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RefreshTokenServiceTest {
    private static final long GRACE_SECONDS = 30;
    private RefreshTokenRepository tokens;
    private UserRepository users;
    private RefreshTokenService service;
    private UserDocument user;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        tokens = mock(RefreshTokenRepository.class);
        users = mock(UserRepository.class);
        clock = new MutableClock(Instant.parse("2026-10-17T10:00:00Z"));
        service = new RefreshTokenService(tokens, users, 14, clock, new SecureRandom(), GRACE_SECONDS);
        user = new UserDocument(); user.setId("user1"); user.setEmail("user@example.test");
        when(tokens.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(users.findById("user1")).thenReturn(Optional.of(user));
        when(tokens.findByUserIdAndFamilyIdAndRevokedAtIsNull(any(), any())).thenReturn(List.of());
    }

    /** Issues a token and makes the repository find it by hash; returns the raw token and its stored document. */
    private Issued issued() {
        var raw = service.issue(user).rawToken();
        ArgumentCaptor<RefreshTokenDocument> stored = ArgumentCaptor.forClass(RefreshTokenDocument.class);
        verify(tokens, atLeastOnce()).save(stored.capture());
        RefreshTokenDocument document = stored.getValue();
        when(tokens.findByTokenHash(document.getTokenHash())).thenReturn(Optional.of(document));
        return new Issued(raw, document);
    }

    @Test
    void storesOnlyHashAndRotatesEveryUse() {
        Issued token = issued();
        assertNotEquals(token.raw(), token.document().getTokenHash());
        var rotated = service.rotate(token.raw());
        assertNotEquals(token.raw(), rotated.rawToken());
        assertNotNull(token.document().getRevokedAt());
        assertNotNull(token.document().getReplacedByHash());
    }

    @Test
    void reuseRevokesTheActiveTokenFamily() {
        Issued token = issued();
        service.rotate(token.raw());
        clock.advance(Duration.ofSeconds(GRACE_SECONDS + 1));

        assertThrows(UnauthorizedException.class, () -> service.rotate(token.raw()));
        verify(tokens).findByUserIdAndFamilyIdAndRevokedAtIsNull("user1", token.document().getFamilyId());
    }

    @Test
    void concurrentReuseWithinGraceIsRejectedWithoutRevokingTheFamily() {
        Issued token = issued();
        service.rotate(token.raw());
        clock.advance(Duration.ofSeconds(GRACE_SECONDS));

        assertThrows(UnauthorizedException.class, () -> service.rotate(token.raw()));
        verify(tokens, never()).findByUserIdAndFamilyIdAndRevokedAtIsNull(any(), any());
    }

    @Test
    void reuseOfALoggedOutTokenRevokesTheFamilyEvenImmediately() {
        Issued token = issued();
        service.revoke(token.raw());

        assertThrows(UnauthorizedException.class, () -> service.rotate(token.raw()));
        verify(tokens).findByUserIdAndFamilyIdAndRevokedAtIsNull("user1", token.document().getFamilyId());
    }

    @Test
    void optimisticLockConflictIsABenign401() {
        Issued token = issued();
        when(tokens.save(token.document())).thenThrow(new OptimisticLockingFailureException("raced"));

        assertThrows(UnauthorizedException.class, () -> service.rotate(token.raw()));
        verify(tokens, never()).findByUserIdAndFamilyIdAndRevokedAtIsNull(any(), any());
    }

    private record Issued(String raw, RefreshTokenDocument document) { }

    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public Instant instant() { return now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }
}
