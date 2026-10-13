package com.example.backend.integration.ashby;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AshbyJobSourceTest {
    @Test
    void employmentTypesMapToTheVocabularyUsedByOtherBoards() {
        assertAll(
                () -> assertEquals("Full-time", AshbyJobSource.employmentType("FullTime")),
                () -> assertEquals("Part-time", AshbyJobSource.employmentType("PartTime")),
                () -> assertEquals("Internship", AshbyJobSource.employmentType("Intern")),
                () -> assertEquals("Contract", AshbyJobSource.employmentType("Contract")),
                () -> assertEquals("Temporary", AshbyJobSource.employmentType("Temporary")),
                () -> assertNull(AshbyJobSource.employmentType(null)));
    }
}
