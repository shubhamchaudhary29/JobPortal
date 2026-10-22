package com.example.backend.geo.infrastructure;

import java.time.Instant;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One office of a company, pinned by an ADMIN on the map (no geocoding). {@code companyKey} is
 * {@code CompanyNames.key(company)} and, with {@code placeId}, decides which jobs the office represents.
 * {@code company} and {@code placeId} are identity: changing either means creating a new office.
 * {@code source} (GEO-8D) is "ADMIN" (a pin; also when missing on older documents) or "OSM" when an ADMIN picked an
 * OpenStreetMap match, whose reference is {@code osmRef}.
 */
@Data
@Document("company_offices")
public class CompanyOfficeDocument {
    @Id private String id;
    private String companyKey;
    private String company;
    private String label;
    private String placeId;
    private double lat;
    private double lng;
    private boolean enabled;
    private Instant createdAt;
    private String createdBy;
    private Instant updatedAt;
    private String updatedBy;
    private String source;
    private String osmRef;
}
