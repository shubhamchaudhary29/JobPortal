package com.example.backend.service;

import com.example.backend.entity.RefreshToken;
import com.example.backend.entity.User;
import com.example.backend.entity.UserRole;
import com.example.backend.exception.UnauthorizedException;
import com.example.backend.repository.RefreshTokenRepository;
import com.example.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {
    @Mock RefreshTokenRepository repository;
    @Mock UserRepository userRepository;
    RefreshTokenService service;
    User user;

    @BeforeEach void setup() {
        service = new RefreshTokenService(repository, userRepository, 14);
        user = new User(); user.setId("user1"); user.setEmail("user@example.test"); user.setRole(UserRole.USER);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void storesOnlyHashAndRotatesEveryUse() {
        var issued = service.issue(user);
        var stored = org.mockito.ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(stored.capture());
        assertNotEquals(issued.rawToken(), stored.getValue().getTokenHash());
        reset(repository);
        when(repository.findByTokenHash(stored.getValue().getTokenHash())).thenReturn(Optional.of(stored.getValue()));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.findById("user1")).thenReturn(Optional.of(user));
        var rotated = service.rotate(issued.rawToken());
        assertNotEquals(issued.rawToken(), rotated.rawToken());
        assertNotNull(stored.getValue().getRevokedAt());
    }

    @Test
    void reuseOfRevokedTokenRevokesFamily() {
        var issued = service.issue(user);
        var stored = org.mockito.ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(stored.capture());
        stored.getValue().setRevokedAt(java.time.Instant.now());
        when(repository.findByTokenHash(stored.getValue().getTokenHash())).thenReturn(Optional.of(stored.getValue()));
        when(repository.findByUserIdAndFamilyIdAndRevokedAtIsNull(any(), any())).thenReturn(java.util.List.of());
        assertThrows(UnauthorizedException.class, () -> service.rotate(issued.rawToken()));
        verify(repository).findByUserIdAndFamilyIdAndRevokedAtIsNull("user1", stored.getValue().getFamilyId());
    }
}
