package com.example.backend.integration.smartrecruiters;

import java.util.Optional;

/**
 * Posting details (description and public link) keyed by board and posting id. An entry is valid only for the
 * {@code releasedDate} it was fetched for, so a re-released posting is fetched again.
 */
public interface SmartRecruitersDetailCache {
    Optional<Detail> find(String board, String postingId, String releasedDate);
    void save(String board, String postingId, String releasedDate, Detail detail);

    record Detail(String description, String postingUrl, String applyUrl) { }
}
