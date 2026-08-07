package com.example.backend.user.application;

import com.example.backend.shared.error.ConflictException;
import com.example.backend.shared.error.UnauthorizedException;
import com.example.backend.user.api.dto.UpdateProfileRequest;
import com.example.backend.user.domain.UserRole;
import com.example.backend.user.infrastructure.UserDocument;
import com.example.backend.user.infrastructure.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserServiceTest {
    private UserRepository users;
    private PasswordEncoder passwords;
    private ApplicationStatsProvider stats;
    private UserService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        passwords = mock(PasswordEncoder.class);
        stats = mock(ApplicationStatsProvider.class);
        service = new UserService(users, passwords, stats);
    }

    @Test
    void registrationNormalizesEmailAndUsesServerAssignedRole() {
        when(passwords.encode("strong-password")).thenReturn("hash");
        when(users.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        UserDocument result = service.register(new RegisterUserCommand(" Test User ", " TEST@Test.COM ", "strong-password"), UserRole.USER);
        assertEquals("test@test.com", result.getEmail());
        assertEquals(UserRole.USER, result.getRole());
        assertEquals("Test User", result.getFullName());
    }

    @Test
    void duplicateAndWrongCredentialsUseSafeDomainErrors() {
        when(users.existsByEmail("test@test.com")).thenReturn(true);
        assertThrows(ConflictException.class, () -> service.register(
                new RegisterUserCommand("Test", "TEST@test.com", "strong-password"), UserRole.USER));
        when(users.findByEmail("missing@test.com")).thenReturn(Optional.empty());
        assertEquals("Invalid credentials", assertThrows(UnauthorizedException.class,
                () -> service.authenticate("missing@test.com", "wrong")).getMessage());
        UserDocument existing = new UserDocument(); existing.setEmail("test@test.com"); existing.setPassword("hash");
        when(users.findByEmail("test@test.com")).thenReturn(Optional.of(existing));
        when(passwords.matches("wrong", "hash")).thenReturn(false);
        assertEquals("Invalid credentials", assertThrows(UnauthorizedException.class,
                () -> service.authenticate("test@test.com", "wrong")).getMessage());
    }

    @Test
    void correctCredentialsAuthenticateNormalizedEmail() {
        UserDocument existing = new UserDocument(); existing.setEmail("test@test.com"); existing.setPassword("hash");
        when(users.findByEmail("test@test.com")).thenReturn(Optional.of(existing));
        when(passwords.matches("password", "hash")).thenReturn(true);
        assertSame(existing, service.authenticate(" TEST@Test.COM ", "password"));
    }

    @Test
    void updateChangesOnlyEditableName() {
        UserDocument user = new UserDocument(); user.setId("u1"); user.setEmail("user@example.test");
        user.setPassword("hash"); user.setRole(UserRole.USER);
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(stats.forCandidate(user.getEmail())).thenReturn(new ApplicationStats(0, 0, 0, 0));
        service.updateProfile(user.getEmail(), new UpdateProfileRequest("New Name"));
        assertEquals("New Name", user.getFullName());
        assertEquals("hash", user.getPassword());
        assertEquals(UserRole.USER, user.getRole());
    }
}
