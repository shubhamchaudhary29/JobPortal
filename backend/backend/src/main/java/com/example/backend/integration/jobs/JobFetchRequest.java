package com.example.backend.integration.jobs;
/** {@code location} narrows a search-API query; ATS board fetches leave it null. */
public record JobFetchRequest(String keyword, int page, String boardId, String company, String location) {
    public JobFetchRequest(String keyword, int page, String boardId, String company) { this(keyword, page, boardId, company, null); }
    public JobFetchRequest(String keyword, int page) { this(keyword, page, null, null, null); }
}
