package com.example.backend.candidate.application;

import com.example.backend.candidate.CandidateProfileMapper;
import com.example.backend.candidate.api.dto.CandidateProfileResponse;
import com.example.backend.candidate.api.dto.ResumeStatusResponse;
import com.example.backend.candidate.api.dto.UpdateCandidateProfileRequest;
import com.example.backend.candidate.application.extraction.ResumeTextExtraction;
import com.example.backend.candidate.application.extraction.ResumeTextExtractionService;
import com.example.backend.candidate.application.parsing.CandidateResumeParser;
import com.example.backend.candidate.application.parsing.ParsedResume;
import com.example.backend.candidate.application.storage.CandidateResumeStorageService;
import com.example.backend.candidate.application.storage.ResumeDocumentType;
import com.example.backend.candidate.application.storage.ValidatedResumeFile;
import com.example.backend.candidate.domain.ResumeParsingStatus;
import com.example.backend.candidate.infrastructure.CandidateProfileDocument;
import com.example.backend.candidate.infrastructure.CandidateProfileDocument.ProfessionalLinks;
import com.example.backend.candidate.infrastructure.CandidateProfileDocument.ResumeMetadata;
import com.example.backend.candidate.infrastructure.CandidateProfileRepository;
import com.example.backend.shared.error.ForbiddenException;
import com.example.backend.shared.error.ResumeParsingException;
import com.example.backend.shared.error.ResourceNotFoundException;
import com.example.backend.shared.security.CurrentUserProvider;
import com.example.backend.user.domain.UserRole;
import com.example.backend.user.infrastructure.UserDocument;
import com.example.backend.user.infrastructure.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

@Service
public class CandidateProfileService {
    private static final Logger log = LoggerFactory.getLogger(CandidateProfileService.class);
    private final CandidateProfileRepository profiles;
    private final UserRepository users;
    private final CurrentUserProvider currentUser;
    private final CandidateResumeStorageService storage;
    private final ResumeTextExtractionService extraction;
    private final CandidateResumeParser parser;
    private final CandidateProfileMapper mapper;
    private final ResumeQualityAnalyzer quality;
    private final CandidateIntelligenceMetrics metrics;

    public CandidateProfileService(CandidateProfileRepository profiles, UserRepository users,
                                   CurrentUserProvider currentUser, CandidateResumeStorageService storage,
                                   ResumeTextExtractionService extraction, CandidateResumeParser parser,
                                   CandidateProfileMapper mapper, ResumeQualityAnalyzer quality,
                                   CandidateIntelligenceMetrics metrics) {
        this.profiles = profiles;
        this.users = users;
        this.currentUser = currentUser;
        this.storage = storage;
        this.extraction = extraction;
        this.parser = parser;
        this.mapper = mapper;
        this.quality = quality;
        this.metrics = metrics;
    }

    public CandidateProfileResponse current() {
        UserDocument user = candidate();
        return mapper.toResponse(user, profile(user));
    }

    public CandidateProfileResponse update(UpdateCandidateProfileRequest request) {
        UserDocument user = candidate();
        CandidateProfileDocument profile = profile(user);
        mapper.applyUpdate(profile, request);
        user.setFullName(request.fullName().strip());
        users.save(user);
        touch(profile);
        return mapper.toResponse(user, profiles.save(profile));
    }

    public CandidateProfileResponse upload(MultipartFile file) throws IOException {
        UserDocument user = candidate();
        CandidateProfileDocument profile = profile(user);
        try {
            ValidatedResumeFile resume = storage.validate(file);
            ResumeMetadata existing = metadata(profile);
            if (resume.sha256().equals(existing.getSha256()) && existing.getStoredName() != null) {
                return mapper.toResponse(user, profile);
            }
            ResumeTextExtraction text = extraction.extract(resume.bytes(), resume.type());
            CandidateProfileDocument updated = applyExtraction(profile, user, text);
            String oldStoredName = existing.getStoredName();
            String newStoredName = storage.store(resume);
            try {
                updated.setResume(new ResumeMetadata(newStoredName, resume.displayFilename(), resume.type().contentType(),
                        resume.bytes().length, resume.sha256(), Instant.now(), statusFor(text, updated.getParsingWarnings()),
                        CandidateResumeParser.PARSER_VERSION, null, null));
                touch(updated);
                CandidateProfileDocument saved = profiles.save(updated);
                if (oldStoredName != null && !oldStoredName.equals(newStoredName)) storage.deleteQuietly(oldStoredName);
                metrics.uploadAccepted();
                metrics.parsed(saved.getResume().getParsingStatus());
                log.info("Candidate resume processing completed with status={}", saved.getResume().getParsingStatus());
                return mapper.toResponse(user, saved);
            } catch (RuntimeException | Error failure) {
                storage.deleteQuietly(newStoredName);
                throw failure;
            }
        } catch (IOException | RuntimeException failure) {
            metrics.uploadRejected();
            log.info("Candidate resume processing rejected with reason={}", failure.getClass().getSimpleName());
            throw failure;
        }
    }

