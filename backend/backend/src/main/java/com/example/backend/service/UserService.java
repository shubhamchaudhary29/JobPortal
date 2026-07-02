package com.example.backend.service;

import com.example.backend.dto.UpdateProfileRequest;
import com.example.backend.dto.UserProfileDTO;
import com.example.backend.exception.BadRequestException;
import com.example.backend.exception.ResourceNotFoundException;
import com.example.backend.repository.ApplicationRepository;
import com.example.backend.repository.UserRepository;
import com.example.backend.security.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import com.example.backend.entity.User;

import java.util.HashMap;
import java.util.Map;

@Service
public class UserService {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtUtil jwtUtils;
    @Autowired
    private ApplicationRepository applicationRepository;

    public User register(User user) {
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        if (user.getRole() == null || user.getRole().isEmpty()) {
            user.setRole("USER");
        }
        return userRepository.save(user);
    }

    public Map<String, String> login(String email, String password) {
        User loggedInUser = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (!passwordEncoder.matches(password, loggedInUser.getPassword())) {
            throw new BadRequestException("Invalid password");
        }

        String token = jwtUtils.generateToken(email, loggedInUser.getRole());

        Map<String, String> response = new HashMap<>();
        response.put("token", token);
        return response;
    }

    /**
     * Fetches the profile + application stats for the user identified by email.
     * Note: in the Application collection, userId stores the user's email (not the MongoDB _id).
     */
    public UserProfileDTO getCandidateProfile(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + email));

        // userId in the applications collection is stored as the user's email
        int total    = applicationRepository.countByUserId(email);
        int accepted = applicationRepository.countByUserIdAndStatus(email, "ACCEPTED");
        int rejected = applicationRepository.countByUserIdAndStatus(email, "REJECTED");
        int pending  = total - accepted - rejected;

        return new UserProfileDTO(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole(),
                total,
                accepted,
                rejected,
                pending
        );
    }

    /**
     * Updates the editable profile fields (fullName only) for the user identified by email.
     */
    public UserProfileDTO updateCandidateProfile(String email, UpdateProfileRequest request) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + email));

        String newName = request.getFullName();

        if (newName == null || newName.isBlank()) {
            throw new BadRequestException("Full name must not be empty.");
        }

        newName = newName.trim();

        if (newName.length() < 2) {
            throw new BadRequestException("Full name must be at least 2 characters.");
        }
        if (newName.length() > 100) {
            throw new BadRequestException("Full name must be at most 100 characters.");
        }

        user.setFullName(newName);
        userRepository.save(user);

        // Return the freshly built DTO (also recomputes stats)
        return getCandidateProfile(email);
    }
}