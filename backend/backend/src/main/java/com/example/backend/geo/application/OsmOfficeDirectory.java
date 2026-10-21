package com.example.backend.geo.application;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Named company offices and office buildings from OpenStreetMap (ODbL), from {@code in-osm-offices.tsv} built by
 * {@code scripts/build-osm-geo.py}. Each row already carries its gazetteer parent city. Immutable after startup.
 * Offices are applied at read time, never written into jobs, so this file is not part of the resolver version.
 */
@Component
public class OsmOfficeDirectory {
    private static final Logger log = LoggerFactory.getLogger(OsmOfficeDirectory.class);
    static final String OFFICES = "geo/in-osm-offices.tsv";

    private final List<OsmOffice> offices;

    @Autowired
    public OsmOfficeDirectory(Gazetteer gazetteer) {
        long before = Gazetteer.usedHeapAfterGc();
        this.offices = load(Gazetteer.readOsm(OFFICES).rows(), gazetteer);
        long officeBytes = Math.max(0, Gazetteer.usedHeapAfterGc() - before);
        log.info("event=geo_osm_loaded localities={} offices={} heap_delta_mb={}", gazetteer.localities().size(),
                offices.size(), Math.round((gazetteer.localityHeapBytes() + officeBytes) / 1048576.0 * 10) / 10.0);
    }

    OsmOfficeDirectory(List<OsmOffice> offices) { this.offices = List.copyOf(offices); }

    public List<OsmOffice> offices() { return offices; }

    public int size() { return offices.size(); }

    private static List<OsmOffice> load(List<String[]> rows, Gazetteer gazetteer) {
        List<OsmOffice> loaded = new ArrayList<>(rows.size());
        for (String[] row : rows) {
            if (row.length < 9 || gazetteer.byId(row[6]).isEmpty()) continue;
            loaded.add(new OsmOffice(row[0], row[1], row[2], row[3], row[4], row[5], row[6], Double.parseDouble(row[7]),
                    Double.parseDouble(row[8])));
        }
        return List.copyOf(loaded);
    }

    /** {@code osmRef} is {@code osm-} + n/w/r + the OSM id; {@code kind} is the matching tag, e.g. {@code office=it}. */
    public record OsmOffice(String osmRef, String name, String nameEn, String brand, String operator, String kind,
                            String placeId, double lat, double lng) {
        /** The object's page on openstreetmap.org, for checking a match by eye. */
        public String url() {
            String type = switch (osmRef.charAt(4)) {
                case 'n' -> "node";
                case 'w' -> "way";
                default -> "relation";
            };
            return "https://www.openstreetmap.org/" + type + "/" + osmRef.substring(5);
        }
    }
}
