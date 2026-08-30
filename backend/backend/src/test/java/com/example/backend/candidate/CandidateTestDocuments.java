package com.example.backend.candidate;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayOutputStream;

public final class CandidateTestDocuments {
    private CandidateTestDocuments() { }

    public static byte[] pdf(String... lines) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(); document.addPage(page);
            if (lines.length > 0) try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                content.newLineAtOffset(50, 740);
                for (String line : lines) { content.showText(line); content.newLineAtOffset(0, -15); }
                content.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    public static byte[] docx(String... lines) throws Exception {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (String line : lines) document.createParagraph().createRun().setText(line);
            document.write(output);
            return output.toByteArray();
        }
    }
}
