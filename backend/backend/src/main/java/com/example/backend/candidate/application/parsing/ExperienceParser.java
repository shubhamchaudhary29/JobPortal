package com.example.backend.candidate.application.parsing;

import com.example.backend.candidate.infrastructure.CandidateProfileDocument.Experience;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class ExperienceParser {
    private final SkillNormalizer skills;
    public ExperienceParser(SkillNormalizer skills) { this.skills = skills; }

    public List<Experience> parse(List<String> lines) {
        List<Experience> result = new ArrayList<>();
        for (List<String> block : ParsingSupport.blocks(lines)) {
            if (block.isEmpty()) continue;
            String first = block.get(0);
            String title = first;
            String organization = block.size() > 1 ? block.get(1) : "Not confidently parsed";
            int at = first.toLowerCase(Locale.ROOT).indexOf(" at ");
            if (at > 0) { title = first.substring(0, at); organization = first.substring(at + 4); }
            ParsingSupport.DateRange dates = ParsingSupport.dates(block);
            String description = block.size() > 2 ? ParsingSupport.join(block, 2) : null;
            List<String> technologies = skills.extractKnownSkills(String.join(" ", block)).stream()
                    .map(com.example.backend.candidate.infrastructure.CandidateProfileDocument.Skill::getName).toList();
            result.add(new Experience(organization, title, null, null, dates.start(), dates.end(), dates.current(),
                    description, technologies));
        }
        return result;
    }
}
