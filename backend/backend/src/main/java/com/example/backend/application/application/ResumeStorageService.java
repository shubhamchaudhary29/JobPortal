package com.example.backend.application.application;

import com.example.backend.shared.error.BadRequestException;
import com.example.backend.shared.error.ResourceNotFoundException;
import com.example.backend.shared.error.ResumeTooLargeException;
import com.example.backend.shared.error.UnsupportedResumeTypeException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.UUID;

@Service
public class ResumeStorageService {
    private final Path directory;
    private final long maxBytes;

    public ResumeStorageService(@Value("${app.upload-dir:uploads}") String directory,
                                @Value("${app.resume.max-bytes:5242880}") long maxBytes) {
        this.directory = Paths.get(directory).toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
    }

    public String store(MultipartFile file) throws IOException {
        validate(file);
        Files.createDirectories(directory);
        String name = UUID.randomUUID() + ".pdf";
        Path destination = directory.resolve(name).normalize();
        if (!destination.startsWith(directory)) throw new BadRequestException("Invalid upload destination");
        Files.copy(file.getInputStream(), destination);
        return name;
    }

    public Path resolve(String storedName) {
        if (storedName == null || storedName.isBlank()) throw new ResourceNotFoundException("Resume not found");
        if (!storedName.matches("[0-9a-fA-F-]{36}\\.pdf"))
            throw new ResourceNotFoundException("Resume not found");
        Path fileName = Paths.get(storedName).getFileName();
        if (fileName == null) throw new ResourceNotFoundException("Resume not found");
        Path resume = directory.resolve(fileName).normalize();
        if (!resume.startsWith(directory) || Files.isSymbolicLink(resume)
                || !Files.isRegularFile(resume, LinkOption.NOFOLLOW_LINKS))
            throw new ResourceNotFoundException("Resume not found");
        return resume;
    }

    public void deleteQuietly(String storedName) {
        if (storedName == null) return;
        try {
            Path file = directory.resolve(Paths.get(storedName).getFileName()).normalize();
            if (file.startsWith(directory)) Files.deleteIfExists(file);
        } catch (IOException ignored) { }
    }

    private void validate(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) throw new BadRequestException("Resume must not be empty");
        if (file.getSize() > maxBytes) throw new ResumeTooLargeException();
        if (!MediaType.APPLICATION_PDF_VALUE.equalsIgnoreCase(file.getContentType()))
            throw new UnsupportedResumeTypeException("Resume must be a PDF");
        byte[] bytes = file.getBytes();
        byte[] header = "%PDF-".getBytes(StandardCharsets.US_ASCII);
        if (bytes.length < 10 || !Arrays.equals(Arrays.copyOf(bytes, header.length), header))
            throw new UnsupportedResumeTypeException("Resume content is not a valid PDF");
        String tail = new String(bytes, Math.max(0, bytes.length - 1024), Math.min(1024, bytes.length),
                StandardCharsets.ISO_8859_1);
        if (!tail.contains("%%EOF")) throw new UnsupportedResumeTypeException("Resume content is not a complete PDF");
    }
}