    public CandidateProfileResponse reparse() throws IOException {
        UserDocument user = candidate();
        CandidateProfileDocument profile = profile(user);
        ResumeMetadata resume = metadata(profile);
        if (resume.getStoredName() == null) throw new ResourceNotFoundException("Resume not found");
        try {
            byte[] bytes = storage.read(resume.getStoredName());
            ResumeDocumentType type = resume.getStoredName().endsWith(".docx") ? ResumeDocumentType.DOCX : ResumeDocumentType.PDF;
            ResumeTextExtraction text = extraction.extract(bytes, type);
            applyExtraction(profile, user, text);
            resume.setParsingStatus(statusFor(text, profile.getParsingWarnings()));
            resume.setParserVersion(CandidateResumeParser.PARSER_VERSION);
            resume.setErrorCode(null);
            resume.setErrorMessage(null);
            touch(profile);
            CandidateProfileDocument saved = profiles.save(profile);
            metrics.parsed(resume.getParsingStatus());
            return mapper.toResponse(user, saved);
        } catch (ResumeParsingException failure) {
            resume.setParsingStatus(ResumeParsingStatus.FAILED);
            resume.setParserVersion(CandidateResumeParser.PARSER_VERSION);
            resume.setErrorCode("RESUME_PARSE_FAILED");
            resume.setErrorMessage(failure.getMessage());
            profile.setParsingWarnings(List.of("The stored resume could not be parsed. Replace it with a valid document."));
            touch(profile);
            profiles.save(profile);
            metrics.parsed(ResumeParsingStatus.FAILED);
            log.info("Candidate resume reparse failed with reason={}", failure.getClass().getSimpleName());
            return mapper.toResponse(user, profile);
        }
    }

    public ResumeStatusResponse status() {
        UserDocument user = candidate();
        CandidateProfileDocument profile = profile(user);
        ResumeMetadata resume = metadata(profile);
        return new ResumeStatusResponse(resume.getParsingStatus(), resume.getFilename(), resume.getUploadedAt(),
                resume.getParserVersion(), resume.getErrorCode(), resume.getErrorMessage(),
                List.copyOf(profile.getParsingWarnings()), quality.analyze(profile, user.getEmail()));
    }

    public ResumeDownload download() throws IOException {
        CandidateProfileDocument profile = profile(candidate());
        ResumeMetadata resume = metadata(profile);
        var path = storage.resolve(resume.getStoredName());
        return new ResumeDownload(new InputStreamResource(Files.newInputStream(path)), Files.size(path),
                resume.getContentType(), resume.getFilename());
    }

    public void deleteResume() throws IOException {
        CandidateProfileDocument profile = profile(candidate());
        ResumeMetadata resume = metadata(profile);
        storage.delete(resume.getStoredName());
        profile.setResume(new ResumeMetadata());
        profile.getResume().setParsingStatus(ResumeParsingStatus.NOT_UPLOADED);
        profile.setParsingWarnings(new ArrayList<>());
        profile.setExtractedTextLength(0);
        profile.setExtractedPageCount(0);
        profile.setSpecialCharacterRatio(0);
        touch(profile);
        profiles.save(profile);
    }

    private CandidateProfileDocument applyExtraction(CandidateProfileDocument profile, UserDocument user,
                                                      ResumeTextExtraction text) {
        profile.setExtractedTextLength(text.text().length());
        profile.setExtractedPageCount(text.pageCount());
        profile.setSpecialCharacterRatio(specialCharacterRatio(text.text()));
        if (text.text().strip().length() < 20) {
            profile.setParsingWarnings(new ArrayList<>(List.of(
                    "Too little selectable text was extracted. The document may be image-only and require OCR.")));
            return profile;
        }
        ParsedResume parsed = parser.parse(text.text());
        if (notBlank(parsed.phone())) profile.setPhone(parsed.phone());
        if (notBlank(parsed.location())) profile.setLocation(parsed.location());
        if (notBlank(parsed.professionalSummary())) profile.setProfessionalSummary(parsed.professionalSummary());
        if (!parsed.skills().isEmpty()) profile.setSkills(parsed.skills());
        if (!parsed.education().isEmpty()) profile.setEducation(parsed.education());
        if (!parsed.experience().isEmpty()) profile.setExperience(parsed.experience());
        if (!parsed.projects().isEmpty()) profile.setProjects(parsed.projects());
        if (!parsed.certifications().isEmpty()) profile.setCertifications(parsed.certifications());
        profile.setLinks(mergeLinks(profile.getLinks(), parsed.links()));
        LinkedHashSet<String> warnings = new LinkedHashSet<>(parsed.warnings());
        if (notBlank(parsed.detectedEmail()) && !parsed.detectedEmail().equalsIgnoreCase(user.getEmail()))
            warnings.add("The resume email differs from the authenticated account email; the account email was retained.");
        if (notBlank(parsed.detectedFullName()) && !parsed.detectedFullName().equalsIgnoreCase(user.getFullName()))
            warnings.add("The detected resume name differs from the account name; review the profile name before saving.");
        profile.setParsingWarnings(new ArrayList<>(warnings));
        return profile;
    }

