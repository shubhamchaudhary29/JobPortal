package com.example.backend.candidate.api;

import com.example.backend.candidate.api.dto.CandidateProfileResponse;
import com.example.backend.candidate.api.dto.ResumeStatusResponse;
import com.example.backend.candidate.api.dto.UpdateCandidateProfileRequest;
import com.example.backend.candidate.application.CandidateProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1/candidate-profile")
@Tag(name = "Candidate Intelligence", description = "Self-service structured candidate profile, resume parsing, and heuristic quality analysis")
@SecurityRequirement(name = "bearerAuth")
public class CandidateProfileController {
    private final CandidateProfileService profiles;
    public CandidateProfileController(CandidateProfileService profiles) { this.profiles = profiles; }

    @GetMapping
    @Operation(summary = "Get or initialize the authenticated candidate profile")
    public CandidateProfileResponse current() { return profiles.current(); }

    @PutMapping
    @Operation(summary = "Replace authenticated candidate-editable profile fields",
            description = "Identity, ownership, resume metadata, parser state, and quality results are server-controlled")
    public CandidateProfileResponse update(@Valid @org.springframework.web.bind.annotation.RequestBody UpdateCandidateProfileRequest request) {
        return profiles.update(request);
    }

    @PostMapping(value = "/resume", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload, extract, parse, normalize, and privately store a PDF or DOCX resume",
            requestBody = @RequestBody(content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                    schema = @Schema(type = "object"))))
    public CandidateProfileResponse upload(@RequestPart("file") MultipartFile file) throws IOException {
        return profiles.upload(file);
    }

    @GetMapping("/resume/status")
    @Operation(summary = "Get resume parsing state, warnings, and quality analysis")
    public ResumeStatusResponse status() { return profiles.status(); }

    @PostMapping("/resume/reparse")
    @Operation(summary = "Reparse the authenticated candidate's currently stored resume")
    public CandidateProfileResponse reparse() throws IOException { return profiles.reparse(); }

    @GetMapping("/resume")
    @Operation(summary = "Download the authenticated candidate's private resume")
    public ResponseEntity<Resource> download() throws IOException {
        var resume = profiles.download();
        MediaType contentType = MediaType.parseMediaType(resume.contentType());
        ContentDisposition disposition = ContentDisposition.attachment().filename(
                resume.filename() == null ? "resume" : resume.filename(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().contentType(contentType).contentLength(resume.contentLength())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(resume.resource());
    }

    @DeleteMapping("/resume")
    @Operation(summary = "Delete the stored resume while retaining editable structured profile data")
    public ResponseEntity<Void> delete() throws IOException {
        profiles.deleteResume();
        return ResponseEntity.noContent().build();
    }
}
