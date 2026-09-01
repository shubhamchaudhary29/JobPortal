package com.example.backend.copilot.application;

import com.example.backend.copilot.domain.CopilotModels.ResumeCertification;
import com.example.backend.copilot.domain.CopilotModels.ResumeContent;
import com.example.backend.copilot.domain.CopilotModels.ResumeEducation;
import com.example.backend.copilot.domain.CopilotModels.ResumeExperience;
import com.example.backend.copilot.domain.CopilotModels.ResumeProject;
import com.example.backend.copilot.infrastructure.TailoredResumeVersionDocument;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

@Component
public class TailoredResumeDocxExporter {
    public Export export(TailoredResumeVersionDocument version) throws IOException {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ResumeContent content = version.getContent();
            XWPFParagraph name = document.createParagraph();
            name.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun nameRun = name.createRun();
            nameRun.setBold(true); nameRun.setFontSize(18); nameRun.setText(safe(content.fullName()));
            XWPFParagraph contact = document.createParagraph();
            contact.setAlignment(ParagraphAlignment.CENTER);
            contact.createRun().setText(String.join(" | ", listOf(content.email(), content.phone(), content.location())));
            for (String section : list(content.sectionOrder())) writeSection(document, section, content);
            document.write(output);
            return new Export(output.toByteArray(), filename(version));
        }
    }

    private void writeSection(XWPFDocument document, String section, ResumeContent content) {
        switch (section == null ? "" : section.toLowerCase(Locale.ROOT)) {
            case "summary" -> { if (notBlank(content.summary())) { heading(document, "Professional Summary"); paragraph(document, content.summary()); } }
            case "skills" -> { if (!list(content.skills()).isEmpty()) { heading(document, "Skills"); paragraph(document, String.join(" • ", content.skills())); } }
            case "experience" -> { if (!list(content.experience()).isEmpty()) { heading(document, "Experience"); content.experience().forEach(value -> experience(document, value)); } }
            case "projects" -> { if (!list(content.projects()).isEmpty()) { heading(document, "Projects"); content.projects().forEach(value -> project(document, value)); } }
            case "education" -> { if (!list(content.education()).isEmpty()) { heading(document, "Education"); content.education().forEach(value -> education(document, value)); } }
            case "certifications" -> { if (!list(content.certifications()).isEmpty()) { heading(document, "Certifications"); content.certifications().forEach(value -> certification(document, value)); } }
            case "links" -> links(document, content);
            default -> { }
        }
    }

    private void heading(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setStyle("Heading2");
        XWPFRun run = paragraph.createRun(); run.setBold(true); run.setFontSize(12); run.setText(text);
    }
    private void paragraph(XWPFDocument document, String text) { document.createParagraph().createRun().setText(safe(text)); }
    private void experience(XWPFDocument document, ResumeExperience value) {
        itemHeading(document, value.title(), value.organization(), dates(value.startDate(), value.endDate(), value.currentlyWorking()));
        paragraph(document, value.description());
        if (!list(value.technologies()).isEmpty()) paragraph(document, "Technologies: " + String.join(", ", value.technologies()));
    }
    private void project(XWPFDocument document, ResumeProject value) {
        itemHeading(document, value.name(), null, dates(value.startDate(), value.endDate(), false));
        paragraph(document, value.description());
        if (!list(value.technologies()).isEmpty()) paragraph(document, "Technologies: " + String.join(", ", value.technologies()));
        if (notBlank(value.url())) paragraph(document, value.url());
    }
    private void education(XWPFDocument document, ResumeEducation value) {
        itemHeading(document, value.degree(), value.institution(), dates(value.startDate(), value.endDate(), false));
        paragraph(document, String.join(" | ", listOf(value.fieldOfStudy(), value.grade())));
        if (notBlank(value.description())) paragraph(document, value.description());
    }
    private void certification(XWPFDocument document, ResumeCertification value) {
        itemHeading(document, value.name(), value.issuer(), value.issueDate());
        if (notBlank(value.credentialUrl())) paragraph(document, value.credentialUrl());
    }
    private void links(XWPFDocument document, ResumeContent content) {
        List<String> links = listOf(content.links() == null ? null : content.links().linkedIn(),
                content.links() == null ? null : content.links().github(), content.links() == null ? null : content.links().portfolio(),
                content.links() == null ? null : content.links().website());
        if (content.links() != null) {
            java.util.ArrayList<String> all = new java.util.ArrayList<>(links); all.addAll(list(content.links().other())); links = all;
        }
        if (!links.isEmpty()) { heading(document, "Links"); links.forEach(value -> paragraph(document, value)); }
    }
    private void itemHeading(XWPFDocument document, String first, String second, String date) {
        XWPFParagraph paragraph = document.createParagraph();
        XWPFRun run = paragraph.createRun(); run.setBold(true); run.setText(String.join(" — ", listOf(first, second)));
        if (notBlank(date)) { XWPFRun dateRun = paragraph.createRun(); dateRun.setText(" | " + date); }
    }
    private String dates(String start, String end, boolean current) {
        if (!notBlank(start) && !notBlank(end) && !current) return null;
        return safe(start) + " – " + (current ? "Present" : safe(end));
    }
    private String filename(TailoredResumeVersionDocument version) {
        String title = version.getJobSnapshot() == null ? "job" : safe(version.getJobSnapshot().title());
        String safe = title.replaceAll("[^\\p{L}\\p{N}._-]+", "-").replaceAll("-+", "-");
        if (safe.isBlank()) safe = "job";
        if (safe.length() > 80) safe = safe.substring(0, 80);
        return "tailored-resume-" + safe + "-v" + version.getVersionNumber() + ".docx";
    }
    private List<String> listOf(String... values) { return java.util.Arrays.stream(values).filter(this::notBlank).map(String::strip).toList(); }
    private <T> List<T> list(List<T> values) { return values == null ? List.of() : values; }
    private boolean notBlank(String value) { return value != null && !value.isBlank(); }
    private String safe(String value) { return value == null ? "" : value; }
    public record Export(byte[] bytes, String filename) { }
}
