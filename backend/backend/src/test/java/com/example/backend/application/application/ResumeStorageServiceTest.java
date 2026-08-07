package com.example.backend.application.application;

import com.example.backend.shared.error.BadRequestException;
import com.example.backend.shared.error.ResourceNotFoundException;
import com.example.backend.shared.error.ResumeTooLargeException;
import com.example.backend.shared.error.UnsupportedResumeTypeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ResumeStorageServiceTest {
    @TempDir Path directory;

    @Test
    void validPdfUsesRandomContainedNameAndCannotOverwriteByClientFilename() throws Exception {
        ResumeStorageService storage = new ResumeStorageService(directory.toString(), 1024);
        byte[] pdf = "%PDF-1.4\nbody\n%%EOF".getBytes();
        String first = storage.store(new MockMultipartFile("file", "../../resume.pdf", "application/pdf", pdf));
        String second = storage.store(new MockMultipartFile("file", "/tmp/resume.pdf", "application/pdf", pdf));
        assertNotEquals(first, second);
        assertTrue(first.matches("[0-9a-f-]{36}\\.pdf"));
        assertTrue(Files.isRegularFile(directory.resolve(first)));
        assertTrue(Files.isRegularFile(directory.resolve(second)));
    }

    @Test
    void rejectsEmptyOversizedFalseMimeAndMalformedPdf() {
        ResumeStorageService storage = new ResumeStorageService(directory.toString(), 20);
        assertThrows(BadRequestException.class, () -> storage.store(new MockMultipartFile("file", new byte[0])));
        assertThrows(ResumeTooLargeException.class, () -> storage.store(
                new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[21])));
        assertThrows(UnsupportedResumeTypeException.class, () -> storage.store(
                new MockMultipartFile("file", "a.pdf", "text/plain", "%PDF-1.4%%EOF".getBytes())));
        assertThrows(UnsupportedResumeTypeException.class, () -> storage.store(
                new MockMultipartFile("file", "a.pdf", "application/pdf", "not pdf content".getBytes())));
    }

    @Test
    void resolutionRejectsTraversalMissingFilesAndSymbolicLinks() throws Exception {
        ResumeStorageService storage = new ResumeStorageService(directory.toString(), 1024);
        assertThrows(ResourceNotFoundException.class, () -> storage.resolve("../outside.pdf"));
        assertThrows(ResourceNotFoundException.class, () -> storage.resolve("00000000-0000-0000-0000-000000000000.pdf"));
        Path target = directory.resolve("target.pdf");
        Files.writeString(target, "pdf");
        Path link = directory.resolve("11111111-1111-1111-1111-111111111111.pdf");
        Files.createSymbolicLink(link, target);
        assertThrows(ResourceNotFoundException.class, () -> storage.resolve(link.getFileName().toString()));
    }
}
