package com.example.backend.candidate.application.storage;

import com.example.backend.candidate.CandidateTestDocuments;
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

class CandidateResumeStorageServiceTest {
    @TempDir Path directory;

    @Test
    void storesPdfAndDocxUnderRandomPrivateContainedNamesDespiteMaliciousFilenames() throws Exception {
        CandidateResumeStorageService storage = new CandidateResumeStorageService(directory.toString(), 1_000_000, "pdf,docx");
        var pdf = storage.validate(new MockMultipartFile("file", "../../résumé<script>.PDF", "application/pdf",
                CandidateTestDocuments.pdf("Candidate")));
        var docx = storage.validate(new MockMultipartFile("file", "C:\\temp\\resume.docx",
                ResumeDocumentType.DOCX.contentType(), CandidateTestDocuments.docx("Candidate")));
        String pdfName = storage.store(pdf);
        String docxName = storage.store(docx);
        assertTrue(pdfName.matches("[0-9a-f-]{36}\\.pdf"));
        assertTrue(docxName.matches("[0-9a-f-]{36}\\.docx"));
        assertFalse(pdf.displayFilename().contains("<"));
        assertTrue(Files.isRegularFile(directory.resolve("candidate-profiles").resolve(pdfName)));
        assertArrayEquals(docx.bytes(), storage.read(docxName));
    }

    @Test
    void rejectsEmptyOversizeExtensionMimeAndSignatureMismatches() throws Exception {
        byte[] pdf = CandidateTestDocuments.pdf("Candidate");
        CandidateResumeStorageService storage = new CandidateResumeStorageService(directory.toString(), 1_000_000, "pdf,docx");
        assertThrows(BadRequestException.class, () -> storage.validate(new MockMultipartFile("file", new byte[0])));
        assertThrows(ResumeTooLargeException.class, () -> new CandidateResumeStorageService(directory.toString(), 1024, "pdf")
                .validate(new MockMultipartFile("file", "resume.pdf", "application/pdf", new byte[1025])));
        assertThrows(UnsupportedResumeTypeException.class, () -> storage.validate(
                new MockMultipartFile("file", "resume.docx", "application/pdf", pdf)));
        assertThrows(UnsupportedResumeTypeException.class, () -> storage.validate(
                new MockMultipartFile("file", "resume.pdf", "text/plain", pdf)));
        assertThrows(UnsupportedResumeTypeException.class, () -> storage.validate(
                new MockMultipartFile("file", "resume.pdf", "application/pdf", "not-a-document".getBytes())));
    }

    @Test
    void rejectsTraversalMissingFilesAndSymlinksAndDeletesStoredFiles() throws Exception {
        CandidateResumeStorageService storage = new CandidateResumeStorageService(directory.toString(), 1_000_000, "pdf");
        assertThrows(ResourceNotFoundException.class, () -> storage.resolve("../resume.pdf"));
        assertThrows(ResourceNotFoundException.class, () -> storage.resolve("00000000-0000-0000-0000-000000000000.pdf"));
        Path namespace = directory.resolve("candidate-profiles"); Files.createDirectories(namespace);
        Path target = namespace.resolve("target.pdf"); Files.writeString(target, "data");
        Path link = namespace.resolve("11111111-1111-1111-1111-111111111111.pdf"); Files.createSymbolicLink(link, target);
        assertThrows(ResourceNotFoundException.class, () -> storage.resolve(link.getFileName().toString()));

        ValidatedResumeFile valid = storage.validate(new MockMultipartFile("file", "resume.pdf", "application/pdf",
                CandidateTestDocuments.pdf("Candidate")));
        String stored = storage.store(valid); storage.delete(stored);
        assertThrows(ResourceNotFoundException.class, () -> storage.resolve(stored));
    }
}
