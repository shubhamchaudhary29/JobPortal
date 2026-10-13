package com.example.backend.integration.search;

/** One provider request: a keyword, an optional location, and a 1-based page. */
public record SearchQuery(String keyword, String location, int page) { }
