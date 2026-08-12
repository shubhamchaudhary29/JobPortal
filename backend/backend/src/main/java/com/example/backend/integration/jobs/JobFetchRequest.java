package com.example.backend.integration.jobs;
public record JobFetchRequest(String keyword, int page, String boardId, String company) {
    public JobFetchRequest(String keyword, int page) { this(keyword, page, null, null); }
}
