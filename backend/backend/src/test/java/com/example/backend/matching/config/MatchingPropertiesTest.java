package com.example.backend.matching.config;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MatchingPropertiesTest {
    @Test
    void defaultsAreSaneAndWeightsMustTotalOneHundred() {
        var validator = Validation.buildDefaultValidatorFactory().getValidator();
        MatchingProperties defaults = new MatchingProperties();
        assertTrue(defaults.isWeightTotalValid());
        assertTrue(validator.validate(defaults).isEmpty());
        defaults.setSkillsWeight(39);
        assertFalse(defaults.isWeightTotalValid());
        assertTrue(validator.validate(defaults).stream()
                .anyMatch(value -> value.getMessage().contains("sum to 100")));
    }

    @Test
    void candidateWindowRemainsBounded() {
        var validator = Validation.buildDefaultValidatorFactory().getValidator();
        MatchingProperties properties = new MatchingProperties();
        properties.setCandidateWindow(49);
        assertFalse(validator.validate(properties).isEmpty());
        properties.setCandidateWindow(2001);
        assertFalse(validator.validate(properties).isEmpty());
    }
}
