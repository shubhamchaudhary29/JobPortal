package com.example.backend.service;

import com.example.backend.dto.RegisterRequest;
import com.example.backend.dto.UpdateProfileRequest;
import com.example.backend.dto.UserProfileDTO;
import com.example.backend.entity.ApplicationStatus;
import com.example.backend.entity.User;
import com.example.backend.entity.UserRole;
import com.example.backend.exception.BadRequestException;
import com.example.backend.exception.ConflictException;
import com.example.backend.exception.ResourceNotFoundException;
import com.example.backend.exception.UnauthorizedException;
import com.example.backend.repository.ApplicationRepository;
import com.example.backend.repository.UserRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationRepository applicationRepository;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       ApplicationRepository applicationRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.applicationRepository = applicationRepository;
    }

    public User register(RegisterRequest request, UserRole assignedRole) {
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) throw new ConflictException("Email is already registered");
        User user = new User();
        user.setEmail(email);
        user.setFullName(request.fullName().trim());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setRole(assignedRole);
        try { return userRepository.save(user); }
        catch (DuplicateKeyException e) { throw new ConflictException("Email is already registered"); }
    }

    public User authenticate(String email, String password) {
        User user = userRepository.findByEmail(normalizeEmail(email)).orElse(null);
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            throw new UnauthorizedException("Invalid credentials");
        }
        return user;
    }

    public String normalizeEmail(String email) { return email.trim().toLowerCase(Locale.ROOT); }

    public UserProfileDTO getCandidateProfile(String email) {
        User user = userRepository.findByEmail(normalizeEmail(email))
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        int total = applicationRepository.countByUserId(user.getEmail());
        int accepted = applicationRepository.countByUserIdAndStatus(user.getEmail(), ApplicationStatus.ACCEPTED);
        int rejected = applicationRepository.countByUserIdAndStatus(user.getEmail(), ApplicationStatus.REJECTED);
        return new UserProfileDTO(user.getId(), user.getEmail(), user.getFullName(), user.getRole().name(),
                total, accepted, rejected, total - accepted - rejected);
    }

    public UserProfileDTO updateCandidateProfile(String email, UpdateProfileRequest request) {
        User user = userRepository.findByEmail(normalizeEmail(email))
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        String name = request.getFullName() == null ? "" : request.getFullName().trim();
        if (name.length() < 2 || name.length() > 100) throw new BadRequestException("Full name must be between 2 and 100 characters");
        user.setFullName(name);
        userRepository.save(user);
        return getCandidateProfile(email);
    }
}
