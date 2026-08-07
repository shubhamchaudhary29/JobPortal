package com.example.backend.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;

@Component
public class ProblemAccessDeniedHandler implements AccessDeniedHandler {
    private final ObjectMapper json;
    public ProblemAccessDeniedHandler(ObjectMapper json) { this.json = json; }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                "You are not authorized to perform this operation");
        problem.setTitle("Forbidden");
        problem.setType(URI.create("https://jobportal.example/problems/forbidden"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "FORBIDDEN");
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), problem);
    }
}
