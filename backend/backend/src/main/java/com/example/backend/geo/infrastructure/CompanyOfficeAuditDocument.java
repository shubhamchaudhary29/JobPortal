package com.example.backend.geo.infrastructure;

import java.time.Instant;
import java.util.Map;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Append-only record of one office change, with the acting ADMIN and before/after values. OpenStreetMap decisions
 * (GEO-8D) are audited here too: OSM_PICK on the created office, OSM_REJECT with the {@code osm-…} reference as
 * {@code officeId}.
 */
@Data
@Document("company_office_audit")
public class CompanyOfficeAuditDocument {
    @Id private String id;
    private String officeId;
    private Action action;
    private String actor;
    private Instant at;
    private Map<String, Object> before;
    private Map<String, Object> after;

    public enum Action { CREATE, UPDATE, ENABLE, DISABLE, OSM_PICK, OSM_REJECT }
}
