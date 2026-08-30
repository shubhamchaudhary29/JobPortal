package com.example.backend.candidate.application.extraction;

import com.example.backend.candidate.application.storage.ResumeDocumentType;
import com.example.backend.shared.error.ResumeParsingException;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;

@Component
public class DocxResumeTextExtractor implements ResumeTextExtractor {
    @Override public ResumeDocumentType type() { return ResumeDocumentType.DOCX; }

    @Override
    public ResumeTextExtraction extract(byte[] bytes) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder text = new StringBuilder();
            for (XWPFParagraph paragraph : document.getParagraphs()) append(text, paragraph.getText());
            for (XWPFTable table : document.getTables()) table.getRows().forEach(row -> row.getTableCells()
                    .forEach(cell -> append(text, cell.getText())));
            return new ResumeTextExtraction(text.toString().replace('\u0000', ' ').strip(), 1);
        } catch (IOException | RuntimeException ex) {
            throw new ResumeParsingException("The DOCX is corrupted or cannot be read", ex);
        }
    }

    private void append(StringBuilder target, String value) {
        if (value == null || value.isBlank()) return;
        if (!target.isEmpty()) target.append('\n');
        target.append(value.strip());
    }
}
