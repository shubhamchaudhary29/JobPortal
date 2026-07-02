package com.example.backend.controller;

import com.example.backend.dto.UpdateProfileRequest;
import com.example.backend.dto.UserProfileDTO;
import com.example.backend.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/users")
public class UserController {

    @Autowired
    private UserService userService;

    /**
     * GET /users/me
     * Returns the current authenticated user's profile with application stats.
     */
    @GetMapping("/me")
    public ResponseEntity<UserProfileDTO> getMyProfile(Authentication authentication) {
        String email = authentication.getName();
        UserProfileDTO profile = userService.getCandidateProfile(email);
        return ResponseEntity.ok(profile);
    }

    /**
     * PUT /users/me
     * Updates the current authenticated user's editable profile fields.
     * Body: { "fullName": "New Name" }
     */
    @PutMapping("/me")
    public ResponseEntity<UserProfileDTO> updateMyProfile(
            @RequestBody UpdateProfileRequest request,
            Authentication authentication) {
        String email = authentication.getName();
        UserProfileDTO updated = userService.updateCandidateProfile(email, request);
        return ResponseEntity.ok(updated);
    }
}
