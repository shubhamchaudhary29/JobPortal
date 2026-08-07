package com.example.backend.service;

import com.example.backend.dto.RegisterRequest;
import com.example.backend.entity.User;
import com.example.backend.entity.UserRole;
import com.example.backend.exception.ConflictException;
import com.example.backend.exception.UnauthorizedException;
import com.example.backend.repository.ApplicationRepository;
import com.example.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {
    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock ApplicationRepository applicationRepository;
    private UserService userService;
    private User user;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, passwordEncoder, applicationRepository);
        user = new User();
        user.setId("user1");
        user.setEmail("test@test.com");
        user.setPassword("encodedPassword");
        user.setRole(UserRole.USER);
    }

    @Test
    void registerNormalizesEmailAndAssignsServerRole() {
        when(passwordEncoder.encode("strong-password")).thenReturn("encodedPassword");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        User result = userService.register(new RegisterRequest("Test User", " TEST@Test.COM ", "strong-password"), UserRole.USER);
        assertEquals("test@test.com", result.getEmail());
        assertEquals(UserRole.USER, result.getRole());
    }

    @Test
    void duplicateEmailIsConflict() {
        when(userRepository.existsByEmail("test@test.com")).thenReturn(true);
        assertThrows(ConflictException.class, () -> userService.register(
                new RegisterRequest("Test", "TEST@test.com", "strong-password"), UserRole.USER));
    }

    @Test
    void authenticateSuccess() {
        when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password", "encodedPassword")).thenReturn(true);
        assertEquals(user, userService.authenticate("TEST@test.com", "password"));
    }

    @Test
    void unknownUserAndWrongPasswordShareGenericFailure() {
        when(userRepository.findByEmail("missing@test.com")).thenReturn(Optional.empty());
        assertEquals("Invalid credentials", assertThrows(UnauthorizedException.class,
                () -> userService.authenticate("missing@test.com", "password")).getMessage());
        when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "encodedPassword")).thenReturn(false);
        assertEquals("Invalid credentials", assertThrows(UnauthorizedException.class,
                () -> userService.authenticate("test@test.com", "wrong")).getMessage());
    }
}
