package com.example.backend.integration.aggregation;

import java.time.Instant;
import java.util.Map;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Append-only record of one registry change or validation, with the acting ADMIN and before/after values. */
@Data
@Document("employer_registry_audit")
public class EmployerRegistryAuditDocument {
    @Id private String id;
    private String entryId;
    private Action action;
    private String actor;
    private Instant at;
    private Map<String, Object> before;
    private Map<String, Object> after;

    public enum Action { CREATE, UPDATE, ENABLE, DISABLE, VALIDATE }
}
