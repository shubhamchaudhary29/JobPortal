package com.example.backend.user.api;

import com.example.backend.shared.security.CurrentUserProvider;
import com.example.backend.user.api.dto.UpdateProfileRequest;
import com.example.backend.user.api.dto.UserResponse;
import com.example.backend.user.application.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users")
@SecurityRequirement(name = "bearerAuth")
public class UserController {
    private final UserService users;
    private final CurrentUserProvider currentUser;

    public UserController(UserService users, CurrentUserProvider currentUser) {
        this.users = users;
        this.currentUser = currentUser;
    }

    @GetMapping("/me")
    @Operation(summary = "Get the authenticated user profile")
    public UserResponse me() { return users.getProfile(currentUser.email()); }

    @PutMapping("/me")
    @Operation(summary = "Update the authenticated user's editable profile")
    public ResponseEntity<UserResponse> update(@Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(users.updateProfile(currentUser.email(), request));
    }
}
