package com.example.backend.candidate.application.storage;

import com.example.backend.shared.error.BadRequestException;
import com.example.backend.shared.error.ResourceNotFoundException;
import com.example.backend.shared.error.ResumeTooLargeException;
import com.example.backend.shared.error.UnsupportedResumeTypeException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class CandidateResumeStorageService {
    private static final Set<String> GENERIC_MIME = Set.of("application/octet-stream", "application/zip");
    private final Path directory;
    private final long maxBytes;
    private final Set<String> allowedFormats;

    public CandidateResumeStorageService(@Value("${app.upload-dir:uploads}") String root,
                                         @Value("${app.candidate-resume.max-bytes:5242880}") long maxBytes,
                                         @Value("${app.candidate-resume.allowed-formats:pdf,docx}") String allowedFormats) {
        if (maxBytes < 1024 || maxBytes > 20L * 1024 * 1024) throw new IllegalArgumentException("Candidate resume size limit must be between 1KB and 20MB");
        this.directory = Paths.get(root).toAbsolutePath().normalize().resolve("candidate-profiles");
        this.maxBytes = maxBytes;
        this.allowedFormats = Arrays.stream(allowedFormats.split(",")).map(value -> value.strip().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (this.allowedFormats.isEmpty() || !Set.of("pdf", "docx").containsAll(this.allowedFormats))
            throw new IllegalArgumentException("Candidate resume formats must be a subset of pdf,docx");
    }

    public ValidatedResumeFile validate(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) throw new BadRequestException("Resume must not be empty");
        if (file.getSize() > maxBytes) throw new ResumeTooLargeException();
        byte[] bytes = file.getBytes();
        String original = file.getOriginalFilename();
        String extension = extension(original);
        ResumeDocumentType type = detect(bytes);
        if (!allowedFormats.contains(type.extension()) || !extension.equals(type.extension()))
            throw new UnsupportedResumeTypeException("Resume extension and content must be an allowed PDF or DOCX");
        validateMime(file.getContentType(), type);
        return new ValidatedResumeFile(bytes, type, safeDisplayName(original, type), sha256(bytes));
    }

    public String store(ValidatedResumeFile file) throws IOException {
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) throw new IOException("Resume storage directory cannot be a symbolic link");
        String storedName = UUID.randomUUID() + "." + file.type().extension();
        Path destination = directory.resolve(storedName).normalize();
        if (!destination.startsWith(directory)) throw new BadRequestException("Invalid upload destination");
        Path temporary = Files.createTempFile(directory, ".upload-", ".tmp");
        try {
            Files.write(temporary, file.bytes());
            setOwnerOnlyPermissions(temporary);
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temporary, destination); }
            return storedName;
        } finally { Files.deleteIfExists(temporary); }
    }

    public Path resolve(String storedName) {
        if (storedName == null || !storedName.matches("[0-9a-fA-F-]{36}\\.(pdf|docx)"))
            throw new ResourceNotFoundException("Resume not found");
        Path fileName = Paths.get(storedName).getFileName();
        Path resume = directory.resolve(fileName).normalize();
        if (!resume.startsWith(directory) || Files.isSymbolicLink(resume)
                || !Files.isRegularFile(resume, LinkOption.NOFOLLOW_LINKS))
            throw new ResourceNotFoundException("Resume not found");
        return resume;
    }

    public byte[] read(String storedName) throws IOException { return Files.readAllBytes(resolve(storedName)); }

    public void delete(String storedName) throws IOException {
        if (storedName == null) return;
        Path path;
        try { path = resolve(storedName); }
        catch (ResourceNotFoundException missing) { return; }
        Files.deleteIfExists(path);
    }

    public void deleteQuietly(String storedName) {
        try { delete(storedName); } catch (IOException ignored) { }
    }

    private ResumeDocumentType detect(byte[] bytes) {
        byte[] pdf = "%PDF-".getBytes(StandardCharsets.US_ASCII);
        if (bytes.length >= 10 && Arrays.equals(Arrays.copyOf(bytes, pdf.length), pdf)) {
            String tail = new String(bytes, Math.max(0, bytes.length - 2048), Math.min(2048, bytes.length), StandardCharsets.ISO_8859_1);
            if (!tail.contains("%%EOF")) throw new UnsupportedResumeTypeException("Resume content is not a complete PDF");
            return ResumeDocumentType.PDF;
        }
        if (bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K' && (bytes[2] == 3 || bytes[2] == 5 || bytes[2] == 7)
                && (bytes[3] == 4 || bytes[3] == 6 || bytes[3] == 8)) return ResumeDocumentType.DOCX;
        throw new UnsupportedResumeTypeException("Resume content is not a valid PDF or DOCX");
    }

    private void validateMime(String rawMime, ResumeDocumentType type) {
        String mime = rawMime == null ? "" : rawMime.toLowerCase(Locale.ROOT).split(";", 2)[0].strip();
        if (!mime.equals(type.contentType()) && !(type == ResumeDocumentType.DOCX && GENERIC_MIME.contains(mime))
                && !(type == ResumeDocumentType.PDF && mime.equals("application/octet-stream")))
            throw new UnsupportedResumeTypeException("Resume MIME type does not match its content");
    }

    private String extension(String filename) {
        if (filename == null || filename.isBlank()) throw new UnsupportedResumeTypeException("Resume filename must include an allowed extension");
        String base = Paths.get(filename.replace('\\', '/')).getFileName().toString();
        int dot = base.lastIndexOf('.');
        return dot < 0 ? "" : base.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String safeDisplayName(String filename, ResumeDocumentType type) {
        String base = Paths.get(filename.replace('\\', '/')).getFileName().toString();
        String withoutExtension = base.replaceFirst("(?i)\\.(pdf|docx)$", "").replaceAll("[\\p{Cntrl}\\r\\n]", "")
                .replaceAll("[^\\p{L}\\p{N} ._()-]", "_").strip();
        if (withoutExtension.isBlank()) withoutExtension = "resume";
        if (withoutExtension.length() > 100) withoutExtension = withoutExtension.substring(0, 100).strip();
        return withoutExtension + "." + type.extension();
    }

    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private void setOwnerOnlyPermissions(Path file) {
        try { Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)); }
        catch (UnsupportedOperationException | IOException ignored) { }
    }
}
