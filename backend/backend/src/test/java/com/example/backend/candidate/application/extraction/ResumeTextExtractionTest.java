package com.example.backend.candidate.application.extraction;

import com.example.backend.candidate.CandidateTestDocuments;
import com.example.backend.candidate.application.storage.ResumeDocumentType;
import com.example.backend.shared.error.ResumeParsingException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResumeTextExtractionTest {
    private final PdfResumeTextExtractor pdf = new PdfResumeTextExtractor();
    private final DocxResumeTextExtractor docx = new DocxResumeTextExtractor();

    @Test
    void extractsStructuredLinesFromValidPdfAndDocx() throws Exception {
        ResumeTextExtraction pdfText = pdf.extract(CandidateTestDocuments.pdf("Test Candidate", "SKILLS", "Java Spring Boot"));
        ResumeTextExtraction docxText = docx.extract(CandidateTestDocuments.docx("Test Candidate", "PROJECTS", "Portal"));
        assertTrue(pdfText.text().contains("Java Spring Boot"));
        assertEquals(1, pdfText.pageCount());
        assertTrue(docxText.text().contains("PROJECTS\nPortal"));
    }

    @Test
    void blankImageOnlyPdfProducesCleanNoTextSignalAndCorruptionFailsSafely() throws Exception {
        ResumeTextExtraction blank = pdf.extract(CandidateTestDocuments.pdf());
        assertTrue(blank.text().isBlank());
        assertThrows(ResumeParsingException.class, () -> pdf.extract("%PDF-1.4 broken %%EOF".getBytes()));
        assertThrows(ResumeParsingException.class, () -> docx.extract(new byte[]{'P', 'K', 3, 4, 1, 2, 3}));
    }

    @Test
    void dispatcherUsesOnlyTheExtractorForDetectedType() throws Exception {
        ResumeTextExtractionService service = new ResumeTextExtractionService(List.of(pdf, docx));
        assertTrue(service.extract(CandidateTestDocuments.docx("Résumé Unicode ✓"), ResumeDocumentType.DOCX)
                .text().contains("Résumé"));
    }
}
