package com.example.backend.shared.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ValidExternalUrlValidator implements ConstraintValidator<ValidExternalUrl, String> {
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || value.isBlank() || SafeExternalUrl.parse(value).isPresent();
    }
}
