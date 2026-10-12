package com.example.backend.integration.smartrecruiters;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Request bounds for one SmartRecruiters board per run. */
@Component
public class SmartRecruitersProperties {
    private final int pageSize;
    private final int maxPages;
    private final int maxDetailRequests;
    private final int detailRetentionDays;

    public SmartRecruitersProperties(
            @Value("${job-aggregation.providers.smartrecruiters.page-size:100}") int pageSize,
            @Value("${job-aggregation.providers.smartrecruiters.max-pages:20}") int maxPages,
            @Value("${job-aggregation.providers.smartrecruiters.max-detail-requests:100}") int maxDetailRequests,
            @Value("${job-aggregation.providers.smartrecruiters.detail-retention-days:30}") int detailRetentionDays) {
        if (pageSize < 1 || pageSize > 100 || maxPages < 1 || maxPages > 100
                || maxDetailRequests < 0 || maxDetailRequests > 1_000
                || detailRetentionDays < 1 || detailRetentionDays > 365) {
            throw new IllegalArgumentException("SmartRecruiters request bounds are outside configured limits");
        }
        this.pageSize = pageSize;
        this.maxPages = maxPages;
        this.maxDetailRequests = maxDetailRequests;
        this.detailRetentionDays = detailRetentionDays;
    }

    public int pageSize() { return pageSize; }
    public int maxPages() { return maxPages; }
    public int maxDetailRequests() { return maxDetailRequests; }
    public int detailRetentionDays() { return detailRetentionDays; }
}
