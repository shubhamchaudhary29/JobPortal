package com.example.backend.candidate.application;

import com.example.backend.candidate.CandidateProfileMapper;
import com.example.backend.candidate.CandidateTestDocuments;
import com.example.backend.candidate.api.dto.UpdateCandidateProfileRequest;
import com.example.backend.candidate.application.extraction.DocxResumeTextExtractor;
import com.example.backend.candidate.application.extraction.PdfResumeTextExtractor;
import com.example.backend.candidate.application.extraction.ResumeTextExtractionService;
import com.example.backend.candidate.application.parsing.*;
import com.example.backend.candidate.application.storage.CandidateResumeStorageService;
import com.example.backend.candidate.domain.ResumeParsingStatus;
import com.example.backend.candidate.infrastructure.CandidateProfileDocument;
import com.example.backend.candidate.infrastructure.CandidateProfileRepository;
import com.example.backend.shared.error.ForbiddenException;
import com.example.backend.shared.security.CurrentUserProvider;
import com.example.backend.user.domain.UserRole;
import com.example.backend.user.infrastructure.UserDocument;
import com.example.backend.user.infrastructure.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CandidateProfileServiceTest {
    @TempDir Path directory;
    private CandidateProfileRepository profiles;
    private UserRepository users;
    private CurrentUserProvider current;
    private CandidateProfileService service;
    private UserDocument candidate;
    private AtomicReference<CandidateProfileDocument> stored;

    @BeforeEach
    void setUp() {
        profiles = mock(CandidateProfileRepository.class);
        users = mock(UserRepository.class);
        current = mock(CurrentUserProvider.class);
        candidate = new UserDocument("candidate-a", "a@example.test", "Candidate A", "hash", UserRole.USER);
        when(current.email()).thenReturn(candidate.getEmail());
        when(users.findByEmail(candidate.getEmail())).thenReturn(Optional.of(candidate));
        when(users.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        stored = new AtomicReference<>();
        when(profiles.findByUserId(candidate.getId())).thenAnswer(ignored -> Optional.ofNullable(stored.get()));
        when(profiles.save(any())).thenAnswer(invocation -> {
            CandidateProfileDocument value = invocation.getArgument(0); if (value.getId() == null) value.setId("profile-a");
            stored.set(value); return value;
        });

        SkillNormalizer skills = new SkillNormalizer();
        ResumeQualityAnalyzer quality = new ResumeQualityAnalyzer();
        CandidateProfileMapper mapper = new CandidateProfileMapper(skills, quality);
        CandidateResumeParser parser = new CandidateResumeParser(new ResumeSectionDetector(), new ContactInfoExtractor(),
                skills, new EducationParser(), new ExperienceParser(skills), new ProjectParser(skills), new CertificationParser());
        service = new CandidateProfileService(profiles, users, current,
                new CandidateResumeStorageService(directory.toString(), 1_000_000, "pdf,docx"),
                new ResumeTextExtractionService(List.of(new PdfResumeTextExtractor(), new DocxResumeTextExtractor())),
                parser, mapper, quality, new CandidateIntelligenceMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void initializesOnlyTheAuthenticatedUsersCanonicalProfileAndRejectsWrongRoles() {
        assertEquals("candidate-a", service.current().userId());
        assertEquals(ResumeParsingStatus.NOT_UPLOADED, service.current().resume().parsingStatus());
        verify(profiles, times(1)).save(any());

        candidate.setRole(UserRole.RECRUITER);
        assertThrows(ForbiddenException.class, service::current);
        verify(profiles, never()).findByUserId("candidate-b");
    }

    @Test
    void fullProfileUpdateNormalizesDuplicatesSupportsCollectionsAndRejectsCurrentJobEndDate() {
        service.current();
        var request = updateRequest(List.of(new UpdateCandidateProfileRequest.Skill("java", null, null, null, null),
                new UpdateCandidateProfileRequest.Skill("JAVA", null, null, null, null)), false, "2025");
        var response = service.update(request);
        assertEquals("Updated Candidate", response.fullName());
        assertEquals(List.of("Java"), response.skills().stream().map(value -> value.name()).toList());
        assertEquals(1, response.education().size());
        assertEquals(1, response.experience().size());
        assertEquals(1, response.projects().size());
        assertEquals(1, response.certifications().size());
        assertEquals("https://github.com/candidate", response.links().github());

        assertThrows(com.example.backend.shared.error.BadRequestException.class,
                () -> service.update(updateRequest(List.of(), true, "2025")));
    }

    @Test
    void uploadIsIdempotentReplacementSafeAndDeleteRetainsEditableParsedProfile() throws Exception {
        byte[] pdf = CandidateTestDocuments.pdf("Candidate A", "a@example.test", "+91 98765 43210", "SKILLS",
                "Java, Spring Boot, Docker", "PROJECTS", "Portal", "Improved throughput by 30 percent using Java.");
        var first = service.upload(new MockMultipartFile("file", "../resume.pdf", "application/pdf", pdf));
        assertNotEquals(ResumeParsingStatus.NOT_UPLOADED, first.resume().parsingStatus());
        assertTrue(first.skills().stream().anyMatch(value -> value.name().equals("Java")));
        Path namespace = directory.resolve("candidate-profiles");
        assertEquals(1, Files.list(namespace).count());

        service.upload(new MockMultipartFile("file", "same.pdf", "application/pdf", pdf));
        assertEquals(1, Files.list(namespace).count());

        byte[] docx = CandidateTestDocuments.docx("Candidate A", "a@example.test", "SKILLS", "Python, SQL, Git");
        var replaced = service.upload(new MockMultipartFile("file", "replacement.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx));
        assertEquals("replacement.docx", replaced.resume().filename());
        assertEquals(1, Files.list(namespace).count());
        assertTrue(replaced.skills().stream().anyMatch(value -> value.name().equals("Python")));

        service.deleteResume();
        assertEquals(ResumeParsingStatus.NOT_UPLOADED, service.status().status());
        assertFalse(service.current().skills().isEmpty());
        assertEquals(0, Files.list(namespace).count());
    }

    @Test
    void imageOnlyPdfIsStoredAsOcrRequiredWithoutGarbage() throws Exception {
        var response = service.upload(new MockMultipartFile("file", "scan.pdf", "application/pdf",
                CandidateTestDocuments.pdf()));
        assertEquals(ResumeParsingStatus.OCR_REQUIRED, response.resume().parsingStatus());
        assertTrue(response.parsingWarnings().get(0).contains("OCR"));
    }

    private UpdateCandidateProfileRequest updateRequest(List<UpdateCandidateProfileRequest.Skill> skills,
                                                        boolean currentJob, String endDate) {
        return new UpdateCandidateProfileRequest("Updated Candidate", "+91 99999 99999", "Pune, India",
                "Engineer building reliable systems with measurable product outcomes.", skills,
                List.of(new UpdateCandidateProfileRequest.Education("Example University", "B.Tech", "CS", "2018", "2022", "8.5", null)),
                List.of(new UpdateCandidateProfileRequest.Experience("Example", "Engineer", "FULL_TIME", "Pune", "2022", endDate,
                        currentJob, "Reduced latency by 20% across APIs.", List.of("springboot", "java"))),
                List.of(new UpdateCandidateProfileRequest.Project("Portal", "Built a portal", List.of("React"),
                        "https://github.com/candidate/portal", "2021", "2022")),
                List.of(new UpdateCandidateProfileRequest.Certification("AWS Developer", "Amazon", "2024", "https://example.com/credential")),
                new UpdateCandidateProfileRequest.ProfessionalLinks("https://linkedin.com/in/candidate", "https://github.com/candidate", null, null, List.of()),
                new UpdateCandidateProfileRequest.JobPreferences(List.of("Backend Engineer"), List.of("Pune"), "HYBRID", List.of("FULL_TIME"), 1000000L));
    }
}
