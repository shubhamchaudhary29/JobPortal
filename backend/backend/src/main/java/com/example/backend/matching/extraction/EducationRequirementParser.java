package com.example.backend.matching.extraction;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class EducationRequirementParser {
    public List<String> parse(String description) {
        String original = description == null ? "" : description.replaceAll("\\s+", " ");
        String value = original.toLowerCase(Locale.ROOT);
        List<String> requirements = new ArrayList<>();
        if (value.matches(".*\\b(b\\.?\\s?tech|b\\.?e\\.?|bachelor(?:'s)?|bsc|b\\.?sc|b\\.?s\\.?)\\b.*")) requirements.add("BACHELOR");
        if (value.matches(".*\\b(m\\.?\\s?tech|mca|master(?:'s)?|msc|m\\.?sc|m\\.?s\\.?)\\b.*")) requirements.add("MASTER");
        if (value.matches(".*\\b(computer science|cse|information technology|computer engineering)\\b.*")
                || original.matches(".*\\b(CS|IT)\\b.*")) requirements.add("COMPUTING_FIELD");
        return List.copyOf(requirements);
    }
}
