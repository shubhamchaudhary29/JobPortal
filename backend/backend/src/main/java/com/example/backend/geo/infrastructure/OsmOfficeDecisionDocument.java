package com.example.backend.geo.infrastructure;

import java.time.Instant;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * An ADMIN's decision on one OpenStreetMap office match (GEO-8D): PICK (it became an admin office) or REJECT (it is not
 * this company's office in this city). One per (company key, place, OSM reference); a later decision replaces it.
 */
@Data
@Document("osm_office_decisions")
public class OsmOfficeDecisionDocument {
    @Id private String id;
    private String companyKey;
    private String placeId;
    private String osmRef;
    private Decision decision;
    private String actor;
    private Instant at;

    public enum Decision { PICK, REJECT }
}
