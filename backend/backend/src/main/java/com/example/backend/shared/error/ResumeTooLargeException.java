package com.example.backend.shared.error;

public class ResumeTooLargeException extends RuntimeException {
    public ResumeTooLargeException() { super("Resume exceeds maximum size"); }
}
