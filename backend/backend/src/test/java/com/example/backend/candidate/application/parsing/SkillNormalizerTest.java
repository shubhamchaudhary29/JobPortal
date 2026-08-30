package com.example.backend.candidate.application.parsing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillNormalizerTest {
    private final SkillNormalizer normalizer = new SkillNormalizer();

    @Test
    void canonicalizesRequiredAliasesAndRemovesCaseAndSeparatorDuplicates() {
        var values = normalizer.normalizeAll(List.of("springboot", "Spring Boot", "spring-boot", "JS", "JavaScript",
                "nodejs", "k8s", "amazon web services", "postgre sql", "mongo db"), "MANUAL", null);
        assertEquals(List.of("Spring Boot", "JavaScript", "Node.js", "Kubernetes", "AWS", "PostgreSQL", "MongoDB"),
                values.stream().map(value -> value.getName()).toList());
    }

    @Test
    void javaNeverNormalizesToOrMatchesJavascript() {
        assertEquals("Java", normalizer.normalize("JAVA", "MANUAL", null).getName());
        assertEquals("JavaScript", normalizer.normalize("javascript", "MANUAL", null).getName());
        assertEquals(List.of("JavaScript"), normalizer.extractKnownSkills("Built in JavaScript and TypeScript")
                .stream().filter(value -> value.getName().startsWith("Java")).map(value -> value.getName()).toList());
    }

    @Test
    void customSkillsRemainDistinctExtensibleCanonicalValues() {
        assertEquals("Rust Programming", normalizer.normalize("rust programming", "MANUAL", null).getName());
        assertTrue(normalizer.canonicalKnownName("not-a-real-alias").isEmpty());
    }
}
