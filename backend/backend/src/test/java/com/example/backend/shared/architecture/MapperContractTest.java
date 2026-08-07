package com.example.backend.shared.architecture;

import com.example.backend.application.ApplicationMapper;
import com.example.backend.application.infrastructure.ApplicationDocument;
import com.example.backend.job.JobMapper;
import com.example.backend.job.api.dto.CreateJobRequest;
import com.example.backend.job.infrastructure.JobDocument;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class MapperContractTest {
    @Test
    void createMappingDoesNotAcceptOrPopulateOwnershipAndResponseHidesInternals() {
        JobDocument document = JobMapper.fromCreate(new CreateJobRequest(" T ", " D ", " L ", " C ", 1, 2));
        assertNull(document.getRecruiterId());
        assertNull(document.getExternalId());
        assertEquals("T", document.getTitle());
        assertFalse(Arrays.stream(JobMapper.toResponse(document).getClass().getRecordComponents())
                .anyMatch(component -> component.getName().equals("recruiterId") || component.getName().equals("externalId")));
    }

    @Test
    void applicationResponsesNeverExposeResumeStorageIdentifiers() {
        ApplicationDocument document = new ApplicationDocument();
        document.setResumeUrl("internal-random-name.pdf");
        String jsonShape = ApplicationMapper.toResponse(document).toString();
        assertFalse(jsonShape.contains("internal-random-name"));
        assertFalse(Arrays.stream(ApplicationMapper.toResponse(document).getClass().getRecordComponents())
                .anyMatch(component -> component.getName().toLowerCase().contains("resume")));
    }
}
