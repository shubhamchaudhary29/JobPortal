package com.example.backend.integration.smartrecruiters;

import static org.junit.jupiter.api.Assertions.*;

import com.example.backend.geo.application.Gazetteer;
import com.example.backend.geo.application.GeoResolver;
import com.example.backend.job.domain.GeoResolution;
import com.example.backend.job.domain.LocationHint;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

/** GEO-10E against the captured SmartRecruiters sample: postal code and street address are evidence; city geocodes are not. */
class SmartRecruitersLocationHintTest {
    private static final GeoResolver RESOLVER = new GeoResolver(new Gazetteer());

    private static List<SmartRecruitersPostingDto> postings() throws Exception {
        try (InputStream in = SmartRecruitersLocationHintTest.class.getClassLoader()
                .getResourceAsStream("ats-samples/smartrecruiters.json")) {
            return new ObjectMapper().readValue(in, SmartRecruitersPostingPage.class).content();
        }
    }

    @Test
    void streetAddressIsKeptAndPostalCodeRefinesTheCity() throws Exception {
        List<SmartRecruitersPostingDto> postings = postings();
        LocationHint address = SmartRecruitersJobSource.hint(postings.get(0).location());
        assertEquals("Sumadhura Capitol Towers (1826)", address.address());

        SmartRecruitersLocationDto pinned = postings.get(1).location();
        LocationHint postal = SmartRecruitersJobSource.hint(pinned);
        assertEquals("560066", postal.postalCode());
        assertNull(postal.address(), "'.' is a placeholder");
        assertNull(postal.lat());
        GeoResolution resolved = RESOLVER.resolve(SmartRecruitersJobSource.location(pinned), postal);
        assertEquals("Whitefield", new Gazetteer().localityById(resolved.localityIds().get(0)).orElseThrow().name());
    }

    @Test
    void aCityGeocodeWithoutAStreetAddressIsNoEvidence() throws Exception {
        SmartRecruitersLocationDto cityOnly = postings().get(2).location();
        assertNotNull(cityOnly.latitude(), "the sample has a point");
        assertNull(SmartRecruitersJobSource.hint(cityOnly), "one geocode per city name; never snapped");
        assertNull(SmartRecruitersJobSource.hint(new SmartRecruitersLocationDto("Pune", null, "in", false)));
    }
}