    private ProfessionalLinks mergeLinks(ProfessionalLinks current, ProfessionalLinks parsed) {
        ProfessionalLinks result = current == null ? new ProfessionalLinks() : current;
        if (parsed == null) return result;
        if (notBlank(parsed.getLinkedIn())) result.setLinkedIn(parsed.getLinkedIn());
        if (notBlank(parsed.getGithub())) result.setGithub(parsed.getGithub());
        if (notBlank(parsed.getPortfolio())) result.setPortfolio(parsed.getPortfolio());
        if (notBlank(parsed.getWebsite())) result.setWebsite(parsed.getWebsite());
        LinkedHashSet<String> other = new LinkedHashSet<>(result.getOther() == null ? List.of() : result.getOther());
        if (parsed.getOther() != null) other.addAll(parsed.getOther());
        result.setOther(new ArrayList<>(other));
        return result;
    }

    private ResumeParsingStatus statusFor(ResumeTextExtraction text, List<String> warnings) {
        if (text.text().strip().length() < 20) return ResumeParsingStatus.OCR_REQUIRED;
        return warnings == null || warnings.isEmpty() ? ResumeParsingStatus.PARSED : ResumeParsingStatus.PARTIALLY_PARSED;
    }

    private UserDocument candidate() {
        UserDocument user = users.findByEmail(currentUser.email().strip().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (user.getRole() != UserRole.USER) throw new ForbiddenException("Only candidates can manage candidate profiles");
        return user;
    }

    private CandidateProfileDocument profile(UserDocument user) {
        return profiles.findByUserId(user.getId()).map(this::defaults).orElseGet(() -> create(user));
    }

    private CandidateProfileDocument create(UserDocument user) {
        CandidateProfileDocument profile = new CandidateProfileDocument();
        profile.setUserId(user.getId());
        try { return profiles.save(profile); }
        catch (DuplicateKeyException race) {
            return profiles.findByUserId(user.getId()).orElseThrow(() -> race);
        }
    }

    private CandidateProfileDocument defaults(CandidateProfileDocument profile) {
        if (profile.getResume() == null) profile.setResume(new ResumeMetadata());
        if (profile.getResume().getParsingStatus() == null) profile.getResume().setParsingStatus(ResumeParsingStatus.NOT_UPLOADED);
        if (profile.getSkills() == null) profile.setSkills(new ArrayList<>());
        if (profile.getEducation() == null) profile.setEducation(new ArrayList<>());
        if (profile.getExperience() == null) profile.setExperience(new ArrayList<>());
        if (profile.getProjects() == null) profile.setProjects(new ArrayList<>());
        if (profile.getCertifications() == null) profile.setCertifications(new ArrayList<>());
        if (profile.getLinks() == null) profile.setLinks(new ProfessionalLinks());
        if (profile.getPreferences() == null) profile.setPreferences(new CandidateProfileDocument.JobPreferences());
        if (profile.getParsingWarnings() == null) profile.setParsingWarnings(new ArrayList<>());
        if (profile.getCreatedAt() == null) profile.setCreatedAt(Instant.now());
        return profile;
    }

    private ResumeMetadata metadata(CandidateProfileDocument profile) {
        if (profile.getResume() == null) profile.setResume(new ResumeMetadata());
        if (profile.getResume().getParsingStatus() == null) profile.getResume().setParsingStatus(ResumeParsingStatus.NOT_UPLOADED);
        return profile.getResume();
    }

    private double specialCharacterRatio(String text) {
        long visible = text.codePoints().filter(code -> !Character.isWhitespace(code)).count();
        if (visible == 0) return 0;
        long special = text.codePoints().filter(code -> !Character.isWhitespace(code) && !Character.isLetterOrDigit(code)
                && ".,;:'\"!?@+%&/()[]{}#-_₹$€£".indexOf(code) < 0).count();
        return (double) special / visible;
    }

    private void touch(CandidateProfileDocument profile) { profile.setUpdatedAt(Instant.now()); }
    private boolean notBlank(String value) { return value != null && !value.isBlank(); }

    public record ResumeDownload(Resource resource, long contentLength, String contentType, String filename) { }
}
