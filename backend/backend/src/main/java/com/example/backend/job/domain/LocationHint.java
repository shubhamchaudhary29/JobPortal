package com.example.backend.job.domain;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Structured location from a provider (Adzuna {@code location.area[]} and coordinates; GEO-10E: an ATS postal code and
 * street address). Evidence for a locality of a place the location text names, never a pin. Never persisted as is:
 * listings keep the parts re-resolution needs ({@code rawArea}, {@code rawPostalCode}, {@code rawAddress}, the point).
 */
public record LocationHint(List<String> area, Double lat, Double lng, String postalCode, String address) {
    static final int MAX_ADDRESS_LENGTH = 300;
    private static final Pattern PIN = Pattern.compile("^[1-9]\\d{5}$");

    public LocationHint {
        area = area == null ? List.of() : area.stream().filter(java.util.Objects::nonNull).toList();
        postalCode = postalCode == null || !PIN.matcher(postalCode.trim()).matches() ? null : postalCode.trim();
        address = address == null ? null : address.trim();
        if (address != null && address.length() > MAX_ADDRESS_LENGTH) address = address.substring(0, MAX_ADDRESS_LENGTH);
        // Placeholders ("." in SmartRecruiters) say nothing.
        if (address != null && address.replaceAll("[\\p{Punct}\\s]", "").isEmpty()) address = null;
    }

    /** Area and point only (Adzuna; pre-GEO-10E shape). */
    public LocationHint(List<String> area, Double lat, Double lng) { this(area, lat, lng, null, null); }
}
