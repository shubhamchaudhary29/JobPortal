package com.example.backend.job.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.backend.integration.adzuna.AdzunaClient;
import java.time.LocalDateTime;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class JobSearchMongoIntegrationTest {
    @Autowired JobSearchRepository search;
    @Autowired MongoTemplate mongo;
    @Autowired MockMvc mvc;
    @MockitoBean AdzunaClient adzunaClient;

    @BeforeEach
    void setUp() {
        mongo.remove(new Query(), JobDocument.class);
        save("Backend Engineer", "Pune", "manual", true, "recruiter-1", 4);
        save("Data Engineer", "Pune", "adzuna", true, null, 3);
        save("Support Engineer", "Mumbai", "adzuna", true, null, 2);
        save("Inactive Engineer", "Pune", "adzuna", false, null, 1);
        mongo.getCollection("jobs").insertOne(new Document("title", "Legacy Engineer")
                .append("description", "older record").append("location", "Delhi").append("company", "Legacy Co")
                .append("salary", 1.0).append("experience", 1.0).append("source", "manual")
                .append("createdAt", LocalDateTime.of(2026, 1, 1, 0, 0)));
    }

    @Test
    void excludesInactiveJobsButKeepsLegacyAndCombinesManualAndImportedJobs() {
        Page<JobDocument> result = search.search(null, null, null, PageRequest.of(0, 10, Sort.by("title")));
        List<String> titles = result.map(JobDocument::getTitle).toList();

        assertEquals(4, result.getTotalElements());
        assertTrue(titles.containsAll(List.of("Backend Engineer", "Data Engineer", "Support Engineer", "Legacy Engineer")));
        assertFalse(titles.contains("Inactive Engineer"));
    }

    @Test
    void filtersPaginatesAndSortsAgainstMongo() {
        Page<JobDocument> importedPune = search.search("Engineer", "Pune", "adzuna",
                PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "title")));
        assertEquals(1, importedPune.getTotalElements());
        assertEquals("Data Engineer", importedPune.getContent().get(0).getTitle());

        Page<JobDocument> sorted = search.search(null, null, null,
                PageRequest.of(1, 2, Sort.by(Sort.Direction.ASC, "title")));
        assertEquals(4, sorted.getTotalElements());
        assertEquals(2, sorted.getContent().size());
        assertEquals("Legacy Engineer", sorted.getContent().get(0).getTitle());
        assertEquals("Support Engineer", sorted.getContent().get(1).getTitle());
    }

    @Test
    void publicHttpSearchMakesNoProviderCalls() throws Exception {
        mvc.perform(get("/api/v1/jobs").param("q", "Engineer").param("size", "2").param("sort", "title,asc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[0].title").value("Backend Engineer"));

        verifyNoInteractions(adzunaClient);
    }

    @Test
    void matchingWindowIsBoundedAndExcludesInactiveAndReconciliationRecords() {
        JobDocument conflicted = new JobDocument();
        conflicted.setTitle("Conflicted Engineer"); conflicted.setDescription("Java"); conflicted.setLocation("Pune");
        conflicted.setCompany("Co"); conflicted.setSource("adzuna"); conflicted.setActive(true);
        conflicted.setReconciliationConflictId("conflict-1");
        mongo.save(conflicted);

        List<JobDocument> results = search.matchingCandidates("Pune", "adzuna", 1);
        assertEquals(1, results.size());
        assertEquals("Data Engineer", results.get(0).getTitle());
        assertFalse(search.matchingCandidates(null, null, 20).stream()
                .anyMatch(job -> "Inactive Engineer".equals(job.getTitle()) || "Conflicted Engineer".equals(job.getTitle())));
    }

    private void save(String title, String location, String source, boolean active, String recruiterId, int day) {
        JobDocument job = new JobDocument();
        job.setTitle(title); job.setDescription(title + " description"); job.setLocation(location); job.setCompany("Co");
        job.setSalary(1); job.setExperience(1); job.setSource(source); job.setRecruiterId(recruiterId); job.setActive(active);
        job.setCreatedAt(LocalDateTime.of(2026, 1, day, 0, 0));
        mongo.save(job);
    }
}
