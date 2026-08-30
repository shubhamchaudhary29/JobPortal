package com.example.backend.shared.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidExternalUrlValidator.class)
public @interface ValidExternalUrl {
    String message() default "must be an absolute HTTP or HTTPS URL without embedded credentials";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
