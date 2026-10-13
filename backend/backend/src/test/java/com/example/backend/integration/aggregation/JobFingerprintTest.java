package com.example.backend.integration.aggregation;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class JobFingerprintTest {
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = ';', value = {
            "Bangalore; bengaluru", "Bengaluru; bengaluru", "BENGALURU, Karnataka, India; bengaluru",
            "bangalore, KA, India; bengaluru", "Bengaluru Karnataka; bengaluru", "Bangalore Urban; bengaluru",
            "Bengaluru (Hybrid); bengaluru", "Gurgaon; gurugram", "Gurugram, Haryana; gurugram",
            "Gurgaon, HR, IN; gurugram", "Bombay; mumbai", "Mumbai, Maharashtra, India; mumbai",
            "New Delhi; delhi", "Delhi NCR; delhi", "New Delhi, Delhi, India; delhi", "Delhi, NCT of Delhi; delhi",
            "Madras; chennai", "Chennai, TN; chennai", "Calcutta; kolkata", "Poona; pune",
            "Pune, Maharashtra, India; pune", "Trivandrum; thiruvananthapuram", "Cochin; kochi",
            "Ernakulam, Kerala; kochi", "Mysore; mysuru", "Mangalore; mangaluru", "Baroda; vadodara",
            "Vizag; visakhapatnam", "Belgaum; belagavi", "Hubli; hubballi", "Gauhati; guwahati",
            "Banaras; varanasi", "Benares; varanasi", "Pondicherry; puducherry", "Simla; shimla",
            "Amdavad; ahmedabad", "Hyderabad, Telangana, IN; hyderabad", "Noida, Uttar Pradesh; noida",
            "India; india", "IN; in", "Remote; remote", "remote; remote", "Remote - India; remote",
            "Remote, India; remote", "India (Remote); remote", "Work from home; remote", "WFH - India; remote",
            "Remote - United States; remote united states", "Bengaluru / Mumbai; bengaluru / mumbai",
            "Mumbai | Bangalore; bengaluru / mumbai", "San Francisco, CA, United States; san francisco ca united states"
    })
    void locationAliasesAndSuffixesCollapseEquivalentPlaces(String raw, String expected) {
        assertEquals(expected, JobFingerprint.location(raw));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = ';', value = {
            "Razorpay; razorpay", "Razorpay Software Pvt Ltd; razorpay", "Razorpay Software Private Limited; razorpay",
            "Razorpay Software Pvt. Ltd.; razorpay", "Freshworks Inc.; freshworks", "Acme LLC; acme",
            "Infosys Technologies Limited; infosys", "Microsoft India; microsoft", "Microsoft India Pvt Ltd; microsoft",
            "Bundl Technologies Private Limited; swiggy", "One97 Communications Limited; paytm",
            "FSN E-Commerce Ventures Limited; nykaa", "ANI Technologies Pvt Ltd; ola", "UrbanClap; urban company",
            "Dreamplug Technologies Pvt Ltd; cred", "Sorting Hat Technologies Pvt Ltd; unacademy",
            "Mohalla Tech Pvt Ltd; sharechat", "KiranaKart Technologies Pvt Ltd; zepto", "Grofers India Pvt Ltd; blinkit",
            "Flipkart Internet Private Limited; flipkart", "Nextbillion Technology Pvt Ltd; groww",
            "Pocket FM; pocketfm", "India Ltd; india", "Software Limited; software", "Tata Consultancy Services; tata consultancy services",
            "Procter & Gamble; procter and gamble"
    })
    void companySuffixesAndAliasesCollapseLegalNames(String raw, String expected) {
        assertEquals(expected, JobFingerprint.company(raw));
    }

    @Test
    void equivalentListingsShareOneFingerprint() {
        String base = JobFingerprint.of("Razorpay", "Backend Engineer", "Bengaluru");
        assertAll(
                () -> assertEquals(base, JobFingerprint.of("Razorpay Software Private Limited", " backend  engineer ",
                        "Bangalore, Karnataka, India")),
                () -> assertEquals(base, JobFingerprint.of("RAZORPAY SOFTWARE PVT LTD", "Backend Engineer",
                        "bangalore, KA, IN")),
                () -> assertEquals(JobFingerprint.of("Acme", "Designer", "Remote"),
                        JobFingerprint.of("Acme Inc", "Designer", "Remote - India")),
                () -> assertEquals(JobFingerprint.of("Acme", "Engineer", "Gurugram"),
                        JobFingerprint.of("Acme", "Engineer", "Gurgaon, Haryana")));
    }

    @Test
    void lookAlikesStayDistinct() {
        String base = JobFingerprint.of("Razorpay", "Backend Engineer", "Bengaluru");
        assertAll(
                () -> assertNotEquals(base, JobFingerprint.of("Razorpay", "Backend Engineer", "Mumbai")),
                () -> assertNotEquals(base, JobFingerprint.of("Razorpay", "Backend Engineer II", "Bengaluru")),
                () -> assertNotEquals(base, JobFingerprint.of("Razorpayments", "Backend Engineer", "Bengaluru")),
                () -> assertNotEquals(JobFingerprint.of("Acme", "Engineer", "Noida"),
                        JobFingerprint.of("Acme", "Engineer", "Greater Noida")),
                () -> assertNotEquals(JobFingerprint.of("Acme", "Engineer", "Navi Mumbai"),
                        JobFingerprint.of("Acme", "Engineer", "Mumbai")),
                () -> assertNotEquals(JobFingerprint.of("Acme", "C++ Developer", "Pune"),
                        JobFingerprint.of("Acme", "C# Developer", "Pune")),
                () -> assertNotEquals(JobFingerprint.of("Acme", "Engineer", "Remote - India"),
                        JobFingerprint.of("Acme", "Engineer", "Remote - United States")),
                () -> assertNotEquals(JobFingerprint.of("Acme", "Engineer", "Remote"),
                        JobFingerprint.of("Acme", "Engineer", "Bengaluru")),
                () -> assertNotEquals(JobFingerprint.of("Acme India", "Engineer", "Pune"),
                        JobFingerprint.of("India", "Engineer", "Pune")),
                () -> assertNotEquals(JobFingerprint.of("Eternal", "Engineer", "Delhi"),
                        JobFingerprint.of("Zomato", "Engineer", "Delhi")));
    }

    @Test
    void missingCompanyOrTitleHasNoFingerprintAndOutputIsDeterministic() {
        assertNull(JobFingerprint.of(null, "Engineer", "Pune"));
        assertNull(JobFingerprint.of("Acme", null, "Pune"));
        assertEquals(JobFingerprint.of("Acme", "Engineer", null), JobFingerprint.of("Acme", "Engineer", " "));
        assertEquals(64, JobFingerprint.of("Acme", "Engineer", "Pune").length());
        assertEquals(JobFingerprint.of("Acme", "Engineer", "Pune"), JobFingerprint.of("Acme", "Engineer", "Pune"));
        assertEquals(2, JobFingerprint.VERSION);
    }

    @Test
    void everyAliasTargetIsAKnownCityAndAliasKeysAreNormalized() throws Exception {
        JsonNode cities = table("aggregation/city-aliases.json");
        cities.get("aliases").fields().forEachRemaining(entry -> {
            assertEquals(entry.getValue().asText(), JobFingerprint.location(entry.getKey()), entry.getKey());
            assertEquals(entry.getKey(), entry.getKey().toLowerCase().replaceAll("[^a-z0-9 ]", ""), entry.getKey());
        });
        JsonNode companies = table("aggregation/company-aliases.json");
        companies.get("aliases").fields().forEachRemaining(entry ->
                assertEquals(entry.getValue().asText(), JobFingerprint.company(entry.getKey()), entry.getKey()));
    }

    @Test
    void displayNormalizerKeepsABareRemoteLocation() {
        assertAll(
                () -> assertEquals("Remote", ExternalJobNormalizer.location("Remote")),
                () -> assertEquals("Remote", ExternalJobNormalizer.location(" remote ")),
                () -> assertEquals("Remote - India", ExternalJobNormalizer.location("Remote - India")),
                () -> assertEquals("Remote - India", ExternalJobNormalizer.location("remote,India")),
                () -> assertEquals("Remote - India", ExternalJobNormalizer.location("Remote India")),
                () -> assertEquals("Bengaluru", ExternalJobNormalizer.location("Bengaluru")),
                () -> assertEquals("Remoteville", ExternalJobNormalizer.location("Remoteville")));
    }

    private JsonNode table(String resource) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            return new ObjectMapper().readTree(input);
        }
    }
}
