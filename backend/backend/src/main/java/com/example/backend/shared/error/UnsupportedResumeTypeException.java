package com.example.backend.shared.error;

public class UnsupportedResumeTypeException extends RuntimeException {
    public UnsupportedResumeTypeException(String message) { super(message); }
}
