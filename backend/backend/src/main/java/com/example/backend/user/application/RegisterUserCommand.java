package com.example.backend.user.application;

public record RegisterUserCommand(String fullName, String email, String password) { }
