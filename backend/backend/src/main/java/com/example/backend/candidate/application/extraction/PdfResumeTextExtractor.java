package com.example.backend.candidate.application.extraction;

import com.example.backend.candidate.application.storage.ResumeDocumentType;
import com.example.backend.shared.error.ResumeParsingException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class PdfResumeTextExtractor implements ResumeTextExtractor {
    @Override public ResumeDocumentType type() { return ResumeDocumentType.PDF; }

    @Override
    public ResumeTextExtraction extract(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()) throw new ResumeParsingException("Password-protected PDF resumes are not supported");
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setLineSeparator("\n");
            return new ResumeTextExtraction(stripper.getText(document).replace('\u0000', ' ').strip(), document.getNumberOfPages());
        } catch (ResumeParsingException ex) { throw ex; }
        catch (IOException | RuntimeException ex) { throw new ResumeParsingException("The PDF is corrupted or cannot be read", ex); }
    }
}
