package com.example.backend.candidate.application.storage;

public enum ResumeDocumentType {
    PDF("pdf", "application/pdf"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final String extension;
    private final String contentType;
    ResumeDocumentType(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }
    public String extension() { return extension; }
    public String contentType() { return contentType; }
}
