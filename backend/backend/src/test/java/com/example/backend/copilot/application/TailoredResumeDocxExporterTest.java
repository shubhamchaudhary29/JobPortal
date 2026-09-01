package com.example.backend.copilot.application;

import com.example.backend.copilot.domain.CopilotModels.JobSnapshot;
import com.example.backend.copilot.domain.CopilotModels.ResumeContent;
import com.example.backend.copilot.domain.CopilotModels.ResumeExperience;
import com.example.backend.copilot.domain.CopilotModels.ResumeLinks;
import com.example.backend.copilot.infrastructure.TailoredResumeVersionDocument;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TailoredResumeDocxExporterTest {
    @Test
    void exportIsValidSelectableOfficeXmlWithTailoredOrderingAndNoMissingClaim() throws Exception {
        TailoredResumeVersionDocument version = new TailoredResumeVersionDocument();
        version.setVersionNumber(2);
        version.setJobSnapshot(new JobSnapshot("job-1", "Backend / Engineer", "Example", null, null, null, null));
        version.setContent(new ResumeContent("Candidate Name", "candidate@example.test", null, "Delhi",
                "Backend engineer", List.of("Java", "Docker"),
                List.of(new ResumeExperience("Acme", "Engineer", null, null, null, null, true,
                        "Reduced latency by 20%", List.of("Java"))), List.of(), List.of(), List.of(),
                new ResumeLinks(null, null, null, null, List.of()), List.of("skills", "experience", "summary")));
        var export = new TailoredResumeDocxExporter().export(version);
        String text;
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(export.bytes()))) {
            text = document.getParagraphs().stream().map(value -> value.getText()).reduce("", (left, right) -> left + "\n" + right);
        }
        assertAll(
                () -> assertTrue(export.bytes().length > 1000),
                () -> assertTrue(export.filename().endsWith(".docx")),
                () -> assertFalse(export.filename().contains("/")),
                () -> assertTrue(text.indexOf("Skills") < text.indexOf("Experience")),
                () -> assertTrue(text.contains("Candidate Name")),
                () -> assertTrue(text.contains("Reduced latency by 20%")),
                () -> assertFalse(text.contains("Kafka"))
        );
    }
}
