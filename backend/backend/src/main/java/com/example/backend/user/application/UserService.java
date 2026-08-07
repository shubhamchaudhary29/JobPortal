package com.example.backend.user.application;

import com.example.backend.shared.error.ConflictException;
import com.example.backend.shared.error.ResourceNotFoundException;
import com.example.backend.shared.error.UnauthorizedException;
import com.example.backend.user.UserMapper;
import com.example.backend.user.api.dto.UpdateProfileRequest;
import com.example.backend.user.api.dto.UserResponse;
import com.example.backend.user.domain.UserRole;
import com.example.backend.user.infrastructure.UserDocument;
import com.example.backend.user.infrastructure.UserRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class UserService {
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationStatsProvider statistics;

    public UserService(UserRepository users, PasswordEncoder passwordEncoder, ApplicationStatsProvider statistics) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.statistics = statistics;
    }

    public UserDocument register(RegisterUserCommand request, UserRole assignedRole) {
        String email = normalizeEmail(request.email());
        if (users.existsByEmail(email)) throw new ConflictException("Email is already registered");
        UserDocument user = new UserDocument();
        user.setEmail(email);
        user.setFullName(request.fullName().trim());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setRole(assignedRole);
        try { return users.save(user); }
        catch (DuplicateKeyException ex) { throw new ConflictException("Email is already registered"); }
    }

    public UserDocument authenticate(String email, String password) {
        UserDocument user = users.findByEmail(normalizeEmail(email)).orElse(null);
        if (user == null || !passwordEncoder.matches(password, user.getPassword()))
            throw new UnauthorizedException("Invalid credentials");
        return user;
    }

    public UserDocument requireByEmail(String email) {
        return users.findByEmail(normalizeEmail(email)).orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    public UserResponse getProfile(String email) {
        UserDocument user = requireByEmail(email);
        return UserMapper.toResponse(user, statistics.forCandidate(user.getEmail()));
    }

    public UserResponse updateProfile(String email, UpdateProfileRequest request) {
        UserDocument user = requireByEmail(email);
        user.setFullName(request.fullName().trim());
        users.save(user);
        return UserMapper.toResponse(user, statistics.forCandidate(user.getEmail()));
    }

    public String normalizeEmail(String email) { return email.trim().toLowerCase(Locale.ROOT); }
}
