package com.example.backend.shared.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String PROBLEM_BASE = "https://jobportal.example/problems/";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().stream().sorted((a, b) -> a.getField().compareTo(b.getField()))
                .forEach(error -> fields.putIfAbsent(error.getField(), safeValidationMessage(error.getDefaultMessage())));
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation-error", "Validation failed",
                "One or more fields are invalid", "VALIDATION_ERROR", request);
        problem.setProperty("fieldErrors", fields);
        return response(problem);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> malformed(Exception ex, HttpServletRequest request) {
        return response(problem(HttpStatus.BAD_REQUEST, "malformed-request", "Malformed request",
                "The request body or parameter value is malformed", "MALFORMED_REQUEST", request));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ProblemDetail> missingParameter(MissingServletRequestParameterException ex, HttpServletRequest request) {
        return response(problem(HttpStatus.BAD_REQUEST, "missing-parameter", "Missing parameter",
                "A required request parameter is missing", "MISSING_PARAMETER", request));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> unsupportedMethod(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return response(problem(HttpStatus.METHOD_NOT_ALLOWED, "method-not-allowed", "Method not allowed",
                "The HTTP method is not supported for this resource", "METHOD_NOT_ALLOWED", request));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> unsupportedMedia(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return response(problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported-media-type", "Unsupported media type",
                "The request media type is not supported", "UNSUPPORTED_MEDIA_TYPE", request));
    }

    @ExceptionHandler({MaxUploadSizeExceededException.class, ResumeTooLargeException.class})
    ResponseEntity<ProblemDetail> payloadTooLarge(Exception ex, HttpServletRequest request) {
        return response(problem(HttpStatus.PAYLOAD_TOO_LARGE, "payload-too-large", "Payload too large",
                "The uploaded resume exceeds the configured limit", "RESUME_TOO_LARGE", request));
    }

    @ExceptionHandler(UnsupportedResumeTypeException.class)
    ResponseEntity<ProblemDetail> unsupportedResume(UnsupportedResumeTypeException ex, HttpServletRequest request) {
        return response(problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "invalid-resume-type", "Invalid resume type",
                ex.getMessage(), "INVALID_RESUME_TYPE", request));
    }

    @ExceptionHandler(UnauthorizedException.class)
    ResponseEntity<ProblemDetail> unauthorized(UnauthorizedException ex, HttpServletRequest request) {
        return response(problem(HttpStatus.UNAUTHORIZED, "invalid-credentials", "Unauthorized",
                "Invalid credentials", "INVALID_CREDENTIALS", request));
    }

    @ExceptionHandler({ForbiddenException.class, AccessDeniedException.class})
    ResponseEntity<ProblemDetail> forbidden(Exception ex, HttpServletRequest request) {
        return response(problem(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                "You are not authorized to perform this operation", "FORBIDDEN", request));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(ResourceNotFoundException ex, HttpServletRequest request) {
        return response(problem(HttpStatus.NOT_FOUND, "resource-not-found", "Resource not found",
                "The requested resource was not found", "RESOURCE_NOT_FOUND", request));
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ProblemDetail> conflict(ConflictException ex, HttpServletRequest request) {
        return response(problem(HttpStatus.CONFLICT, "conflict", "Conflict", ex.getMessage(),
                conflictCode(ex.getMessage()), request));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ProblemDetail> duplicate(DuplicateKeyException ex, HttpServletRequest request) {
        return response(problem(HttpStatus.CONFLICT, "duplicate-resource", "Conflict",
                "The resource already exists", "DUPLICATE_RESOURCE", request));
    }

    @ExceptionHandler(RateLimitException.class)
    ResponseEntity<ProblemDetail> rateLimited(RateLimitException ex, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.TOO_MANY_REQUESTS, "rate-limit", "Too many requests",
                "Too many authentication attempts", "RATE_LIMITED", request);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(ex.getRetryAfterSeconds())).body(problem);
    }

    @ExceptionHandler(BadRequestException.class)
    ResponseEntity<ProblemDetail> badRequest(BadRequestException ex, HttpServletRequest request) {
        return response(problem(HttpStatus.BAD_REQUEST, "bad-request", "Bad request", ex.getMessage(),
                "BAD_REQUEST", request));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception ex, HttpServletRequest request) {
        log.error("Unexpected request failure: {}", ex.getClass().getSimpleName());
        return response(problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal server error",
                "An unexpected error occurred", "INTERNAL_ERROR", request));
    }

    private ProblemDetail problem(HttpStatus status, String type, String title, String detail, String code,
                                  HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(PROBLEM_BASE + type));
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        return problem;
    }

    private ResponseEntity<ProblemDetail> response(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    private String safeValidationMessage(String message) {
        if (message == null || message.isBlank()) return "is invalid";
        return message.length() > 160 ? "is invalid" : message;
    }

    private String conflictCode(String message) {
        if (message != null && message.toLowerCase().contains("email")) return "DUPLICATE_EMAIL";
        if (message != null && message.toLowerCase().contains("application already")) return "DUPLICATE_APPLICATION";
        if (message != null && message.toLowerCase().contains("status")) return "INVALID_STATUS_TRANSITION";
        return "CONFLICT";
    }
}
