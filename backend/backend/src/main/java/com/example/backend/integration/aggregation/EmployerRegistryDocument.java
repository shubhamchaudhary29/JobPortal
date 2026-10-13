package com.example.backend.integration.aggregation;

import java.time.Instant;
import java.util.Locale;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One ATS board. {@code boardKey} and {@code companyKey} are the lower-case keys behind the unique
 * {@code source+boardKey} index and the unique enabled-company index.
 */
@Data
@Document("employer_registry")
public class EmployerRegistryDocument {
    @Id private String id;
    private EmployerRegistryProperties.Source source;
    private String boardId;
    private String boardKey;
    private String company;
    private String companyKey;
    private boolean enabled;
    private String disabledReason;
    private Origin origin;
    private Instant createdAt;
    private String createdBy;
    private Instant updatedAt;
    private String updatedBy;

    public enum Origin { PROPERTIES, ADMIN }

    public static String boardKey(String boardId) { return boardId.trim().toLowerCase(Locale.ROOT); }
    public static String companyKey(String company) { return company.trim().toLowerCase(Locale.ROOT); }

    public EmployerRegistryProperties.Employer toEmployer() {
        return new EmployerRegistryProperties.Employer(company, source, boardId, enabled);
    }
}
